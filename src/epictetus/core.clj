(ns epictetus.core
  (:require [integrant.core :as ig]
            [clojure.java.io :as io]
            [epictetus.state :as state]
            [epictetus.coeffect :as cofx]
            [epictetus.effect :as fx]
            [epictetus.loop :as game-loop]
            [epictetus.event :as event]
            [epictetus.interceptors :as interc :refer [->interceptor]]
            [epictetus.window]
            [epictetus.program])
  (:import (org.lwjgl.glfw GLFW)))

(def system state/system)
(def db state/db)

(defn start
  ([]
   (start "engine-default.edn"))

  ([conf-path]
   (let [system (-> conf-path
                    io/resource
                    slurp
                    ig/read-string
                    ig/prep
                    ig/init)]
     (start conf-path system)))

  ([_ sys]
     (reset! system sys)
     (event/dispatch [:loop/start])
     (game-loop/start @system)
     (event/dispatch [:loop/end])))

(defn reg-event
  "Set the handler to an event id, with the option to add additional coeffects.

  Handler are pure functions that takes two arguments:
  - a map of coeffects containing input data for the handler function.
  - a map of effects that the handler function must return (modified or not).

  Coeffects and effects can be registered with reg-cofx and reg-fx functions"
  ([id handler-fn]
   (reg-event id [] handler-fn))
  ([id coeffects handler-fn]
   (let [handler (->interceptor
                   {:id     :event-fn
                    :before (fn handler [context]
                              (let [{:keys [_ db] :as coeffects} (:coeffects context)
                                    effect     {:db db}]

                                (->> (handler-fn coeffects effect)
                                     (assoc context :effects))))})
         interceptors [fx/do-fx cofx/inject-db cofx/inject-system coeffects handler]
         chain        (->> interceptors flatten (remove nil?))]
     (event/register :event id chain))))


;; CORE events
(reg-event
  :mouse/position
  (fn [{[_ position] :event} fx]
    (assoc-in fx [:db :mouse/position] position)))


(reg-event
  [:press :escape]
  (fn quit-flag [cofx fx]
    (GLFW/glfwSetWindowShouldClose (:glfw/window @system) true)))


