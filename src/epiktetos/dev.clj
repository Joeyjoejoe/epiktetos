(ns epiktetos.dev
  (:require [portal.api :as p]
            [integrant.core :as ig]
            [clojure.tools.namespace.repl :refer (refresh-all)]
            [epiktetos.core :as epiktet]
            [epiktetos.loop :as epiktet-loop]
            [epiktetos.state :as state]
            [epiktetos.event :as event]
            [epiktetos.registrar :as registrar]))

(defn start
  "Start engine"
  ([]
   (epiktet/run []))
  ([config-path]
   (start config-path []))
  ([config-path events]
   (epiktet/run config-path events)))

(defn resume
  "Resume a paused loop"
  []
  (epiktet-loop/start @state/system))

(defn stop
  "Stop engine"
  []
  (ig/halt! @state/system)
  (refresh-all))

(defn inspect
  "Open portal with engine's current state (ready for inspection)"
  []
  (let [p (p/open)]
    (add-tap #'p/submit)
    (tap> {:register @registrar/register
           :db @state/db
           :rendering @state/rendering
           :entities @state/entities
           :system @state/system
           :events @event/kind->id->handler
           :events/queue @event/queue})
    p))
