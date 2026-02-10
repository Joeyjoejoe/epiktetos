(ns epiktetos.render.step
  "Render steps optimize the performance of the rendering process.

   They serves two purpose:
   - Minimize costly OpenGL state switches (shader programs, vao, textures etc...).
   - Control shader inputs handler exectution.

   ### Sort keys

   Each entity in the `render-queue` have a `sort-key`. A `sort-key` is a 64 bits java
   long composed of parts, each matching a specific `render-step` and occupying a specific
   number of bits in the `sort-key` :

   [vao-step(6 bits), program-step(10 bits), custom-step1(x bits), custom-step2 (y bits) ...]

   Each parts value is the result of applying the step handler function to the entity. The output
   is then replace by an id from the step pool to ensure grouping of similar entities accross the
   render queue.

   ### Render steps

   When an entity is rendered, it will pass through each defined render-steps. Core `render-step`
   corresponds to costly OpenGL API calls, like the :step/program which happen each time we need
   to change the currently bound shader program. With this `render-step`, we ensure entities are
   sorted by shader program and that we will only switch programs once.

   Users can define their own steps depending on their need with the 64 bits limitation of the `sort-key`

   ## Control shader inputs handler exectution
   TODO
  "
  (:require [epiktetos.registrar :as registrar]))


(defn- create-index-pool
  "Creates a dense index pool with indices in range [0, 2^n-bits):
   n-bits - int, number of bits defining the pool capacity"
  [n-bits]
  {:max       (bit-shift-left 1 n-bits)
   :next-idx  0
   :free      #{}
   :allocated {}})

(defn- acquire
  "Acquires a dense index for the given value. Returns the same index
   if value is already allocated.
   pool  - index pool map
   value - the value to associate with an index"
  [pool value]
  (if-let [idx (get-in pool [:allocated value])]
    [pool idx]
    (if-let [idx (first (:free pool))]
      [(-> pool
           (update :free disj idx)
           (assoc-in [:allocated value] idx))
       idx]
      (let [{:keys [next-idx max]} pool]
        (if (< next-idx max)
          [(-> pool
               (update :next-idx inc)
               (assoc-in [:allocated value] next-idx))
           next-idx]
          (throw (ex-info "Index pool exhausted"
                          {:max   max
                           :value value})))))))

(defn- release
  "Releases the index associated with value, recycling it for reuse.
   pool  - index pool map
   value - the value whose index should be freed"
  [pool value]
  (if-let [idx (get-in pool [:allocated value])]
    (-> pool
        (update :free conj idx)
        (update :allocated dissoc value))
    pool))


(defn- step-sk-value
  "Compute sort key value for the given step and entity"
  [step entity]
  (let [{:keys [handler pool]} step
        [updated-pool sort-id] (acquire pool (handler entity))]
    (vector (assoc step :pool updated-pool)
            sort-id)))

(defn- steps-sk-values
  [steps entity]
  (let [results (mapv #(step-sk-value % entity) steps)
        updated-steps (mapv first results)
        sk-values (mapv second results)]
    [updated-steps sk-values]))

(defonce VAO-STEP
  [:step/vao
   (fn [entity]
     (-> entity
         :program
         registrar/get-program
         :p/vao-id))
   6])

(defonce PROGRAM-STEP
  [:step/program
   (fn [entity]
     (get entity :program))
   10])

(defn- parse-step
  "Parse a step vector"
  [s-vec]
  (let [[name handler size] s-vec
        bit-size (or size 8)]
    {:name    name
     :size    bit-size
     :handler handler
     :pool    (create-index-pool bit-size)}))

(defn- assign-shifts
  "Compute and assign bit-shift of each step"
  [steps]
  (let [sizes (map :size steps)
        total (reduce + sizes)]

    (when (> total 64)
      (throw (ex-info "Render steps exceed 64-bit sort-key budget"
                      {:total-bits total
                       :steps (mapv #(select-keys % [:name :size]) steps)})))

    (->> sizes
         (reductions +)
         (map #(- 64 %))
         (mapv #(assoc %1 :shift %2) steps))))

(defn- assign-masks
  "Compute and assign bit-mask of each step"
  [steps]
  (mapv (fn [{:keys [size shift] :as step}]
          (assoc step :mask (bit-shift-left (dec (bit-shift-left 1 size))
                                            shift)))
        steps))

(defn- encode-step-value
  [step value]
  (bit-shift-left (long value) (long (:shift step))))

(defn- encode-sort-key
  "Encodes step values into a 64-bit sort-key"
  [steps sk-values]
  (->> (map encode-step-value steps sk-values)
       (reduce bit-or 0)))

(defn sort-key
  "Compute the sort key of entity.
  Returns a tuple of the updated render register and entity"
  ([entity]
   (let [render-register (::registrar/render @registrar/register)]
     (sort-key render-register entity)))
  ([render-register entity]
   (let [steps-order (get render-register :steps-order)
         steps       (keep #(get-in render-register [:step %]) steps-order)
         [updated-steps sk-values] (steps-sk-values steps entity)
         sk           (encode-sort-key updated-steps sk-values)
         updated-step (into {} (map (juxt :name identity) updated-steps))]

     (vector (assoc render-register :step updated-step)
             (assoc entity :sort-key sk)))))


(defn build-render-steps
  "Build the render steps map"
  [& custom-steps]
  (let [steps (->> (concat [VAO-STEP PROGRAM-STEP] custom-steps)
                   (mapv parse-step)
                   (assign-shifts)
                   (assign-masks)
                   (into {} (map (juxt :name identity))))

        steps-order (concat [:step/frame :step/vao :step/program]
                            (map first custom-steps)
                            [:step/entity])]

    (hash-map :step steps :steps-order steps-order)))

;; TODO Should be a fx instead
(defn save-render-steps!
  "Update render steps and trigger sort-key recomputes"
  ([rs-map]
   (save-render-steps! registrar/register rs-map))
  ([register rs-map]
   ;; TODO Recompute all entities sort-keys
   (swap! register update ::registrar/render merge rs-map)))

(defn step-changed?
  "Returns true if the step value differs between two sort-keys"
  [step curr-k prev-k]
  (let [{:keys [mask]} step]
    (not= (bit-and curr-k mask)
          (bit-and prev-k mask))))

(comment

  (do

    (save-render-steps!
      registrar/register
      (build-render-steps
        [:per-material
         (fn [entity]
           (:material entity))]))

  (def entity
    {:program  :some-program
     :position [0.0 0.0 0.24]
     :material "wood"
     :assets   {:vertices [{:coordinates [-0.5 -0.5 0.1] :color [1.0 1.0 0.0] :texture [0.0 0.0]}
                           {:coordinates [ 0.5 -0.5 0.1] :color [0.05 0.0 0.0] :texture [0.0 0.0]}
                           {:coordinates [ 0.0  0.5 0.1] :color [1.0 0.0 0.80] :texture [0.0 0.0]}]}})

  (def entity2
    {:program  :some-program
     :position [0.0 0.0 0.4]
     :assets   {:vertices [{:coordinates [-0.5 -0.5 0.1] :color [1.0 1.0 0.0] :texture [0.0 0.0]}
                           {:coordinates [ 0.5 -0.5 0.1] :color [0.05 0.0 0.0] :texture [0.0 0.0]}
                           {:coordinates [ 0.0  0.5 0.1] :color [1.0 0.0 0.80] :texture [0.0 0.0]}]}})

  (def entity3
    {:program  :some-program2
     :position [0.0 0.0 0.1]
     :material "wood"
     :assets   {:vertices [{:coordinates [-0.5 -0.5 0.1] :color [1.0 1.0 0.0] :texture [0.0 0.0]}
                           {:coordinates [ 0.5 -0.5 0.1] :color [0.05 0.0 0.0] :texture [0.0 0.0]}
                           {:coordinates [ 0.0  0.5 0.1] :color [1.0 0.0 0.80] :texture [0.0 0.0]}]}})

  (-> (sort-key entity) ;; 0
      (doto prn)
      first
      (sort-key entity2) ;; 1099511627776
      (doto prn)
      first
      (sort-key entity3) ;; 281474976710656
      last
      :sort-key))

  )
