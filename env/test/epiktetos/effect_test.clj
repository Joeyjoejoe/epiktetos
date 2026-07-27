(ns epiktetos.effect-test
  (:require [clojure.test :as t]
            [epiktetos.effect :as fx]))

(defn- recording
  "Build an effect handler recording [id value] in the log atom.
  Returns a 1-arity fn"
  [id log]
  (fn [value] (swap! log conj [id value])))

(t/deftest ordered-effects-test
  (t/testing ":db first, core effects in CORE-FX-ORDER, user effects last"
    (let [effects {::user-b   :ub
                   ::fx/render :r
                   :db        :new-db
                   ::fx/reg-p :p
                   ::user-a   :ua}
          ordered (mapv first (fx/ordered-effects effects))]
      (t/is (= :db (first ordered)))
      (t/is (= [::fx/reg-p ::fx/render] (subvec ordered 1 3)))
      (t/is (= #{::user-a ::user-b} (set (subvec ordered 3))))))

  (t/testing "absent tiers are skipped"
    (t/is (= [[::fx/delete :x]]
             (fx/ordered-effects {::fx/delete :x})))))

(t/deftest do-fx-execution-test
  (t/testing "effects execute in order and the context is returned"
    (let [log     (atom [])
          context {:effects {::user     :u
                             ::fx/render :r
                             :db        :new-db}}]
      (fx/register :db (recording :db log))
      (fx/register ::fx/render (recording ::fx/render log))
      (fx/register ::user (recording ::user log))
      (t/is (= context ((:after fx/do-fx) context)))
      (t/is (= [[:db :new-db] [::fx/render :r] [::user :u]] @log)))))

(t/deftest do-fx-error-test
  (t/testing "a throwing effect aborts the walk with the effects bookkeeping"
    (let [log   (atom [])
          cause (ex-info "kaboom" {})]
      (fx/register :db (recording :db log))
      (fx/register ::fx/reg-p (recording ::fx/reg-p log))
      (fx/register ::fx/render (fn [_value] (throw cause)))
      (fx/register ::user (recording ::user log))
      (try
        ((:after fx/do-fx) {:effects {::user     :u
                                      ::fx/render :r
                                      ::fx/reg-p :p
                                      :db        :new-db}})
        (t/is false "an ex-info should have been thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [{:fx/keys [executed failed remaining]} (ex-data e)]
            (t/is (= [:db ::fx/reg-p] executed))
            (t/is (= [::fx/render :r] failed))
            (t/is (= [::user] remaining))
            (t/is (identical? cause (.getCause e))))))
      (t/is (= [[:db :new-db] [::fx/reg-p :p]] @log)))))

(t/deftest do-fx-missing-handler-test
  (t/testing "a missing effect handler aborts before any execution"
    (let [log (atom [])]
      (fx/register :db (recording :db log))
      (fx/register ::user (recording ::user log))
      (try
        ((:after fx/do-fx) {:effects {:db              :new-db
                                      ::unknown-effect :x
                                      ::user           :u}})
        (t/is false "an ex-info should have been thrown")
        (catch clojure.lang.ExceptionInfo e
          (t/is (= [::unknown-effect] (:fx/missing (ex-data e))))))
      (t/is (= [] @log)))))
