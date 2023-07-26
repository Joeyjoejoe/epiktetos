(ns epictetus.core
  (:require [clojure.pprint :refer [pprint]]
            [integrant.core :as ig]
            [clojure.java.io :as io]
            [epictetus.scene :as scene]
            [epictetus.loop :as game-loop]
            [epictetus.window]
            [epictetus.program]))

(defn play
  ([] (play {} "engine-default.edn"))
  ([state] (play state "engine-default.edn"))
  ([state config-path]
   (let [{window   :glfw/window
          shaders  :gl/shaders
          programs :gl/programs} (-> config-path
                                     io/resource
                                     slurp
                                     ig/read-string
                                     ig/prep
                                     ig/init)]

     (println "Game state")
     (pprint (reset! scene/state state))

     (game-loop/start window))))
