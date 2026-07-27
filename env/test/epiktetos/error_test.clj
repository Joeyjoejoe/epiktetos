(ns epiktetos.error-test
  (:require [clojure.test :as t]
            [epiktetos.error :as error]
            [epiktetos.registrar :as registrar]))

(t/use-fixtures :each
  (fn [f]
    (error/clear-pause-state!)
    (f)
    (error/clear-pause-state!)
    (swap! registrar/registry update ::registrar/system-registry dissoc :gl/engine)))

(defn- enable-error-pause!
  []
  (swap! registrar/registry assoc-in
         [::registrar/system-registry :gl/engine :error-pause :enabled?] true))

(defn- chain-error
  "Build a ::interc/error-shaped map"
  [interceptor direction throwable context]
  {:throwable   throwable
   :interceptor interceptor
   :direction   direction
   :context     context})

(t/deftest chain-report-test
  (t/testing "a coeffects failure: recoverable, no acquired coeffects exposed"
    (let [throwable (ex-info "Coeffect error" {:coeffect :now})
          data      (ex-data (error/chain-report
                               [:evt 1]
                               (chain-error :coeffects :before throwable
                                            {:coeffects {:event [:evt 1]}})))]
      (t/is (= [:evt 1] (:event data)))
      (t/is (= :coeffects (:stage data)))
      (t/is (= :recoverable (:severity data)))
      (t/is (identical? throwable (:error data)))
      (t/is (not (contains? data :coeffects)))))

  (t/testing "a handler failure: recoverable, coeffects exposed"
    (let [data (ex-data (error/chain-report
                          [:evt 1]
                          (chain-error :event-fn :before (ex-info "boom" {})
                                       {:coeffects {:event [:evt 1] :db {:hp 1}}})))]
      (t/is (= :handler (:stage data)))
      (t/is (= :recoverable (:severity data)))
      (t/is (= {:event [:evt 1] :db {:hp 1}} (:coeffects data)))))

  (t/testing "an effects failure: terminal, effects and bookkeeping exposed"
    (let [throwable (ex-info "Effect error"
                             {:fx/executed  [:db]
                              :fx/failed    [::render :r]
                              :fx/remaining [::audio]})
          data      (ex-data (error/chain-report
                               [:evt 1]
                               (chain-error :effects :after throwable
                                            {:coeffects {:event [:evt 1]}
                                             :effects   {:db :new}})))]
      (t/is (= :effects (:stage data)))
      (t/is (= :terminal (:severity data)))
      (t/is (= {:db :new} (:effects data)))
      (t/is (= [:db] (:fx/executed data)))
      (t/is (= [::render :r] (:fx/failed data)))
      (t/is (= [::audio] (:fx/remaining data)))))

  (t/testing "an unknown interceptor id falls back on the direction"
    (t/is (= :effects
             (:stage (ex-data (error/chain-report
                                [:evt] (chain-error :custom :after (ex-info "x" {}) {}))))))
    (t/is (= :coeffects
             (:stage (ex-data (error/chain-report
                                [:evt] (chain-error :custom :before (ex-info "x" {}) {}))))))))

(t/deftest lookup-report-test
  (let [data (ex-data (error/lookup-report [:typo/evt 1]))]
    (t/is (= [:typo/evt 1] (:event data)))
    (t/is (= :lookup (:stage data)))
    (t/is (= :recoverable (:severity data)))
    (t/is (= [:typo/evt 1] (:event (ex-data (:error data)))))))

(t/deftest handle-error-disabled-test
  (t/testing "a lookup or coeffects report is printed and the event dropped"
    (let [decision (atom nil)
          log      (with-out-str
                     (reset! decision (error/handle-error! (error/lookup-report [:evt]))))]
      (t/is (= {:action :skip :event nil} @decision))
      (t/is (re-find #"Event dropped" log))
      (t/is (false? (error/paused?)))))

  (t/testing "a handler or effects report is thrown"
    (let [report (error/chain-report
                   [:evt] (chain-error :event-fn :before (ex-info "boom" {}) {}))]
      (try
        (error/handle-error! report)
        (t/is false "the report should have been thrown")
        (catch clojure.lang.ExceptionInfo e
          (t/is (identical? report e)))))))

(t/deftest handle-error-enabled-test
  (enable-error-pause!)
  (t/testing "the loop blocks until a decision, then the pause is cleared"
    (with-redefs [error/wake-loop!      (fn [] nil)
                  error/pump-os-events! (fn [] (error/retry! [:fixed 1]))]
      (let [report   (error/chain-report
                       [:evt] (chain-error :event-fn :before (ex-info "boom" {}) {}))
            decision (atom nil)
            log      (with-out-str
                       (reset! decision (error/handle-error! report)))]
        (t/is (= {:action :retry :event [:fixed 1]} @decision))
        (t/is (re-find #"engine paused" log))
        (t/is (re-find #"recoverable" log))
        (t/is (false? (error/paused?)))))))

(t/deftest error-report-access-test
  (enable-error-pause!)
  (with-redefs [error/wake-loop!      (fn [] nil)
                error/pump-os-events! (fn []
                                        (t/is (= :handler (:stage (error/error-report))))
                                        (error/skip!))]
    (with-out-str
      (error/handle-error!
        (error/chain-report
          [:evt] (chain-error :event-fn :before (ex-info "boom" {}) {}))))))

(t/deftest terminal-controls-test
  (enable-error-pause!)
  (let [report (error/chain-report
                 [:evt] (chain-error :effects :after
                                     (ex-info "Effect error" {:fx/executed [:db]})
                                     {}))]
    (reset! error/pause-state {:report report :decision nil})
    (with-redefs [error/wake-loop! (fn [] nil)]
      (t/testing "retry! and skip! are inert on a terminal pause"
        (let [log (with-out-str (error/retry!) (error/skip!))]
          (t/is (nil? (:decision @error/pause-state)))
          (t/is (re-find #"terminal" log))))
      (t/testing "abort! is the only exit"
        (with-out-str (error/abort!))
        (t/is (= {:action :abort :event nil}
                 (:decision @error/pause-state)))))))

(t/deftest controls-without-pending-error-test
  (t/testing "controls are inert and explain themselves"
    (with-redefs [error/wake-loop! (fn [] nil)]
      (let [log (with-out-str (error/retry!) (error/skip!) (error/abort!))]
        (t/is (false? (error/paused?)))
        (t/is (re-find #"No pending error" log))))))
