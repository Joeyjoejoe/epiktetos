(ns epictetus.core
  (:require [clojure.pprint :refer [pprint]]
            [integrant.core :as ig]
            [clojure.java.io :as io]
            [epictetus.scene :as scene]
            [epictetus.coeffect :as cofx]
            [epictetus.loop :as game-loop]
            [epictetus.event :as event]
            [epictetus.interceptors :as interc :refer [->interceptor handle-state!]]
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
  ([id handler-fn]
   (reg-event id [] handler-fn))
  ([id coeffects handler-fn]
   (let [handler (->interceptor {:id     :event-fn
                                 :before handler-fn})
         interceptors [handle-state! cofx/inject-scene coeffects handler]
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
           (fn test-cofx [context]
             (pprint context)
             context))


(reg-event
  [:press :btn-left]
  (fn count-click [context]
    (update-in context
               [:coeffects :scene :click/count]
               inc)))

(reg-event
  [:press :btn-right]
  (fn uncount-click [context]
    (update-in context
               [:coeffects :scene :click/count]
               dec)))

(reg-event
  :mouse/position
  (fn [context]
    (let [value (get-in context [:coeffects :event 1])]
      (assoc-in context
                [:coeffects :scene :mouse/position]
                value))))

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
  (fn quit-flag [context]
    (assoc-in context
               [:coeffects :scene :should-quit?]
               true)))
