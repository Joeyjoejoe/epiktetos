(ns epiktetos.startup
  (:require [clojure.java.io :as io]
            [integrant.core :as ig]
            [epiktetos.opengl.shader-program :as prog]
            [nextjournal.beholder :as beholder]
            [epiktetos.registrar :as registrar]
            [epiktetos.texture :as texture]
            [epiktetos.state :as state]
            [epiktetos.event :as event]
            [epiktetos.loop :as game-loop])
  (:import (org.lwjgl.glfw GLFW)
           (org.lwjgl.opengl GL15)))

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

(defn start-engine!
  "Start the engine with a list of user defined
  events to execute immediately"
  ([]
   (start-engine! (init-systems)))
  ([systems]
   (start-engine! systems []))
  ([systems startup-events]

   (reset! state/system systems)

   (doseq [e startup-events]
     (event/dispatch e))

   (game-loop/start systems)))

(defmethod ig/init-key
  :gl/engine
  [_ opts]
  (let [{:keys [hot-reload]} opts]

    (cond-> opts
      hot-reload (assoc :hot-reload {:watcher (apply beholder/watch
                                                     (fn [_]
                                                       (doseq [[id prog] (get-in @registrar/register [::registrar/opengl :programs])]
                                                         (event/dispatch [::event/reg-p [id prog]])))
                                                     hot-reload)
                                     :paths hot-reload}))))

(defmethod ig/halt-key!
  :gl/engine
  [_ system]
  (let [{:keys [hot-reload]} system]
    ;; reset state
    (doseq [[layout programs] @state/rendering]
      (doseq [[program-k entities] programs]
        (for [[entity-id {:keys [buffers ibo]}] entities]
          (do
            (when ibo
              (GL15/glDeleteBuffers ibo))
            (GL15/glDeleteBuffers (map :id buffers))))))

    (when hot-reload
      (beholder/stop (:watcher hot-reload)))

    (reset! registrar/register {})
    (reset! event/kind->id->handler {})
    (reset! event/queue clojure.lang.PersistentQueue/EMPTY)
    (reset! texture/text-cache {})
    (reset! state/rendering {})
    (reset! state/entities {})
    (reset! state/system {})
    (reset! state/db {})))
