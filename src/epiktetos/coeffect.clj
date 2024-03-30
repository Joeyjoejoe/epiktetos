(ns epiktetos.coeffect
  (:require [epiktetos.event :as event]
            [epiktetos.state :as state]
            [clojure.pprint :refer [pprint]]
            [epiktetos.interceptors :refer [->interceptor]]))

(defn reg-cofx
  "A cofx is a function that takes the coeffects map and
   an optional parameter, and return a modified version
   of the coeffects map"
  [id cofx-fn]
  (event/register :coeffect id cofx-fn))

(defn cofx-error
  "Register an error that occurred in a coeffect.
  Coeffect errors prevent events handler functions
  from executing, and display a warning."

  ([cofx err-map]
   (update cofx :errors conj err-map))

  ([cofx cofx-id msg]
   (let [event   (:event cofx)
         err-map {:event event :coeffect cofx-id :error msg}]
     (cofx-error cofx err-map)))

  ([cofx cofx-id value msg]
   (let [event   (:event cofx)
         err-map {:event event :coeffect cofx-id :value value :error msg}]
     (cofx-error cofx err-map))))

(defn inject
  ([id]
   (->interceptor
     :id      :coeffects
     :before  (fn coeffects-before
                [context]
                (if-let [handler (event/get-handler :coeffect id)]
                  (try
                    (update context :coeffects handler)
                    (catch Exception e
                      (update context :coeffects
                              #(cofx-error % id (cons (.toString e) (.getStackTrace e))))))
                  (update context :coeffects
                          #(cofx-error % id "cofx not registered"))))))
  ([id value]
   (->interceptor
     :id     :coeffects
     :before  (fn coeffects-before
                [context]
                (if-let [handler (event/get-handler :coeffect id)]
                  ;; TODO rescue handler execution errors and create
                  ;; proper cofx-error
                  (try
                    (update context :coeffects handler value)
                    (catch Exception e
                      (update context :coeffects
                          #(cofx-error % id value (cons (.toString e) (.getStackTrace e))))))
                  (update context :coeffects
                          #(cofx-error % id value "cofx not registered")))))))

;; Core coeffects
(reg-cofx :inject-system
          (fn [coeffects]
            (assoc coeffects :system @state/system)))

(reg-cofx :inject-db
          (fn [coeffects]
            (assoc coeffects :db @state/db)))

(reg-cofx :error-logger
          (fn [coeffects]
            (when-let [errors (:errors coeffects)]
              (doseq [err errors] (pprint err)))
            coeffects))

(def inject-system (inject :inject-system))
(def inject-db (inject :inject-db))
(def error-logger (inject :error-logger))
