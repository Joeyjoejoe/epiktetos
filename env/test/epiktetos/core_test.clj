(ns epiktetos.core-test
  (:require [clojure.test :as t]
            [epiktetos.core :as core]
            [epiktetos.effect :as fx]
            [epiktetos.event :as event]
            [epiktetos.interceptors :as interc]))

(t/deftest pure-forms-test
  (t/testing "pure forms accumulate their entries in the fx map"
    (let [handler (fn [_db _step] {})
          fx      (-> {}
                      (core/render :triangle {:program :flat})
                      (core/render :square {:program :flat})
                      (core/delete :circle)
                      (core/dispatch :player/damage 10)
                      (core/reg-p :flat {:pipeline []})
                      (core/reg-input "Camera" handler {}))]
      (t/is (= #{[:triangle {:program :flat}] [:square {:program :flat}]}
               (set (::fx/render fx))))
      (t/is (= [:circle] (vec (::fx/delete fx))))
      (t/is (= [[:player/damage 10]] (vec (::fx/dispatch fx))))
      (t/is (= [[:flat {:pipeline []}]] (vec (::fx/reg-p fx))))
      (t/is (= [["Camera" handler {}]] (vec (::fx/reg-input fx))))))

  (t/testing "pure forms preserve unrelated fx entries"
    (let [fx (core/render {:db {:score 1}} :triangle {:program :flat})]
      (t/is (= {:score 1} (:db fx))))))

(t/deftest cofx-error-confinement-test
  (t/testing "a throwing cofx short-circuits the chain: no handler run, no effect executed"
    (core/install-core!)
    (let [handler-ran? (atom false)
          fx-ran?      (atom false)]
      (core/reg-fx ::probe (fn [_value] (reset! fx-ran? true)))
      (core/reg-cofx ::boom (fn [_coeffects] (throw (ex-info "kaboom" {}))))
      (core/reg-event ::failing-cofx-event
                      [(core/inject-cofx ::boom)]
                      (fn [_cofx fx]
                        (reset! handler-ran? true)
                        (assoc fx ::probe true)))
      (try
        (event/execute [::failing-cofx-event])
        (t/is false "an ex-info should have been thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [error (::interc/error (ex-data e))]
            (t/is (= :coeffects (:interceptor error)))
            (t/is (= :before (:direction error)))
            (t/is (= ::boom (:coeffect (ex-data (:throwable error))))))))
      (t/is (false? @handler-ran?))
      (t/is (false? @fx-ran?)))))
