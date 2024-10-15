(ns epiktetos.dev
  (:require [portal.api :as p]
            [integrant.core :as ig]
            [clojure.tools.namespace.repl :refer (refresh-all)]
            [epiktetos.core :as epiktet :refer [reg-event reg-fx]]
            [epiktetos.loop :as epiktet-loop]
            [epiktetos.state :as state]
            [epiktetos.event :as event]
            [epiktetos.registrar :as registrar])
  (:import (org.lwjgl.glfw GLFW)))

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
  )

(defn inspect
  "Open portal with engine's current state (ready for inspection)"
  []
    (p/clear)
    (tap> {:register @registrar/register
           :db @state/db
           :rendering @state/rendering
           :entities @state/entities
           :system @state/system
           :events @event/kind->id->handler
           :events/queue @event/queue}))

(reg-fx :hot-reload/display-state
        (fn reload-state [hr?]
          (if hr?
            (inspect)
            (p/clear))))

(reg-fx :quit
        (fn [close?]
          (let [window (state/window)]
            (ig/halt! @state/system))))

(reg-fx :loop/toggle
        (fn [_]
         (swap! state/db update-in [:core/loop :runing?] not)))

(reg-fx :loop/running?
        (fn [run?]
         (swap! state/db update-in [:core/loop :runing?] run?)))

(reg-fx :portal/close
        (fn [_]
          (remove-tap #'p/submit)
          (p/close)))

(reg-event
  [:press :escape]
  (fn quit [_ fx]
        (-> fx
            (assoc :portal/close true)
            (assoc :quit true))))

(reg-event
  [:press :enter]
  (fn loop-play [_ fx]
    (-> fx
      (assoc :loop/toggle true)
      (assoc :hot-reload/display-state true))))

(when (resolve 'p)
  (remove-tap #'p/submit)
  (p/close))


(def p (p/open))
(add-tap #'p/submit)
