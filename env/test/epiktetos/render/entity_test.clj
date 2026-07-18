(ns epiktetos.render.entity-test
  (:require [clojure.test :as t]
            [epiktetos.render.entity :as entity]))

(t/deftest draw-count-test
  (t/testing "non-instanced and static counts pass through"
    (t/is (nil? (entity/draw-count {} {})))
    (t/is (= 7 (entity/draw-count {:instances 7} {}))))

  (t/testing "dynamic count derives from db, clamped to [0 max]"
    (let [entity {:instances (fn [db] (:n db)) :max-instances 10}]
      (t/is (= 4 (entity/draw-count entity {:n 4})))
      (t/is (= 10 (entity/draw-count entity {:n 25})))
      (t/is (= 0 (entity/draw-count entity {:n -3}))))))
