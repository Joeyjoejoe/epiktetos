(ns epictetus.coeffect
  (:require [epictetus.event :as event]
            [epictetus.scene :as scene]
            [epictetus.interceptors :refer [->interceptor]]))

(defn reg-cofx
  "A cofx is a function that takes the coeffects map and
   an optional parameter, and return a modified version
   of the coeffects map"
  [id cofx-fn]
  (event/register :coeffect id cofx-fn))

(defn inject
  ([id]
   (->interceptor
     :id      :coeffects
     :before  (fn coeffects-before
                [context]
                (if-let [handler (event/get-handler :coeffect id)]
                  (update context :coeffects handler)
                  (println "No cofx handler registered for" id)))))
  ([id value]
   (->interceptor
     :id     :coeffects
     :before  (fn coeffects-before
                [context]
                (if-let [handler (event/get-handler :coeffect id)]
                  (update context :coeffects handler value)
                  (println "No cofx handler registered for" id))))))

;; Core coeffects

(reg-cofx :inject-scene
          (fn [coeffects]
            (assoc coeffects :scene @scene/state)))

(def inject-scene (inject :inject-scene))

