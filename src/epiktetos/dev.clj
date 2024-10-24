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

(def inspector nil)

(defn- portal-load-state
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

(defn- open-inspector
  []
  (alter-var-root #'inspector (constantly (p/open {:window-title "Inspector"})))
  (add-tap #'p/submit)
  (portal-load-state))

(defn- close-inspector
  []
  (p/clear)
  (remove-tap #'p/submit)
  (p/close))

(defn start
  "Start engine"
  ([]
   (open-inspector)
   (epiktet/run []))
  ([config-path]
   (start config-path []))
  ([config-path events]
   (open-inspector)
   (epiktet/run config-path events)))

(defn resume
  "Resume a paused loop"
  []
  (epiktet-loop/start @state/system))

(defn stop
  "Stop engine"
  []
  (close-inspector)
  (ig/halt! @state/system)
  (refresh-all))

(reg-fx :loop/toggle
        (fn [db]
          (portal-load-state)
          (swap! state/db update-in [:core/loop :runing?] not)))

(reg-fx :loop/stop
        (fn [_]
          ;; TODO Pass window as a parameter of the fx
          (portal-load-state)
          (let [window (state/window)]
            (GLFW/glfwSetWindowShouldClose window true))))

(reg-event
  [:press :escape]
  (fn loop-stop [_ fx]
    (assoc fx :loop/stop true)))

(reg-event
  [:press :enter]
  (fn loop-play [_ fx]
    (assoc fx :loop/toggle true)))
