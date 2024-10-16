(ns epiktetos.dev
  (:require [portal.api :as p]
            [integrant.core :as ig]
            [clojure.tools.namespace.repl :refer [refresh-all]]
            [epiktetos.core :as epiktet :refer [reg-event reg-fx]]
            [epiktetos.loop :as epiktet-loop]
            [epiktetos.state :as state]
            [epiktetos.event :as event]
            [epiktetos.registrar :as registrar])
  (:import (org.lwjgl.glfw GLFW)))

(defn portal-reload-state
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
  (remove-tap #'p/submit)
  (p/close)
  (ig/halt! @state/system)
  (refresh-all)
  (portal-reload-state))


(reg-fx :hot-reload/reload-state
        (fn reload-state [_]
          (portal-reload-state)))

(reg-fx :loop/toggle
        (fn [_]
         (swap! state/db update-in [:core/loop :runing?] not)))

(reg-fx :loop/stop
        (fn [_]
          ;; TODO Pass window as a parameter of the fx
         (let [window (state/window)]
           (GLFW/glfwSetWindowShouldClose window true))))

(reg-event
  [:press :escape]
  (fn quit [_ fx]
    (assoc fx :loop/stop true)))

(reg-event
  [:press :enter]
  (fn loop-play [_ fx]
    (-> fx
      (assoc :loop/toggle true)
      (assoc :hot-reload/reload-state true))))

(when (resolve 'p)
  (remove-tap #'p/submit)
  (p/close))


(def p (p/open))
(add-tap #'p/submit)
