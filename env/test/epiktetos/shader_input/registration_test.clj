(ns epiktetos.shader-input.registration-test
  (:require [clojure.test :as t]
            [epiktetos.registrar :as registrar]
            [epiktetos.shader-input.registration :as registration]))

(t/deftest register-input-step-validation-test
  (let [handler (fn [_ _] {})]
    (try
      (t/testing "core steps are accepted"
        (t/is (some? (registration/register-input-handler!
                       "TestBlock" handler {})))
        (t/is (= :step/frame
                 (:step (registrar/lookup-input "TestBlock"))))
        (t/is (some? (registration/register-input-handler!
                       "TestBlock" handler {:step :step/entity}))))

      (t/testing "unknown steps are rejected at registration"
        (t/is (thrown? clojure.lang.ExceptionInfo
                       (registration/register-input-handler!
                         "TestBlock" handler {:step :step/frmae})))
        (t/is (thrown? clojure.lang.ExceptionInfo
                       (registration/register-input-handler!
                         "TestBlock" handler {:step :per-material}))))

      (t/testing "custom steps are accepted once registered with reg-steps!"
        (swap! registrar/render-state
               assoc ::registrar/custom-step-order [:per-material])
        (t/is (some? (registration/register-input-handler!
                       "TestBlock" handler {:step :per-material}))))

      (finally
        (swap! registrar/render-state
               dissoc ::registrar/custom-step-order)
        (swap! registrar/registry
               update ::registrar/input-registry dissoc "TestBlock")))))
