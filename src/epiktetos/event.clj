(ns epiktetos.event
  (:require [epiktetos.interceptors :as interc]
            [epiktetos.error :as error]
            [epiktetos.registrar :as registrar]))

(def queue (atom clojure.lang.PersistentQueue/EMPTY))

;; Kinds :
;; - coeffects are just functions, later executed by do-cofx interceptor
;;   TODO we should directly store them as interceptors (cd inject-cofx)
;; - events are chain of interceptors

(defn get-id [event]
  (get event 0))

(defn get-handler
  ([id] (get-handler :events id))
  ([kind id] (get-in @registrar/registry [::registrar/event-registry kind id])))

(defn get-handlers [kind]
  (get-in @registrar/registry [::registrar/event-registry kind]))

;; Probably useless as a (when-let [h get-handler] ...) would suffice
(defn handler?
  ([event] (handler? :events event))
  ([kind event]
   (get-in @registrar/registry [::registrar/event-registry kind event])))

(def PLACEHOLDER-EVENTS
  #{::physics.update ::loop.iter})

(defn log-missing-event!
  [id]
  (when-not (id PLACEHOLDER-EVENTS)
    (println "event not registered" id)))

(defn dispatch
  "Queue an event for the next simulation step. The registry is not
  inspected: an event whose id has no handler yet is queued all the
  same, and reported by execute at consumption time — so a handler
  registered between the dispatch and the consumption works, and top
  level user declarations stack up freely before the engine starts.
  event - vector, [id & args]
  Returns the updated queue"
  [event]
  (swap! queue conj event))

(defn register
  ([id handler]
   (register :events id handler))
  ([kind id handler]
   (swap! registrar/registry assoc-in [::registrar/event-registry kind id] handler)))

(defn execute
  ([event]
   (if-let [interceptors (get-handler :events (get-id event))]
     (interc/execute event interceptors)
     (log-missing-event! (get-id event))))
  ([event & events]
   (doseq [e (cons event events)]
     (execute e))))

(defn- drain!
  "Take every queued event out of the queue.
  Returns the drained events in dispatch order, or nil"
  []
  (let [[drained] (swap-vals! queue (constantly clojure.lang.PersistentQueue/EMPTY))]
    (seq drained)))

(defn- requeue-head!
  "Return events to the head of the queue, in order, ahead of anything
  dispatched since they were drained.
  Returns the updated queue"
  [events]
  (swap! queue #(into (into clojure.lang.PersistentQueue/EMPTY events) %)))

(defn- try-execute
  "Execute one consumed event through its interceptor chain.
  Returns nil on success, the error report ex-info otherwise
  (see epiktetos.error)"
  [event]
  (if-let [interceptors (get-handler :events (get-id event))]
    (try
      (interc/execute event interceptors)
      nil
      (catch clojure.lang.ExceptionInfo e
        (if-let [chain-error (::interc/error (ex-data e))]
          (error/chain-report event chain-error)
          (throw e))))
    (error/lookup-report event)))

(defn- execute-confined
  "Execute one consumed event inside the error confinement boundary.
  A failing event hands its report to error/handle-error! — which
  pauses the loop when the error pause is enabled — after returning
  remainder, the rest of the batch, to the queue head. The decision
  drives what happens next: a retried event (possibly replaced)
  re-executes in its slot until it succeeds, is skipped, or aborts.
  Returns :continue when the batch can proceed, :redrain after a
  pause — the remainder waits in the queue — or :abort when the
  engine must stop"
  [event remainder]
  (if (and (contains? PLACEHOLDER-EVENTS (get-id event))
           (nil? (get-handler :events (get-id event))))
    :continue
    (if-let [report (try-execute event)]
      (do (when (seq remainder)
            (requeue-head! remainder))
          (loop [pending event
                 report  report]
            (let [{:keys [action paused?] replacement :event}
                  (error/handle-error! report)]
              (case action
                :skip  (do (when paused? (error/print-resumed!))
                           :redrain)
                :abort :abort
                :retry (let [pending (or replacement pending)]
                         (if-let [report (try-execute pending)]
                           (do (error/print-retry-failed! pending)
                               (recur pending report))
                           (do (error/print-retry-succeeded! pending)
                               :redrain)))))))
      :continue)))

(defn consume!
  "Consume the queued events in order, inside the error confinement
  boundary (ai-spec/specs/error-spec.md). Events dispatched during
  consumption wait for the next call, except after an error pause:
  the queue — batch remainder first, then anything dispatched while
  paused — is consumed by the same call once the loop resumes.
  Returns nil"
  []
  (loop [batch (drain!)]
    (when-let [event (first batch)]
      (case (execute-confined event (rest batch))
        :continue (recur (rest batch))
        :redrain  (recur (drain!))
        :abort    nil)))
  nil)
