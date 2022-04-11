(ns user
  (:require [integrant.core :as ig]
            [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [clojure.java.io :as io]
            [epictetus.window :as epic-w]))

(defonce config (-> "engine-default.edn"
                   io/resource
                   slurp
                   ig/read-string))

(defn run [] (ig/init config))
