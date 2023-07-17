(ns epictetus.core
  (:require [clojure.pprint :refer [pprint]]
            [integrant.core :as ig]
            [clojure.java.io :as io]
            [epictetus.state :as state]
            [epictetus.loop :as game-loop]
            [epictetus.window]
            [epictetus.program]))

(defn play
  ([] (play {} "engine-default.edn"))
  ([scene] (play scene "engine-default.edn"))
  ([scene config-path]
   (let [{window   :glfw/window
          shaders  :gl/shaders
          programs :gl/programs} (-> config-path
                                     io/resource
                                     slurp
                                     ig/read-string
                                     ig/prep
                                     ig/init)]

     (println "Game state")
     (pprint (reset! state/game scene))

     (game-loop/start window))))
