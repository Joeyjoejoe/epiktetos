(ns epiktetos.shader-input.buffer-test
  (:require [clojure.test :as t]
            [epiktetos.shader-input.buffer :as buffer]))

(def ^:private unchanged-value? @#'buffer/unchanged-value?)

(t/deftest unchanged-value-test
  (t/testing "identical members in a fresh map skip the write"
    (let [bones [[1.0 0.0] [0.0 1.0]]]
      (t/is (unchanged-value? {"bones" bones} {"bones" bones}))
      (t/is (unchanged-value? {"count" 2 "bones" bones}
                              {"bones" bones "count" 2}))))

  (t/testing "equal but not identical members do not skip"
    (t/is (not (unchanged-value? {"bones" [1.0 2.0]}
                                 {"bones" [1.0 2.0]}))))

  (t/testing "no previous value never skips"
    (t/is (not (unchanged-value? nil {"bones" [1.0]}))))

  (t/testing "added, removed or swapped members do not skip"
    (let [bones [[1.0]]]
      (t/is (not (unchanged-value? {"bones" bones}
                                   {"bones" bones "count" 1})))
      (t/is (not (unchanged-value? {"bones" bones "count" 1}
                                   {"bones" bones})))
      (t/is (not (unchanged-value? {"bones" bones}
                                   {"count" bones}))))))
