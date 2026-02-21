(ns epiktetos.event
  (:require [epiktetos.interceptors :as interc]
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

(defn dispatch [event]
  (let [id (get-id event)]
    (if (get-handler id)
      (swap! queue conj event)
      (log-missing-event! id))))

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

(defn consume!
  []
  (let [[q] (swap-vals! queue (constantly clojure.lang.PersistentQueue/EMPTY))]
    (doseq [e q]
      (execute e))))
