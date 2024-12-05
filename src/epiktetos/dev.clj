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
  (add-tap #'p/submit))

(defn- close-inspector
  []
  (p/clear)
  (remove-tap #'p/submit)
  (p/close))

(defn start
  "Start engine"
  ([]
   (if-not (empty? @state/system)
     (do (ig/halt! (dissoc @state/system :glfw/window))
         (refresh-all :after (symbol "epiktetos.dev" "start")))
     (epiktet/run [])))
  ([config-path]
   (start config-path []))
  ([config-path events]
   (epiktet/run config-path events)))

(reg-fx :loop/pause-toggle
        (fn [db]
          (let [paused (get-in @state/db [:core/loop :paused?])]

            (if-not paused
              (open-inspector)
              (close-inspector))

            (swap! state/db update-in [:core/loop :paused?] not)

            (when-not paused
              (portal-load-state)))))

(reg-fx :engine/stop
        (fn [_]
          (let [window (state/window)]
            (GLFW/glfwSetWindowShouldClose window true))))

(reg-event
  [:press :escape]
  (fn loop-stop [_ fx]
    (assoc fx :engine/stop true)))

(reg-event
  [:press :enter]
  (fn loop-play [_ fx]
    (assoc fx :loop/pause-toggle true)))
