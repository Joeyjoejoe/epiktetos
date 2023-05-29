(ns epictetus.event
  (:require [epictetus.interceptors :as i]))

(def queue (atom []))
(def kind->id->handlers (atom {}))

(defn register
  ([id handler-fn]
   (register :event id handler-fn))
  ([kind id handler-fn]
   (let [pipeline [i/mutate-game-state!
                   (i/->interceptor {:id     :event-fn
                                     :before handler-fn})]]

   (swap! kind->id->handlers assoc-in [kind id] pipeline))))

(defn dispatch [event]
  (swap! queue conj event))

(defn execute
  ([event]
   (execute :event event))
  ([kind [event-id]]
   (if-let [interceptors (get-in @kind->id->handlers [kind event-id])]
     (i/execute event-id interceptors))))

(register
  :mouse/left-click
  (fn [context]
    (update-in context
               [:coeffects :game/state :click/count]
               inc)))
