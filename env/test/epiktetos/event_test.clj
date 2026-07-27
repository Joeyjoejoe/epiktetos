(ns epiktetos.event-test
  (:require [clojure.test :as t]
            [epiktetos.core :as core]
            [epiktetos.error :as error]
            [epiktetos.event :as event]
            [epiktetos.registrar :as registrar]))

(t/use-fixtures :each
  (fn [f]
    (reset! event/queue clojure.lang.PersistentQueue/EMPTY)
    (error/clear-pause-state!)
    (core/install-core!)
    (f)
    (reset! event/queue clojure.lang.PersistentQueue/EMPTY)
    (error/clear-pause-state!)
    (swap! registrar/registry update ::registrar/system-registry dissoc :gl/engine)))

(defn- enable-error-pause!
  []
  (swap! registrar/registry assoc-in
         [::registrar/system-registry :gl/engine :error-pause :enabled?] true))

(t/deftest nominal-consumption-test
  (t/testing "queued events are consumed in dispatch order"
    (let [log (atom [])]
      (core/reg-fx ::log (fn [value] (swap! log conj value)))
      (core/reg-event ::a (fn [_cofx fx] (assoc fx ::log :a)))
      (core/reg-event ::b (fn [_cofx fx] (assoc fx ::log :b)))
      (event/dispatch [::a])
      (event/dispatch [::b])
      (event/consume!)
      (t/is (= [:a :b] @log))
      (t/is (empty? @event/queue)))))

(t/deftest retry-after-fix-test
  (t/testing "a fixed handler retried in place completes the event"
    (enable-error-pause!)
    (let [log     (atom [])
          broken? (atom true)]
      (core/reg-fx ::log (fn [value] (swap! log conj value)))
      (core/reg-event ::fragile
                      (fn [_cofx fx]
                        (if @broken?
                          (throw (ex-info "broken" {}))
                          (assoc fx ::log :fixed))))
      (event/dispatch [::fragile])
      (let [pause-log (with-redefs [error/wake-loop!      (fn [] nil)
                                    error/pump-os-events! (fn []
                                                            (reset! broken? false)
                                                            (error/retry!))]
                        (with-out-str (event/consume!)))]
        (t/is (re-find #"✔ retry succeeded" pause-log)))
      (t/is (= [:fixed] @log))
      (t/is (false? (error/paused?))))))

(t/deftest retry-replacement-test
  (t/testing "a replacement event re-executes in the pending event slot"
    (enable-error-pause!)
    (let [log (atom [])]
      (core/reg-fx ::log (fn [value] (swap! log conj value)))
      (core/reg-event ::compute
                      (fn [{[_ n] :event} fx]
                        (assoc fx ::log (inc n))))
      (event/dispatch [::compute :not-a-number])
      (event/dispatch [::after])
      (core/reg-event ::after (fn [_cofx fx] (assoc fx ::log :after)))
      (with-redefs [error/wake-loop!      (fn [] nil)
                    error/pump-os-events! (fn [] (error/retry! [::compute 41]))]
        (with-out-str (event/consume!)))
      (t/is (= [42 :after] @log)))))

(t/deftest skip-preserves-remainder-test
  (t/testing "skipping the failing event resumes the rest of the batch"
    (enable-error-pause!)
    (let [log (atom [])]
      (core/reg-fx ::log (fn [value] (swap! log conj value)))
      (core/reg-event ::boom (fn [_cofx _fx] (throw (ex-info "boom" {}))))
      (core/reg-event ::ok (fn [_cofx fx] (assoc fx ::log :ok)))
      (event/dispatch [::boom])
      (event/dispatch [::ok])
      (let [pause-log (with-redefs [error/wake-loop!      (fn [] nil)
                                    error/pump-os-events! (fn [] (error/skip!))]
                        (with-out-str (event/consume!)))]
        (t/is (re-find #"⏭ skipped" pause-log)))
      (t/is (= [:ok] @log)))))

(t/deftest lookup-retry-test
  (t/testing "an event without handler pauses at :lookup and is not lost"
    (enable-error-pause!)
    (let [log (atom [])]
      (core/reg-fx ::log (fn [value] (swap! log conj value)))
      (event/dispatch [::unregistered 5])
      (with-redefs [error/wake-loop!      (fn [] nil)
                    error/pump-os-events! (fn []
                                            (t/is (= :lookup (:stage (error/error-report))))
                                            (core/reg-event ::unregistered
                                                            (fn [{[_ n] :event} fx]
                                                              (assoc fx ::log n)))
                                            (error/retry!))]
        (with-out-str (event/consume!)))
      (t/is (= [5] @log)))))

(t/deftest missing-cofx-pause-test
  (t/testing "an unregistered coeffect pauses at :coeffects and heals on retry"
    (enable-error-pause!)
    (let [log (atom [])]
      (core/reg-fx ::log (fn [value] (swap! log conj value)))
      (core/reg-event ::needs-cofx
                      [(core/inject-cofx ::not-registered)]
                      (fn [cofx fx]
                        (assoc fx ::log (::not-registered cofx))))
      (event/dispatch [::needs-cofx])
      (with-redefs [error/wake-loop!      (fn [] nil)
                    error/pump-os-events! (fn []
                                            (t/is (= :coeffects (:stage (error/error-report))))
                                            (core/reg-cofx ::not-registered
                                                           (fn [coeffects]
                                                             (assoc coeffects ::not-registered 7)))
                                            (error/retry!))]
        (with-out-str (event/consume!)))
      (t/is (= [7] @log)))))

(t/deftest missing-effect-handler-test
  (t/testing "an unregistered effect pauses recoverably before any execution"
    (enable-error-pause!)
    (let [log (atom [])]
      (core/reg-event ::wants-effect
                      (fn [{:keys [db]} fx]
                        (-> fx
                            (assoc :db (assoc db ::touched? true))
                            (assoc ::missing-probe :payload))))
      (event/dispatch [::wants-effect])
      (with-redefs [error/wake-loop!      (fn [] nil)
                    error/pump-os-events! (fn []
                                            (let [report (error/error-report)]
                                              (t/is (= :effects (:stage report)))
                                              (t/is (= :recoverable (:severity report)))
                                              (t/is (= [::missing-probe] (:fx/missing report))))
                                            (core/reg-fx ::missing-probe
                                                         (fn [value] (swap! log conj value)))
                                            (error/retry!))]
        (with-out-str (event/consume!)))
      (t/is (= [:payload] @log)))))

(t/deftest abort-test
  (t/testing "aborting stops consumption, the remainder stays queued"
    (enable-error-pause!)
    (let [log (atom [])]
      (core/reg-fx ::log (fn [value] (swap! log conj value)))
      (core/reg-event ::boom (fn [_cofx _fx] (throw (ex-info "boom" {}))))
      (core/reg-event ::never (fn [_cofx fx] (assoc fx ::log :never)))
      (event/dispatch [::boom])
      (event/dispatch [::never])
      (let [pause-log (with-redefs [error/wake-loop!      (fn [] nil)
                                    error/pump-os-events! (fn [] (error/abort!))]
                        (with-out-str (event/consume!)))]
        (t/is (re-find #"⏹ aborted" pause-log)))
      (t/is (= [] @log))
      (t/is (= [[::never]] (vec @event/queue))))))

(t/deftest terminal-effect-error-test
  (t/testing "a throwing user effect pauses terminally with the effects bookkeeping"
    (enable-error-pause!)
    (core/reg-fx ::explode (fn [_value] (throw (ex-info "bang" {}))))
    (core/reg-event ::doomed
                    (fn [{:keys [db]} fx]
                      (-> fx
                          (assoc :db (assoc db :hp 0))
                          (assoc ::explode true))))
    (event/dispatch [::doomed])
    (with-redefs [error/wake-loop!      (fn [] nil)
                  error/pump-os-events! (fn []
                                          (let [report (error/error-report)]
                                            (t/is (= :effects (:stage report)))
                                            (t/is (= :terminal (:severity report)))
                                            (t/is (= [:db] (:fx/executed report)))
                                            (t/is (= [::explode true] (:fx/failed report))))
                                          (error/abort!))]
      (with-out-str (event/consume!)))
    (t/is (false? (error/paused?)))))

(t/deftest disabled-handler-error-test
  (t/testing "error pause disabled, a handler error ends the session with the report"
    (core/reg-event ::boom (fn [_cofx _fx] (throw (ex-info "boom" {}))))
    (event/dispatch [::boom])
    (try
      (event/consume!)
      (t/is false "the report should have been thrown")
      (catch clojure.lang.ExceptionInfo e
        (t/is (= :handler (:stage (ex-data e))))
        (t/is (= [::boom] (:event (ex-data e))))))))

(t/deftest disabled-lookup-drop-test
  (t/testing "error pause disabled, a missing handler is logged and the event dropped"
    (event/dispatch [::never-registered])
    (let [log (with-out-str (event/consume!))]
      (t/is (re-find #"Event dropped" log))
      (t/is (empty? @event/queue)))))
