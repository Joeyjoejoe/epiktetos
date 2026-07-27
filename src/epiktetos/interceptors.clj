;; A copy of https://github.com/day8/re-frame/blob/master/src/re_frame/interceptor.cljc
;; Extended with a Pedestal-style :error stage (error confinement, see
;; ai-spec/specs/error-spec.md): a thrown error is captured as data in
;; the context, short-circuits the chain, and unwinds the stack through
;; the interceptors :error fns.

(ns epiktetos.interceptors
  (:require [clojure.set :as set]))

;; Must be a pure function !
;; Must return a new state
;;
;; (defn handler-fn            ;; Take current state and event and
;;   [state event]             ;; it's parameters.
;;   (assoc state :foo event)) ;; Returns a new state


(def mandatory-interceptor-keys #{:id :after :before})

(defn interceptor?
  [m]
  (and (map? m)
       (set/subset? mandatory-interceptor-keys (set (keys m)))))


(defn ->interceptor
  [& {:as m :keys [id before after error]}]
  ;; (when debug-enabled?
  ;;   (if-let [unknown-keys (seq (set/difference
  ;;                               (-> m keys set)
  ;;                               mandatory-interceptor-keys))]
  ;;     (console :error "re-frame: ->interceptor" m "has unknown keys:" unknown-keys)))
  {:id     (or id :unnamed)
   :before before
   :after  after
   :error  error})

;; -- Effect Helpers  -----------------------------------------------------------------------------

 (defn get-effect
   ([context]
    (:effects context))
   ([context key]
    (get-in context [:effects key]))
   ([context key not-found]
    (get-in context [:effects key] not-found)))

 (defn assoc-effect
   [context key value]
   (assoc-in context [:effects key] value))

 (defn update-effect
   [context key f & args]
   (apply update-in context [:effects key] f args))

;; -- CoEffect Helpers  ---------------------------------------------------------------------------

 (defn get-coeffect
   ([context]
    (:coeffects context))
   ([context key]
    (get-in context [:coeffects key]))
   ([context key not-found]
    (get-in context [:coeffects key] not-found)))

 (defn assoc-coeffect
   [context key value]
   (assoc-in context [:coeffects key] value))

 (defn update-coeffect
   [context key f & args]
   (apply update-in context [:coeffects key] f args))

;; -- Execute Interceptor Chain  ------------------------------------------------------------------


(defn- invoke-interceptor-fn
  "Invoke the `direction` fn of interceptor on context.
  A thrown error is captured as data under `::error` — `:throwable`,
  `:interceptor` (the failing interceptor id), `:direction` — instead
  of propagating, and short-circuits the rest of the chain (see
  invoke-interceptors).
  Returns the context, updated or marked with `::error`."
  [context interceptor direction]
  (if-let [f (get interceptor direction)]
    (try
      (f context)
      (catch Throwable t
        (assoc context ::error {:throwable   t
                                :interceptor (:id interceptor)
                                :direction   direction})))
    context))

(defn- invoke-error-fn
  "Invoke the :error fn of interceptor on a context carrying `::error`,
  giving it a chance to enrich the error or to resolve it — removing
  `::error` resumes normal :after processing for the interceptors
  still to unwind. An interceptor without :error fn passes the context
  through untouched; an :error fn that throws replaces the error.
  Returns the context."
  [context interceptor]
  (if-let [f (:error interceptor)]
    (try
      (f context)
      (catch Throwable t
        (assoc context ::error {:throwable   t
                                :interceptor (:id interceptor)
                                :direction   :error})))
    context))


(defn- invoke-interceptors
  "Loop over all interceptors, calling `direction` function on each,
  threading the value of `context` through every call.
  `direction` is one of `:before` or `:after`.
  Each iteration, the next interceptor to process is obtained from
  context's `:queue`. After they are processed, interceptors are popped
  from `:queue` and added to `:stack`.
  After sufficient iteration, `:queue` will be empty, and `:stack` will
  contain all interceptors processed.
  Returns updated `context`. Ie. the `context` which has been threaded
  through all interceptor functions.
  Generally speaking, an interceptor's `:before` function will (if present)
  add to a `context's` `:coeffects`, while its `:after` function
  will modify the `context`'s `:effects`.  Very approximately.
  But because all interceptor functions are given `context`, and can
  return a modified version of it, the way is clear for an interceptor
  to introspect the stack or queue, or even modify the queue
  (add new interceptors via `enqueue`?). This is a very fluid arrangement.
  When the context carries `::error`, the `:before` walk halts — the
  interceptors not yet entered are never invoked — and the `:after`
  walk unwinds the entered interceptors through their :error fn
  (invoke-error-fn) instead of their :after fn, until one resolves the
  error or the stack is exhausted."
  ([context direction]
   (loop [context context]
     (let [queue (:queue context)]        ;; future interceptors
       (if (or (empty? queue)
               (and (= :before direction) (::error context)))
         context
         (let [interceptor (peek queue)   ;; next interceptor to call
               stack (:stack context)     ;; already completed interceptors
               context (assoc context
                              :queue (pop queue)
                              :stack (conj stack interceptor))]
           (recur (if (::error context)
                    (invoke-error-fn context interceptor)
                    (invoke-interceptor-fn context interceptor direction)))))))))


(defn enqueue
  [context interceptors]
  (update context :queue
          (fnil into clojure.lang.PersistentQueue/EMPTY)
          interceptors))


(defn- context
  "Create a fresh context"
  ([event interceptors]
   (-> {}
      (assoc-coeffect :event event)
      ;; Some interceptors, like `trim-v` and `unwrap`, alter event so capture
      ;; the original for use cases such as tracing.
      (assoc-coeffect :original-event event)
      (enqueue interceptors)))
  ([event interceptors db]      ;; only used in tests, probably a hack, remove ?  XXX
   (-> (context event interceptors)
       (assoc-coeffect :db db))))


(defn- change-direction
  "Called on completion of `:before` processing, this function prepares/modifies
   `context` for the backwards sweep of processing in which an interceptor
   chain's `:after` fns are called.
  At this point in processing, the `:queue` is empty and `:stack` holds all
  the previously run interceptors. So this function enables the backwards walk
  by priming `:queue` with what's currently in `:stack`"
  [context]
  (-> context
      (dissoc :queue)
      (enqueue (:stack context))))


(defn execute
  "Executes the given chain (coll) of interceptors.
   Each interceptor has this form:
       {:before  (fn [context] ...)     ;; returns possibly modified context
        :after   (fn [context] ...)}    ;; `identity` would be a noop
   Walks the queue of interceptors from beginning to end, calling the
   `:before` fn on each, then reverse direction and walk backwards,
   calling the `:after` fn on each.
   The last interceptor in the chain presumably wraps an event
   handler fn. So the overall goal of the process is to \"handle
   the given event\".
   Thread a `context` through all calls. `context` has this form:
     {:coeffects {:event [:a-query-id :some-param]
                  :db    <original contents of app-db>}
      :effects   {:db    <new value for app-db>
                  :fx  [:dispatch [:an-event-id :param1]]}
      :queue     <a collection of further interceptors>
      :stack     <a collection of interceptors already walked>}
   `context` has `:coeffects` and `:effects` which, if this was a web
   server, would be somewhat analogous to `request` and `response`
   respectively.
   `coeffects` will contain data like `event` and the initial
   state of `db` -  the inputs required by the event handler
   (sitting presumably on the end of the chain), while handler-returned
   side effects are put into `:effects` including, but not limited to,
   new values for `db`.
   The first few interceptors in a chain will likely have `:before`
   functions which \"prime\" the `context` by adding the event, and
   the current state of app-db into `:coeffects`. But interceptors can
   add whatever they want to `:coeffects` - perhaps the event handler needs
   some information from localstore, or a random number, or access to
   a DataScript connection.
   Equally, some interceptors in the chain will have `:after` fn
   which can process the side effects accumulated into `:effects`
   including but, not limited to, updates to app-db.
   Through both stages (before and after), `context` contains a `:queue`
   of interceptors yet to be processed, and a `:stack` of interceptors
   already done.  In advanced cases, these values can be modified by the
   functions through which the context is threaded.
   An error captured during the traversal (see invoke-interceptor-fn)
   that is still present after the backwards sweep — no :error fn
   resolved it — is rethrown as an ex-info wrapping the original
   throwable, carrying under `::error` the failing interceptor id, the
   direction and the final context.
   Returns the threaded context."
  [event-v interceptors]
  (let [context (-> (context event-v interceptors)
                    (invoke-interceptors :before)
                    change-direction
                    (invoke-interceptors :after))]
    (if-let [error (::error context)]
      (throw (ex-info "Unhandled interceptor chain error"
                      {::error (assoc error :context (dissoc context :queue :stack ::error))}
                      (:throwable error)))
      context)))
