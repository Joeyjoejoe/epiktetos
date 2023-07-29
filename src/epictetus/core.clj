(ns epictetus.core
  (:require [clojure.pprint :refer [pprint]]
            [integrant.core :as ig]
            [clojure.java.io :as io]
            [epictetus.scene :as scene]
            [epictetus.coeffect :as cofx]
            [epictetus.effect :as fx]
            [epictetus.loop :as game-loop]
            [epictetus.event :as event]
            [epictetus.interceptors :as interc :refer [->interceptor]]
            [epictetus.window]
            [epictetus.program]))

(defn play
  ([] (play {} "engine-default.edn"))
  ([state] (play state "engine-default.edn"))
  ([state config-path]
   (let [{window   :glfw/window
          shaders  :gl/shaders
          programs :gl/programs} (-> config-path
                                     io/resource
                                     slurp
                                     ig/read-string
                                     ig/prep
                                     ig/init)]

     (println "Game state")
     (pprint (reset! scene/state state))

     (game-loop/start window))))

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
                              (let [{:keys [event scene] :as cofx} (:coeffects context)
                                    fx     {:scene scene}]

                                (->> (handler-fn cofx fx)
                                     (assoc context :effects))))})
         interceptors [fx/do-fx cofx/inject-scene coeffects handler]
         chain        (->> interceptors flatten (remove nil?))]
     (event/register :event id chain))))



;;------------------------------------------------------;;
;; Usage examples

;; (reg-cofx :get-user
;;           (fn [cofx params]
;;             (assoc ctx :user (get-from-db params)))
;;

(reg-event [:press :a]
           [(cofx/inject :doh!)]
           (fn test-cofx [cofx fx]
             (pprint cofx)
             fx))


(reg-event
  [:press :btn-left]
  (fn count-click [cofx fx]
    (update-in fx [:scene :click/count] inc)))

(reg-event
  [:press :btn-right]
  (fn uncount-click [cofx fx]
    (update-in fx [:scene :click/count] dec)))

(reg-event
  :mouse/position
  (fn [{[_ position] :event} fx]
    (assoc-in fx [:scene :mouse/position] position)))

;; To set the close flag, we use glfwSetWindowShouldClose.
;; But we want to avoid any state mutation inside event handlers
;; How can we delay this mutation at the end of interceptor chain ?
;; Do we really need to use that glfw function, or can we use our
;; own flags ?
;; Do user will need to directly call opengl ond glfw function, and
;; how can we handle these probably mutating operation at the end
;; of interceptor chain ?
(reg-event
  [:press :escape]
  (fn quit-flag [cofx fx]
    (assoc-in fx [:scene :should-quit?] true)))
