(ns epiktetos.startup
  (:require [clojure.java.io :as io]
            [integrant.core :as ig]
            [epiktetos.opengl.shader-program :as prog]
            [nextjournal.beholder :as beholder]
            [epiktetos.registrar :as registrar]
            [epiktetos.texture :as texture]
            [epiktetos.db :as app-db]
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
  ([] (start-engine! (init-systems)))
  ([systems]
    (swap! registrar/registry assoc ::registrar/system-registry systems)
    (game-loop/start systems)))

(defmethod ig/init-key
  :gl/engine
  [_ opts]
  (let [{:keys [hot-reload]} opts]

    (cond-> opts
      hot-reload (assoc :hot-reload {:watcher (apply beholder/watch
                                                     (fn [_]
                                                       (doseq [[id prog] (get-in @registrar/registry [::registrar/opengl-registry :programs])]
                                                         (event/dispatch [::event/reg-p [id prog]])))
                                                     hot-reload)
                                     :paths hot-reload}))))

(defmethod ig/halt-key!
  :gl/engine
  [_ system]
  (let [{:keys [hot-reload]} system
        render-state @registrar/render-state]

    ;; Delete ibos and vbos
    (doseq [[entitiy-id {:keys [ibo-id vbo-ids]}] (::registrar/entities render-state)]
      (println "Clear entity " entitiy-id)
      (when ibo-id
        (println "ibo " ibo-id " deleted...")
        (GL15/glDeleteBuffers ibo-id))
      (doseq [vbo-id vbo-ids]
        (println "vbo " vbo-id " deleted...")
        (GL15/glDeleteBuffers vbo-id)))

    (when hot-reload
      (beholder/stop (:watcher hot-reload)))

    (reset! registrar/registry {})
    (reset! registrar/render-state {})
    (reset! event/queue clojure.lang.PersistentQueue/EMPTY)
    (reset! app-db/db {})))
