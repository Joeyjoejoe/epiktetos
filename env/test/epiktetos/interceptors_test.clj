(ns epiktetos.interceptors-test
  (:require [clojure.test :as t]
            [epiktetos.interceptors :as interc :refer [->interceptor]]))

(defn- recording
  "Build an interceptor that records its invocations as [id direction]
  in the log atom.
  id  - keyword, the interceptor id
  log - atom holding a vector
  Returns an interceptor map"
  [id log]
  (->interceptor
    {:id     id
     :before (fn [context] (swap! log conj [id :before]) context)
     :after  (fn [context] (swap! log conj [id :after]) context)}))

(defn- throwing
  "Build an interceptor whose `direction` fn throws throwable.
  Returns an interceptor map"
  [id direction throwable]
  (->interceptor {:id id direction (fn [_context] (throw throwable))}))

(defn- execute-error
  "Execute the chain and return the ::interc/error data of the thrown
  ex-info, or nil when the chain succeeds"
  [event chain]
  (try
    (interc/execute event chain)
    nil
    (catch clojure.lang.ExceptionInfo e
      (::interc/error (ex-data e)))))

(t/deftest nominal-chain-test
  (t/testing "before fns run in chain order, after fns unwind in reverse"
    (let [log     (atom [])
          context (interc/execute [:evt 1] [(recording :a log) (recording :b log)])]
      (t/is (= [[:a :before] [:b :before] [:b :after] [:a :after]] @log))
      (t/is (= [:evt 1] (get-in context [:coeffects :event]))))))

(t/deftest before-error-test
  (let [log   (atom [])
        cause (ex-info "boom" {})
        error (execute-error [:evt 1]
                             [(recording :a log)
                              (throwing :failing :before cause)
                              (recording :c log)])]

    (t/testing "the error carries the failing interceptor, the direction and the throwable"
      (t/is (= :failing (:interceptor error)))
      (t/is (= :before (:direction error)))
      (t/is (identical? cause (:throwable error))))

    (t/testing "the final context is embedded, cleaned of chain plumbing"
      (let [context (:context error)]
        (t/is (= [:evt 1] (get-in context [:coeffects :event])))
        (t/is (not (contains? context :queue)))
        (t/is (not (contains? context :stack)))
        (t/is (not (contains? context ::interc/error)))))

    (t/testing "interceptors past the failing one never run, entered ones skip :after"
      (t/is (= [[:a :before]] @log)))))

(t/deftest after-error-test
  (t/testing "an error in an :after fn unwinds the remaining stack in error mode"
    (let [log   (atom [])
          error (execute-error [:evt 1]
                               [(recording :a log)
                                (throwing :failing :after (ex-info "boom" {}))])]
      (t/is (= :failing (:interceptor error)))
      (t/is (= :after (:direction error)))
      (t/is (= [[:a :before]] @log)))))

(t/deftest error-resolution-test
  (t/testing "an :error fn can resolve the error, resuming :after for the rest of the stack"
    (let [log      (atom [])
          rescuer  (assoc (recording :rescuer log)
                          :error (fn [context]
                                   (-> context
                                       (dissoc ::interc/error)
                                       (assoc :rescued? true))))
          context  (interc/execute [:evt 1]
                                   [(recording :a log)
                                    rescuer
                                    (throwing :failing :before (ex-info "boom" {}))])]
      (t/is (:rescued? context))
      (t/is (= [[:a :before] [:rescuer :before] [:a :after]] @log)))))

(t/deftest error-enrichment-test
  (t/testing "an :error fn can enrich the error without resolving it"
    (let [tagger (->interceptor
                   {:id    :tagger
                    :error (fn [context]
                             (update context ::interc/error assoc :tag :seen))})
          error  (execute-error [:evt 1]
                                [tagger
                                 (throwing :failing :before (ex-info "boom" {}))])]
      (t/is (= :seen (:tag error)))
      (t/is (= :failing (:interceptor error))))))

(t/deftest throwing-error-fn-test
  (t/testing "an :error fn that throws replaces the error"
    (let [bad-rescuer (->interceptor
                        {:id    :bad-rescuer
                         :error (fn [_context] (throw (ex-info "worse" {})))})
          error       (execute-error [:evt 1]
                                     [bad-rescuer
                                      (throwing :failing :before (ex-info "boom" {}))])]
      (t/is (= :bad-rescuer (:interceptor error)))
      (t/is (= :error (:direction error)))
      (t/is (= "worse" (.getMessage ^Throwable (:throwable error)))))))
