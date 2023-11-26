(ns user
  (:require [integrant.core :as ig]
            [integrant.repl :as ig-repl] ;; :refer [clear go halt prep init reset reset-all]]
            [integrant.repl.state :refer [system config]]
            [puget.printer :as puget]
            [clojure.java.io :as io]
            [epictetus.core :as core]))

;; https://github.com/weavejester/integrant-repl
;; Provides worflow function (prep) (init) (go) (reset) (halt)
(integrant.repl/set-prep! #(ig/prep (-> "engine-default.edn"
                                        io/resource
                                        slurp
                                        ig/read-string)))

(defn start
  "Start engine"
  []
  (ig-repl/go)
  (core/start nil system))

(defn resume
  "Resume a paused loop"
  []
  (core/start nil system))

(defn reset
  "Restart engine (drop all states)"
  []
  (ig-repl/halt)
  (go))

(defn stop
  "Stop engine"
  []
  (ig-repl/halt))
