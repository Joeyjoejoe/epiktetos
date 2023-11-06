(ns epictetus.coeffect
  (:require [epictetus.event :as event]
            [epictetus.state :as state]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
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
(reg-cofx :inject-system
          (fn [coeffects]
            (assoc coeffects :system @state/system)))

(reg-cofx :inject-db
          (fn [coeffects]
            (assoc coeffects :db @state/db)))

(def inject-system (inject :inject-system))
(def inject-db (inject :inject-db))

;; Utilities
(reg-cofx :edn/load
          (fn parse-at
            [coeffects path]
            (let [data (-> path
                           io/resource
                           slurp
                           edn/read-string)]
              (-> coeffects
                  (assoc :edn/load data)))))

