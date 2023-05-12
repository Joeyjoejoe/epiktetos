(ns epictetus.state)

(def engine-state
  (atom {:event/queue []}))

(defn dispatch-event! [event]
  (swap! engine-state update-in [:event/queue] conj event))

(defn reset-events! []
  (swap! engine-state assoc :event/queue []))
