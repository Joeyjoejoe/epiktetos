(ns epiktetos.core-test
  (:require [clojure.test :as t]
            [epiktetos.core :as core]
            [epiktetos.effect :as fx]))

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
