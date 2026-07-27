(ns epiktetos.coeffect-test
  (:require [clojure.test :as t]
            [epiktetos.coeffect :as cofx]))

(t/deftest inject-nominal-test
  (t/testing "a registered cofx enriches the context coeffects"
    (cofx/register ::now (fn [coeffects] (assoc coeffects ::now 42)))
    (let [before (:before (cofx/inject ::now))]
      (t/is (= 42 (get-in (before {:coeffects {}}) [:coeffects ::now])))))

  (t/testing "the value arity passes its argument to the cofx handler"
    (cofx/register ::seed (fn [coeffects value] (assoc coeffects ::seed value)))
    (let [before (:before (cofx/inject ::seed 7))]
      (t/is (= 7 (get-in (before {:coeffects {}}) [:coeffects ::seed]))))))

(t/deftest inject-missing-cofx-test
  (t/testing "a missing registration throws an ex-info tagged with the cofx id"
    (let [before (:before (cofx/inject ::missing))]
      (try
        (before {:coeffects {}})
        (t/is false "an ex-info should have been thrown")
        (catch clojure.lang.ExceptionInfo e
          (t/is (= ::missing (:coeffect (ex-data e)))))))))

(t/deftest inject-throwing-cofx-test
  (t/testing "a throwing cofx handler throws an ex-info carrying id, value and cause"
    (let [cause (ex-info "kaboom" {})]
      (cofx/register ::boom (fn [_coeffects _value] (throw cause)))
      (let [before (:before (cofx/inject ::boom :unlucky))]
        (try
          (before {:coeffects {}})
          (t/is false "an ex-info should have been thrown")
          (catch clojure.lang.ExceptionInfo e
            (t/is (= ::boom (:coeffect (ex-data e))))
            (t/is (= :unlucky (:value (ex-data e))))
            (t/is (identical? cause (.getCause e)))))))))
