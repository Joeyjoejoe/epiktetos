(ns epiktetos.core
  (:require [epiktetos.db :as app-db]
            [epiktetos.registrar :as registrar]
            [epiktetos.coeffect :as cofx]
            [epiktetos.effect :as fx]
            [epiktetos.error :as error]
            [epiktetos.startup :as startup]
            [epiktetos.event :as event]
            [epiktetos.render.entity :as entity]
            [epiktetos.render.step :as render-step]
            [epiktetos.opengl.shader-program :as prog]
            [epiktetos.shader-input.registration :as shader-input]
            [epiktetos.shader-input.texture :as texture]
            [epiktetos.interceptors :as interc :refer [->interceptor]]
            [epiktetos.window]))

(def db app-db/db)

(defn reg-cofx
  "A cofx is a function that takes a coeffects map and
  an optional parameter, and return a modified version
  of the coeffects map"
  [id cofx-fn]
  (cofx/register id cofx-fn))

(defn inject-cofx
  "Add a cofx to an event registration"
  ([id]
   (cofx/inject id))
  ([id value]
   (cofx/inject id value)))

(defn reg-event
  "Set the handler to an event id, with the option to add additional coeffects.

  Handler are pure functions that takes two arguments:
  - a map of coeffects containing input data for the handler function.
  - a map of effects that the handler function must return (modified or not).

  Coeffects and effects can be registered with reg-cofx and reg-fx functions"
  ([id handler-fn]
   (reg-event id [] handler-fn))
  ([id coeffects handler-fn]
   (let [handler (->interceptor
                   {:id     :event-fn
                    :before (fn handler [context]
                              (let [cofx (:coeffects context)
                                    fx   {}]
                                (->> (handler-fn cofx fx)
                                     (assoc context :effects))))})
         interceptors [fx/do-fx
                       (inject-cofx :inject-db)
                       (inject-cofx :inject-system)
                       coeffects
                       handler]
         chain        (->> interceptors flatten (remove nil?))]
     (event/register :events id chain))))

(defn reg-fx
  "An effect, aka fx, is a function that takes a coeffects map and
  an optional parameter, and return a modified version
  of the coeffects map"
  [id fx-fn]
  (fx/register id fx-fn))

(defn dispatch
  "Dispatch an event"
  ([event-k value]
  (event/dispatch [event-k value]))
  ([fx event-k value]
   (update fx ::fx/dispatch conj [event-k value])))

(defn render
  ([id render-params]
  (dispatch ::entity/render [id render-params]))
  ([fx id render-params]
   (update fx ::fx/render conj [id render-params])))

(defn delete
  ([id]
  (dispatch ::entity/delete id))
  ([fx id]
   (update fx ::fx/delete conj id)))

(defn reg-p
  ([id prog-map]
   (dispatch ::event/reg-p [id prog-map]))
  ([fx id prog-map]
   (update fx ::fx/reg-p conj [id prog-map])))

(defn reg-input
  ([varname handler]
   (reg-input varname handler {}))
  ([varname handler options]
   (dispatch ::event/reg-input [varname handler options]))
  ([fx varname handler options]
   (update fx ::fx/reg-input conj [varname handler options])))

(defn reg-texture
  ([id spec]
   (dispatch ::event/reg-texture [id spec]))
  ([fx id spec]
   (update fx ::fx/reg-texture conj [id spec])))


(defn reg-steps!
  [& steps]
  (dispatch ::render-step/register steps))

(defn stop!
  "Stop the running engine: the loop exits at the end of its iteration
  and the halt releases every system on the loop thread. During an
  error pause, delivers the abort decision to the paused loop.
  Callable from any thread; a no-op when no engine runs.
  Returns nil"
  []
  (error/stop!))


(defn install-core!
  "Register the engine's own events, coeffects and effects.

  Called by run before the loop consumes anything, so a restarted
  engine — the halt empties the registry — always runs on its complete
  core state, whatever the previous session left behind. Dispatch
  queues events without inspecting the registry, so user declarations
  made at load time wait in the queue until this has run.

  Returns nil"
  []
  (reg-event ::event/reg-p
             (fn [cofx fx]
               (let [[id prog] (get-in cofx [:event 1])]
                 (reg-p fx id prog))))

  (reg-event ::event/reg-input
             (fn [cofx fx]
               (let [[varname handler options] (get-in cofx [:event 1])]
                 (reg-input fx varname handler options))))

  (reg-event ::event/reg-texture
             (fn [cofx fx]
               (let [[id spec] (get-in cofx [:event 1])]
                 (reg-texture fx id spec))))

  (reg-event ::entity/render
             (fn [cofx fx]
               (let [[id render-params] (get-in cofx [:event 1])]
                 (render fx id render-params))))

  (reg-event ::entity/delete
             (fn [cofx fx]
               (let [id (get-in cofx [:event 1])]
                 (delete fx id))))

  (reg-cofx :inject-system
            (fn [coeffects]
              (assoc coeffects :system (::registrar/system-registry @registrar/registry))))

  (reg-cofx :inject-db
            (fn [coeffects]
              (assoc coeffects :db @app-db/db)))

  (reg-fx ::fx/dispatch
          (fn dispatch-event!
            [event-coll]
              (doseq [e event-coll]
                (event/dispatch e))))

  (reg-fx ::fx/reg-p
          (fn [prog-coll]
            (doseq [[id prog-map] prog-coll]
              (prog/setup! id prog-map))))

  (reg-fx ::fx/reg-input
          (fn [input-coll]
            (doseq [[varname handler options] input-coll]
              (shader-input/register-input-handler! varname handler options))))

  (reg-fx ::fx/reg-texture
          (fn [texture-coll]
            (doseq [[id spec] texture-coll]
              (texture/register-texture! id spec))))

  (reg-fx :db
          (fn update-db! [new-db]
            (reset! app-db/db new-db)))

  (reg-fx ::fx/render
          (fn render-entity! [entity-coll]
            (doseq [[id render-params] entity-coll]
              (entity/add-entity! id render-params))))

  (reg-fx ::fx/delete
          (fn delete-entity! [entity-ids]
            (doseq [id entity-ids]
              (entity/delete-entity! id))))

  (reg-event ::render-step/register
             (fn [cofx fx]
               (let [step-coll (get-in cofx [:event 1])]
                 (assoc fx ::fx/reg-steps step-coll))))

  ;; NOTE This will invalidate every single sort-key in
  ;; the render-state when custom-step-coll is different
  ;; from previous call.
  ;; This won't happen in production, but in development,
  ;; ns reloads are frequent. Most of the time custom-step-coll
  ;; will remains the same.
  ;; TODO For the rare occasion when an actuel step is added
  ;; or modified, we should recompute all sort-keys. To detect
  ;; such event, we could hash steps vector and compare them, which
  ;; induce the need to prevent anonymous functions in step vector,
  ;; for the hash algorithm and comparison to be effective.
  (reg-fx ::fx/reg-steps
          (fn register-cutom-steps! [custom-step-coll]
            (->> custom-step-coll
                 (apply render-step/build-render-steps)
                 (swap! registrar/render-state merge))))
  nil)

(defn start
  "Start the engine and block until it stops: install the engine core
  state, initialize the systems from the edn configuration, then enter
  the loop. When the error pause is enabled, the development tooling
  is installed with the systems (startup/install-dev-tooling!).

  Refuses to start while an engine is already running — stop it with
  stop! first: halting from the REPL thread would issue GL calls
  outside the thread holding the context.

  config-path - string, classpath path to an edn config (optional)
  Returns the loop exit value, or nil when an engine already runs"
  ([]
   (start startup/DEFAULT_CONFIG_PATH))
  ([config-path]
   (if (seq (::registrar/system-registry @registrar/registry))
     (println "[epiktetos] Engine already running - stop it with (epiktetos.core/stop!) before restarting.")
     (do (install-core!)
         (-> config-path
             startup/init-systems
             (assoc-in [:gl/engine :config-path] config-path)
             startup/start-engine!)))))
