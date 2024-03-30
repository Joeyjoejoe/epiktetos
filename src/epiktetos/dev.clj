(ns epiktetos.dev
  (:require [integrant.core :as ig]
            [integrant.repl :as ig-repl] ;; :refer [clear go halt prep init reset reset-all]]
            [integrant.repl.state :refer [system config]]
            [clojure.java.io :as io]
            [epiktetos.core :as core]))

;; https://github.com/weavejester/integrant-repl
;; Provides worflow function (prep) (init) (go) (reset) (halt)

(if-let [config (io/resource "engine.edn")]
  (integrant.repl/set-prep! #(ig/prep (-> config
                                          slurp
                                          ig/read-string)))
  (throw (Exception. "Missing config file: engine.edn")))

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
  (start))

(defn stop
  "Stop engine"
  []
  (ig-repl/halt))
