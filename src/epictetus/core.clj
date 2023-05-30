(ns epictetus.core
  (:require [clojure.pprint :refer [pprint]]
            [integrant.core :as ig]
            [clojure.java.io :as io]
            [epictetus.state :as state]
            [epictetus.loop :as game-loop]
            [epictetus.window]
            [epictetus.program]))

(defn run
  ([] (run "engine-default.edn" {}))
  ([config-path game-state]
   (let [{window   :glfw/window
          shaders  :gl/shaders
          programs :gl/programs} (-> config-path
                                io/resource
                                slurp
                                ig/read-string
                                ig/prep
                                ig/init)
         engine-state {:window/id      window
                       :window/time    0
                       :mouse/position [0.0 0.0]
                       :shader/program programs
                       :shader/source  shaders
                       :loop/running?  true}]

     (println "Engine state")
     (pprint (reset! state/-engine engine-state))

     (println "Game state")
     (pprint (reset! state/-game game-state))

     (game-loop/start window))))
