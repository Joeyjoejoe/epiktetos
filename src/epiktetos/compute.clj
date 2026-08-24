(ns epiktetos.compute
  (:require [clojure.java.io :as io]
            [epiktetos.registrar :as registrar]
            [epiktetos.opengl.shader-program :as prog]
            [epiktetos.shader-input.registration :as input])
  (:import (org.lwjgl.opengl GL20 GL30 GL42 GL43)
           (org.lwjgl BufferUtils)))

(def SPEC-KEYS
  "The keys of a reg-compute spec map"
  #{:source :invocations :workgroups :step :after})

(defn- after-of
  "The :after dependencies of a compute, normalized to a vector of
   ids present in computes.
   computes  - map {compute-k compute} sharing one step
   compute-k - keyword, compute id
   Returns a vector of compute-ks."
  [computes compute-k]
  (let [after (:after (get computes compute-k))]
    (->> (if (keyword? after) [after] after)
         (filterv #(contains? computes %)))))

(defn- resolve-order
  "Orders the computes of one step by their :after dependencies —
   Kahn by waves, deterministic (unrelated computes sort by id).
   computes - map {compute-k compute} sharing one step
   Returns {:ordered [compute-k] :cyclic #{compute-k}}."
  [computes]
  (loop [remaining (sort-by str (keys computes))
         ordered   []
         placed    #{}]
    (if (empty? remaining)
      {:ordered ordered :cyclic #{}}
      (let [ready (filter (fn [k] (every? placed (after-of computes k)))
                          remaining)]
        (if (seq ready)
          (recur (remove (set ready) remaining)
                 (into ordered ready)
                 (into placed ready))
          {:ordered ordered :cyclic (set remaining)})))))

(defn topo-sort
  "Orders the computes of one step for dispatch: every :after
   dependency precedes its dependent, unrelated computes in id order.
   Cyclic computes are appended in id order — never throws (the cycle
   is a registration error in development, tolerated in production).
   computes - map {compute-k compute} sharing one step
   Returns a vector of compute-ks."
  [computes]
  (let [{:keys [ordered cyclic]} (resolve-order computes)]
    (into ordered (sort-by str cyclic))))

(defn- dispatch-size-form?
  "True when value is a valid dispatch size declaration: an integer,
   a vector of 1 to 3 integers, or a function of db."
  [value]
  (or (fn? value)
      (nat-int? value)
      (and (vector? value)
           (<= 1 (count value) 3)
           (every? nat-int? value))))

(defn- same-step-computes
  "The registered computes sharing the declaration's step, the
   declaration included.
   id       - keyword, compute id
   spec     - map, the reg-compute spec
   computes - map {compute-k compute}, registered computes
   Returns a map {compute-k compute}."
  [id spec computes]
  (let [step (get spec :step :step/frame)]
    (-> (into {} (filter (fn [[_ c]] (= step (get c :step :step/frame)))
                         computes))
        (assoc id spec))))

(defn validate-declaration!
  "Validates a reg-compute declaration from the event handler — the
   pure stage, where an error pauses recoverably (development mode):
   a :source on the classpath, exactly one of :invocations and
   :workgroups in a valid form, a known :step, a well-formed :after
   and no cycle among the step's computes. GLSL compilation and
   linking stay effects-side.
   id       - keyword, compute id
   spec     - map, the reg-compute spec
   computes - map {compute-k compute}, registered computes
   Returns nil."
  [id spec computes]
  (when-not (map? spec)
    (throw (ex-info "Compute declaration must be a map"
                    {:compute/id    id
                     :compute/value spec})))
  (when-let [unknown (seq (remove SPEC-KEYS (keys spec)))]
    (throw (ex-info (str "Unknown reg-compute key(s) " (pr-str (vec unknown)))
                    {:compute/id           id
                     :compute/unknown-keys (vec unknown)
                     :allowed              SPEC-KEYS})))
  (let [{:keys [source invocations workgroups step after]} spec]
    (when-not (string? source)
      (throw (ex-info "Compute declaration requires a :source, the classpath path of its GLSL file"
                      {:compute/id id
                       :source     source})))
    (when-not (io/resource source)
      (throw (ex-info (str "Compute shader file not found on the classpath: " source)
                      {:compute/id id
                       :source     source})))
    (when (= (some? invocations) (some? workgroups))
      (throw (ex-info "Compute declaration requires exactly one of :invocations (elements) or :workgroups (workgroup counts, expert form)"
                      {:compute/id  id
                       :invocations invocations
                       :workgroups  workgroups})))
    (let [[size-k size] (if (some? invocations)
                          [:invocations invocations]
                          [:workgroups workgroups])]
      (when-not (dispatch-size-form? size)
        (throw (ex-info (str "Invalid " size-k " " (pr-str size)
                             " — expected an integer, [x y], [x y z], or (fn [db] ...)")
                        {:compute/id id
                         size-k      size}))))
    (when (some? step)
      (try
        (input/assert-known-step! step)
        (catch clojure.lang.ExceptionInfo e
          (throw (ex-info (ex-message e)
                          (assoc (ex-data e) :compute/id id)
                          e)))))
    (when (and (some? after)
               (not (keyword? after))
               (not (and (vector? after) (every? keyword? after))))
      (throw (ex-info (str "Invalid :after " (pr-str after)
                           " — expected a compute id or a vector of compute ids")
                      {:compute/id id
                       :after      after})))
    (let [{:keys [cyclic]} (resolve-order (same-step-computes id spec computes))]
      (when (seq cyclic)
        (throw (ex-info (str "Cyclic :after dependencies among computes of "
                             (get spec :step :step/frame) ": " (pr-str cyclic))
                        {:compute/id    id
                         :compute/cycle cyclic
                         :step          (get spec :step :step/frame)})))))
  nil)

(defn- local-size
  "Introspects the workgroup local size of a linked compute program.
   program-id - int, GL program id
   Returns [x y z] of ints."
  [program-id]
  (let [size (BufferUtils/createIntBuffer 3)]
    (GL20/glGetProgramiv ^int program-id GL43/GL_COMPUTE_WORK_GROUP_SIZE size)
    [(.get size 0) (.get size 1) (.get size 2)]))

(defn- max-workgroup-counts
  "The GL workgroup count limits of the context.
   Returns [x y z] of ints."
  []
  (mapv #(GL30/glGetIntegeri GL43/GL_MAX_COMPUTE_WORK_GROUP_COUNT %) [0 1 2]))

(defn- derive-computes-by-step
  "Derives the dispatch plan from the registered computes.
   computes - map {compute-k compute}
   Returns a map {step [compute-k]}, topologically ordered."
  [computes]
  (->> computes
       (group-by (fn [[_ compute]] (:step compute)))
       (map (fn [[step group]] [step (topo-sort (into {} group))]))
       (into {})))

(defn- rearm-compute!
  "Removes a compute from the degraded set, so its warning can fire
   again after a fix.
   compute-k - keyword, compute id
   Returns nil."
  [compute-k]
  (swap! registrar/render-state
         update ::registrar/warned-computes (fnil disj #{}) compute-k)
  nil)

(defn setup!
  "Compiles, links and introspects a compute shader, registers it
   under its id and rebuilds the dispatch plan. Registering an
   existing id replaces the compute (hot-reload) and deletes the old
   GL program after the new one linked.
   compute-k - keyword, compute id
   spec      - map, the reg-compute spec
   Returns the registered compute map."
  [compute-k spec]
  (let [old     (registrar/get-compute compute-k)
        linked  (-> spec
                    (update :step #(or % :step/frame))
                    (assoc :pipeline [[:compute (:source spec)]])
                    (->> (prog/link-and-introspect! compute-k)))
        compute (assoc linked
                       :local-size     (local-size (:id linked))
                       :max-workgroups (max-workgroup-counts))]
    (when old
      (GL20/glDeleteProgram (:id old)))
    (registrar/register-compute! compute-k compute)
    (rearm-compute! compute-k)
    (registrar/register-computes-by-step!
      (derive-computes-by-step (registrar/computes)))
    compute))

(defn- warn-degraded!
  "Prints a degradation line once per compute, until the compute is
   rearmed (first valid dispatch size, or re-registration).
   compute-k - keyword, compute id
   value     - the invalid dispatch size
   Returns nil."
  [compute-k value]
  (when-not (contains? (get @registrar/render-state
                            ::registrar/warned-computes #{})
                       compute-k)
    (swap! registrar/render-state
           update ::registrar/warned-computes (fnil conj #{}) compute-k)
    (println (str "✖ compute " compute-k " degraded — dispatch size must be"
                  " an integer, [x y] or [x y z], got " (pr-str value)
                  " (fix and reload; reg-compute re-arms)")))
  nil)

(defn- ceil-div
  "Ceiling division of two positive integers."
  [n d]
  (long (Math/ceil (/ (double n) (double d)))))

(defn- workgroup-counts
  "Derives the [x y z] workgroup counts of a dispatch from the
   evaluated dispatch size: :invocations are divided by the
   introspected local size (ceiling, per axis), :workgroups are taken
   verbatim; both are clamped to the GL limits.
   compute - map, registered compute
   value   - integer or vector, the evaluated dispatch size
   Returns [x y z] of longs, or nil when value is invalid."
  [compute value]
  (let [axes (cond
               (nat-int? value) [value 1 1]
               (and (vector? value)
                    (<= 1 (count value) 3)
                    (every? nat-int? value))
               (into value (repeat (- 3 (count value)) 1))
               :else nil)]
    (when axes
      (let [counts (if (:invocations compute)
                     (mapv ceil-div axes (:local-size compute))
                     axes)]
        (mapv min counts (:max-workgroups compute))))))

(def ^:private BARRIER-BITS
  "Memory barrier bits covering everything a v1 compute can write:
   SSBOs are the only compute-writable resource, and every consumer
   (chained computes, draws) reads them as shader storage. Narrowed
   from GL_ALL_BARRIER_BITS, whose full pipeline drains cost real
   frame time at scale."
  GL43/GL_SHADER_STORAGE_BARRIER_BIT)

(defn dispatch!
  "Binds a compute program and dispatches its workgroups, followed by
   a shader-storage memory barrier. Internal seam, redefined by tests.
   program-id - int, GL program id
   counts     - [x y z] workgroup counts
   Returns nil."
  [program-id [x y z]]
  (GL20/glUseProgram program-id)
  (GL43/glDispatchCompute (int x) (int y) (int z))
  (GL42/glMemoryBarrier BARRIER-BITS)
  nil)

(defn- dispatch-compute!
  "Evaluates a compute's dispatch size against db and dispatches it.
   A zero axis skips the dispatch; an invalid dynamic size skips it
   and warns once, rearmed on the first valid result.
   db        - map, application state
   compute-k - keyword, compute id
   Returns nil."
  [db compute-k]
  (let [compute (registrar/get-compute compute-k)
        raw     (or (:invocations compute) (:workgroups compute))
        value   (if (fn? raw) (raw db) raw)
        counts  (workgroup-counts compute value)]
    (if (nil? counts)
      (warn-degraded! compute-k value)
      (do
        (when (contains? (get @registrar/render-state
                              ::registrar/warned-computes #{})
                         compute-k)
          (rearm-compute! compute-k))
        (when (every? pos? counts)
          (dispatch! (:id compute) counts)))))
  nil)

(defn dispatch-for-step!
  "Dispatches every compute registered on step, in :after topological
   order, then rebinds current-program-id — a dispatch binds its own
   program, and the transition's draws must find theirs back.
   db                 - map, application state
   computes-by-step   - map {step [compute-k]}, the registered plan
   step               - keyword, the transition's step
   current-program-id - int, GL program to rebind, or nil
   Returns nil."
  [db computes-by-step step current-program-id]
  (when-let [compute-ks (seq (get computes-by-step step))]
    (doseq [compute-k compute-ks]
      (dispatch-compute! db compute-k))
    (when current-program-id
      (GL20/glUseProgram current-program-id)))
  nil)

(defn delete-computes!
  "Delete the GL program of every registered compute.
   registry - map, the registry value
   Returns nil."
  [registry]
  (doseq [{:keys [id]} (vals (get-in registry [::registrar/opengl-registry :computes]))]
    (GL20/glDeleteProgram id))
  nil)
