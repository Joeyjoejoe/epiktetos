(ns epiktetos.coeffect
  (:require [epiktetos.event :as event]
            [epiktetos.state :as state]
            [epiktetos.interceptors :refer [->interceptor]]))

(defn register
  "A cofx is a function that takes the coeffects map and
   an optional parameter, and return a modified version
   of the coeffects map"
  [id cofx-fn]
  (event/register :coeffect id cofx-fn))

;; TODO implement fx-error to prevent loop break
(defn cofx-error
  "Register an error that occurred in a coeffect.
  Coeffect errors prevent events handler functions
  from executing, and display a warning."

  ([cofx err-map]
   (if (:errors cofx)
     (update cofx :errors conj err-map)
     (assoc cofx :errors [err-map])))

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
