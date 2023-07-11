(ns epictetus.event
  (:require [epictetus.interceptors :as interc :refer [->interceptor handle-state!]]))

(def queue (atom []))
(def kind->id->handler (atom {}))

(defn register
  ([id handler-fn]
   (register :event id handler-fn))
  ([kind id handler-fn]

   ;; Events interceptors chain
   (let [pipeline [handle-state!
                   (->interceptor {:id     :event-fn
                                   :before handler-fn})]]

   (swap! kind->id->handler assoc-in [kind id] pipeline))))

(defn dispatch [event]
  (swap! queue conj event))

(defn execute
  ([event]
   (execute :event event))
  ([kind event]
   (if-let [interceptors (get-in @kind->id->handler [kind (get event 0)])]
     (interc/execute event interceptors)
     (println "event not registered" event))))

(register
  :count-click
  (fn [context]
    (update-in context
               [:coeffects :game/state :click/count]
               inc)))

(register
  :uncount-click
  (fn [context]
    (update-in context
               [:coeffects :game/state :click/count]
               dec)))

(register
  :mouse/position
  (fn [context]
    (let [value (get-in context [:coeffects :event 1])]
      (assoc-in context
                [:coeffects :game/state :mouse/position]
                value))))
