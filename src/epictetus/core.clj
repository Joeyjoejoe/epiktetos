(ns epictetus.core
  (:require [integrant.core :as ig]
            [clojure.java.io :as io]
            [epictetus.state :as state]
            [epictetus.loop :as game-loop]
            [epictetus.window :as _]
            [epictetus.program :as __]))

(defn start
  ([] (start "engine-default.edn" {}))
  ([config-path game-state]
   (let [{:keys [window
                 shaders
                 programs]} (-> config-path
                                io/resource
                                slurp
                                ig/read-string
                                ig/init)
         engine-state {:window/id   window
                       ;; :window/time nil
                       ;; :mouse/position [0.0 0.0 0.0]
                       :shader/program programs
                       :shader/source  shaders
                       :loop/running?  true}]

     (println (str "  Engine state : " (reset! state/-engine engine-state)))
     (println (str "  Game state : "   (reset! state/-game game-state)))

     (game-loop/run window))))
