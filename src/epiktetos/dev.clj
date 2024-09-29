(ns epiktetos.dev
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [integrant.core :as ig]
            [integrant.repl :as ig-repl] ;; :refer [clear go halt prep init reset reset-all]]
            [integrant.repl.state :as ig-state]
            [epiktetos.startup :as startup]))

;; https://github.com/weavejester/integrant-repl
;; Provides worflow function (prep) (init) (go) (reset) (halt)

(defn system []
  (pp/pprint ig-state/system))

(defn config []
  (pp/pprint ig-state/config))

(defn set-config-path
  "Initialize engine systems"
  ([]
   (set-config-path startup/DEFAULT_CONFIG_PATH))
  ([path]
   (if-let [config (io/resource path)]
     (integrant.repl/set-prep! #(ig/prep (-> config slurp ig/read-string)))
     (throw (Exception. (str "Missing config file: " path))))))

(defn start
  "Start engine"
  ([]
   (start []))
  ([events]
   (ig-repl/go)
   (startup/start-engine! ig-state/system events)))

(defn resume
  "Resume a paused loop"
  []
  (startup/start-engine! ig-state/system))

(defn reset
  "Restart engine (drop all states)"
  []
  (ig-repl/halt)
  (start))

(defn stop
  "Stop engine"
  []
  (ig-repl/halt))

;; Initialize default startup config
(set-config-path)

;; Call set-config-path again to overwrite
;; the defaults in your development namespace :
;;    (set-config-path "custom-config.edn")

