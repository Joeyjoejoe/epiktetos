(ns epiktetos.dev
  (:require [clojure.java.io :as io]
            [integrant.core :as ig]
            [integrant.repl :as ig-repl] ;; :refer [clear go halt prep init reset reset-all]]
            [integrant.repl.state :refer [system config]]
            [epiktetos.startup :as startup]))

;; https://github.com/weavejester/integrant-repl
;; Provides worflow function (prep) (init) (go) (reset) (halt)
(if-let [config (io/resource startup/DEFAULT_CONFIG_PATH)]
  (integrant.repl/set-prep! #(ig/prep (-> config slurp ig/read-string)))
  (throw (Exception. (str "Missing config file: " startup/DEFAULT_CONFIG_PATH))))

(defn start
  "Start engine"
  []
  (ig-repl/go)
  (startup/start-engine! system))

(defn resume
  "Resume a paused loop"
  []
  (startup/start-engine! system))

(defn reset
  "Restart engine (drop all states)"
  []
  (ig-repl/halt)
  (start))

(defn stop
  "Stop engine"
  []
  (ig-repl/halt))
