(ns epiktetos.coeffect
  (:require [epiktetos.event :as event]
            [epiktetos.interceptors :refer [->interceptor]]))

(defn register
  "A cofx is a function that takes the coeffects map and
   an optional parameter, and return a modified version
   of the coeffects map"
  [id cofx-fn]
  (event/register :coeffects id cofx-fn))

(defn inject
  "Build the interceptor injecting the cofx registered under id into
  the context coeffects. A missing registration or a throwing cofx
  handler throws an ex-info tagged with :coeffect (and :value when
  given) — plus :coeffect/missing for the lookup case, mirroring the
  :fx/missing preflight of do-fx — captured as data by the chain (see
  epiktetos.interceptors), so the event handler never runs on
  incomplete coeffects.
  id    - keyword, the registered cofx
  value - optional argument passed to the cofx handler
  Returns an interceptor map"
  ([id]
   (->interceptor
     :id      :coeffects
     :before  (fn coeffects-before
                [context]
                (if-let [handler (event/get-handler :coeffects id)]
                  (try
                    (update context :coeffects handler)
                    (catch Throwable t
                      (throw (ex-info "Coeffect error"
                                      {:coeffect id}
                                      t))))
                  (throw (ex-info "Coeffect not registered"
                                  {:coeffect id :coeffect/missing id}))))))
  ([id value]
   (->interceptor
     :id     :coeffects
     :before  (fn coeffects-before
                [context]
                (if-let [handler (event/get-handler :coeffects id)]
                  (try
                    (update context :coeffects handler value)
                    (catch Throwable t
                      (throw (ex-info "Coeffect error"
                                      {:coeffect id :value value}
                                      t))))
                  (throw (ex-info "Coeffect not registered"
                                  {:coeffect id :coeffect/missing id :value value})))))))
