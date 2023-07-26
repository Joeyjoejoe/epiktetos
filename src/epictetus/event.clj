(ns epictetus.event
  (:require [epictetus.interceptors :as interc :refer [->interceptor handle-state!]]))

(def queue (atom []))

;; Kinds :
;; - coeffects are just functions, later executed by do-cofx interceptor
;;   TODO we should directly store them as interceptors (cd inject-cofx)
;; - events are chain of interceptors
(def kind->id->handler (atom {}))

(defn id [event]
  (get event 0))

(defn get-handler
  ([id] (get-handler :event id))
  ([kind id] (get-in @kind->id->handler [kind id])))

;; Probably useless as a (when-let [h get-handler] ...) would suffice
(defn handler?
  ([event] (handler? :event event))
  ([kind event]
   (get-in @kind->id->handler [kind event])))

(defn dispatch [event]
  (swap! queue conj event))

(defn register
  ([id handler]
   (register :event id handler))
  ([kind id handler]
   (swap! kind->id->handler assoc-in [kind id] handler)))

(defn execute
  ([event]
   (execute :event event))
  ([kind event]
   (if-let [interceptors (get-handler kind (id event))]
     (interc/execute event interceptors)
     (println "event not registered" (id event)))))

(defn ->coeffect
  ([id]
   (->interceptor
     :id      :coeffects
     :before  (fn coeffects-before
                [context]
                (if-let [handler (get-handler :coeffect id)]
                  (update context :coeffects handler)
                  (println "No cofx handler registered for" id)))))
  ([id value]
   (->interceptor
     :id     :coeffects
     :before  (fn coeffects-before
                [context]
                (if-let [handler (get-handler :coeffect id)]
                  (update context :coeffects handler value)
                  (println "No cofx handler registered for" id))))))


