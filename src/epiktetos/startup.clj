(ns epiktetos.startup
  (:require [clojure.java.io :as io]
            [integrant.core :as ig]
            [epiktetos.state :as state]
            [epiktetos.event :as event]
            [epiktetos.loop :as game-loop])
  (:import (org.lwjgl.glfw GLFW)))

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
       ig/prep
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
