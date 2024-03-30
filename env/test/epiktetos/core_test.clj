(ns epiktetos.core-test
  (:require [clojure.test :as t]
            [epiktetos.core :as core]))

(t/deftest start
  (t/testing "Testing start function"
    (t/is (= true (core/start)) "woo")
    (t/is (boolean? (core/start)))) "Output is boolean ?")
