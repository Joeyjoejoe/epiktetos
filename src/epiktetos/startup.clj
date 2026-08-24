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
            [epiktetos.window :as window]
            [epiktetos.loop :as game-loop])
  (:import (org.lwjgl.opengl GL GLCapabilities)))

(defonce DEFAULT_CONFIG_PATH "epiktetos/default-config.edn")

(defn init-systems
  "Start engine's systems as defined in integrant
  (https://github.com/weavejester/integrant)
  Engines core systems are :glfw/window"
  ([]
   (init-systems DEFAULT_CONFIG_PATH))
  ([config-path]

   (when-not (io/resource config-path)
     (throw (java.io.FileNotFoundException.
              (str "Config file not found: " config-path))))

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
           (window/get-state (get-in systems [:glfw/window :id])))
    (when-not (::registrar/steps @registrar/render-state)
      (swap! registrar/render-state merge (render-step/build-render-steps)))
    (try
      (game-loop/start systems)
      (finally
        (ig/halt! systems)))))

(defmethod ig/init-key
  :gl/engine
  [_ opts]
  (let [{:keys [hot-reload]} opts]

    (cond-> opts
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
  (let [{:keys [hot-reload]} system
        registry     @registrar/registry
        render-state @registrar/render-state
        ^GLCapabilities no-capabilities nil]

    (when hot-reload
      (beholder/stop (:watcher hot-reload)))

    (entity/delete-entities-buffers! render-state)
    (input-buffer/delete-block-buffers! registry)
    (texture/delete-textures! registry)
    (prog/delete-programs! registry)
    (compute/delete-computes! registry)
    (attribute/delete-vaos! registry)

    (GL/setCapabilities no-capabilities)
    (reset-engine-state!)))
