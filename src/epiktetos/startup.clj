(ns epiktetos.startup
  (:require [clojure.java.io :as io]
            [integrant.core :as ig]
            [epiktetos.opengl.shader-attribute :as attribute]
            [epiktetos.opengl.shader-program :as prog]
            [epiktetos.compute :as compute]
            [nextjournal.beholder :as beholder]
            [epiktetos.registrar :as registrar]
            [epiktetos.render.entity :as entity]
            [epiktetos.render.step :as render-step]
            [epiktetos.shader-input.buffer :as input-buffer]
            [epiktetos.shader-input.texture :as texture]
            [epiktetos.db :as app-db]
            [epiktetos.error :as error]
            [epiktetos.event :as event]
            [epiktetos.gamepad :as gamepad]
            [epiktetos.window :as window]
            [epiktetos.window.settings :as window-settings]
            [epiktetos.loop :as game-loop])
  (:import (org.lwjgl.opengl GL GLCapabilities)))

(defonce DEFAULT_CONFIG_PATH "epiktetos/default-config.edn")

(defonce engine-run (atom nil))

(defn validate-config-path!
  "Throw when config-path does not resolve to a classpath resource.

  config-path - string, classpath path to an edn config
  Returns nil"
  [config-path]
  (when-not (io/resource config-path)
    (throw (java.io.FileNotFoundException.
             (str "Config file not found: " config-path))))
  nil)

(defn claim-run!
  "Claim the engine run state for handle, by compare-and-set: only
  one start wins while no engine runs.

  handle - the engine handle to install as the run state
  Returns true when the claim succeeds, false otherwise"
  [handle]
  (compare-and-set! engine-run nil handle))

(defn release-run!
  "Release the engine run state, making the next start claimable.
  Called on the loop thread once the engine is fully halted.

  Returns nil"
  []
  (reset! engine-run nil)
  nil)

(defn engine-handle
  "Wrap the promise of the engine outcome into the start handle.

  outcome - promise, delivered with the loop exit value on a clean
  stop, or with the Throwable that stopped the engine
  Returns the handle: deref blocks until the engine stops and
  returns the exit value, or rethrows the delivered Throwable; the
  timeout arity of deref and realized? are supported"
  [outcome]
  (let [unwrap (fn [value]
                 (if (instance? Throwable value)
                   (throw value)
                   value))]
    (reify
      clojure.lang.IDeref
      (deref [_]
        (unwrap (deref outcome)))
      clojure.lang.IBlockingDeref
      (deref [_ timeout-ms timeout-val]
        (let [value (deref outcome timeout-ms ::engine-runs)]
          (if (identical? value ::engine-runs)
            timeout-val
            (unwrap value))))
      clojure.lang.IPending
      (isRealized [_]
        (realized? outcome)))))

(defn halt-incomplete-systems!
  "Halt the partially initialized systems carried by an integrant
  build failure, so a failed start leaves no live system behind.
  A no-op for an error that carries no system.

  throwable - the error that stopped the startup
  Returns nil"
  [throwable]
  (when-let [systems (:system (ex-data throwable))]
    (try
      (ig/halt! systems)
      (catch Throwable halt-error
        (println "[epiktetos] Could not halt the partially initialized systems:"
                 (.getMessage halt-error)))))
  nil)

(defn init-systems
  "Start engine's systems as defined in integrant
  (https://github.com/weavejester/integrant)
  Engines core systems are :glfw/window"
  ([]
   (init-systems DEFAULT_CONFIG_PATH))
  ([config-path]

   (validate-config-path! config-path)

   (-> config-path
       io/resource
       slurp
       ig/read-string
       ig/init)))

(defn install-dev-tooling!
  "Load and install the development tooling when the error pause is
  enabled: :error-pause {:enabled? true} declares development mode,
  the tooling follows it — install-dev! registers the Portal
  inspection pause and the loop-thread evaluation handlers. Dynamic
  require: no static engine to tooling dependency, production never
  loads it. A tooling that cannot load is reported, never fatal.
  Returns nil"
  []
  (when (error/enabled?)
    (try
      (require 'epiktetos.dev)
      ((resolve 'epiktetos.dev/install-dev!))
      (catch Throwable t
        (println "[epiktetos] Could not install the dev tooling:"
                 (.getMessage t)))))
  nil)

(defn start-engine!
  "Start the engine with a list of user defined
  events to execute immediately.

  Blocks until the loop stops, then halts every system on the loop
  thread — the only one holding the GL context — so the engine tears
  its GPU resources down before the window, and its context, are
  destroyed."
  ([] (start-engine! (init-systems)))
  ([systems]
    (swap! registrar/registry assoc ::registrar/system-registry systems)
    (install-dev-tooling!)
    (swap! app-db/db assoc :core/window
           (merge (window/get-state (get-in systems [:glfw/window :id]))
                  (window-settings/current-settings (:glfw/window systems))))
    (when-not (::registrar/steps @registrar/render-state)
      (swap! registrar/render-state merge (render-step/build-render-steps)))
    (try
      (game-loop/start! systems)
      (finally
        (ig/halt! systems)))))

(defmethod ig/init-key
  :gl/engine
  [_ opts]
  (let [{:keys [hot-reload]} opts
        sdl                  (gamepad/initialize-sdl!)]

    (cond-> opts
      sdl        (assoc :sdl sdl)
      hot-reload (assoc :hot-reload {:watcher (apply beholder/watch
                                                     (fn [_]
                                                       (doseq [[id prog] (get-in @registrar/registry [::registrar/opengl-registry :programs])]
                                                         (event/dispatch [::event/reg-p [id prog]]))
                                                       (doseq [[id compute] (get-in @registrar/registry [::registrar/opengl-registry :computes])]
                                                         (event/dispatch [::event/reg-compute [id (select-keys compute compute/SPEC-KEYS)]])))
                                                     hot-reload)
                                     :paths hot-reload}))))

(defn- reset-engine-state!
  "Reset every engine state container to its initial value: registry,
  render state, event queue, application db and pending error pause.
  Returns nil"
  []
  (reset! registrar/registry {})
  (reset! registrar/render-state {})
  (reset! event/queue clojure.lang.PersistentQueue/EMPTY)
  (reset! app-db/db {})
  (error/clear-pause-state!)
  nil)

(defmethod ig/halt-key!
  :gl/engine
  [_ system]
  (let [{:keys [hot-reload sdl]} system
        registry     @registrar/registry
        render-state @registrar/render-state
        ^GLCapabilities no-capabilities nil]

    (when hot-reload
      (beholder/stop (:watcher hot-reload)))

    (gamepad/quit-sdl! sdl)

    (entity/delete-entities-buffers! render-state)
    (input-buffer/delete-block-buffers! registry)
    (texture/delete-textures! registry)
    (prog/delete-programs! registry)
    (compute/delete-computes! registry)
    (attribute/delete-vaos! registry)

    (GL/setCapabilities no-capabilities)
    (reset-engine-state!)))
