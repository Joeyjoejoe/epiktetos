(ns epictetus.event
  (:require [epictetus.interceptors :as interc :refer [->interceptor load-state save-state!]]))

(def queue (atom []))
(def kind->id->handlers (atom {}))

(defn register
  ([id handler-fn]
   (register :event id handler-fn))
  ([kind id handler-fn]

   ;; Events interceptors chain
   (let [pipeline [save-state!
                   load-state
                   (->interceptor {:id     :event-fn
                                   :before handler-fn})]]

   (swap! kind->id->handlers assoc-in [kind id] pipeline))))

(defn dispatch [event]
  (swap! queue conj event))

(defn execute
  ([event]
   (execute :event event))
  ([kind event]
   (if-let [interceptors (get-in @kind->id->handlers [kind (get event 0)])]
     (interc/execute event interceptors))))

(register
  :mouse/left-click
  (fn [context]
    (update-in context
               [:coeffects :game/state :click/count]
               inc)))

(register
  :mouse/position
  (fn [context]
    (let [value (get-in context [:coeffects :event 1])]
      (assoc-in context
                [:coeffects :engine/state :mouse/position]
                value))))
