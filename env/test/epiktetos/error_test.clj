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
      (t/is (= :now (:coeffect data)))
      (t/is (not (contains? data :coeffects)))))

  (t/testing "a missing coeffect is lifted into the report, like :fx/missing"
    (let [throwable (ex-info "Coeffect not registered"
                             {:coeffect :rng :coeffect/missing :rng})
          data      (ex-data (error/chain-report
                               [:evt 1]
                               (chain-error :coeffects :before throwable {})))]
      (t/is (= :rng (:coeffect/missing data)))
      (t/is (= :recoverable (:severity data)))))

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

  (t/testing "a missing effect handler is recoverable — nothing was executed"
    (let [throwable (ex-info "No handler registered for effects"
                             {:fx/missing [:audio/play]})
          data      (ex-data (error/chain-report
                               [:evt] (chain-error :effects :after throwable {})))]
      (t/is (= :recoverable (:severity data)))
      (t/is (= [:audio/play] (:fx/missing data)))))

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
        (t/is (= {:action :retry :event [:fixed 1] :paused? true} @decision))
        (t/is (re-find #"⏸ Event Error \(:evt\)" log))
        (t/is (re-find #"Debug with:" log))
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
      (t/testing "stop! is the only exit"
        (with-out-str (error/stop!))
        (t/is (= {:action :abort :event nil}
                 (:decision @error/pause-state)))))))

(t/deftest log-helpers-test
  (t/testing "user-frame extracts the topmost non-internal frame, demunged"
    (let [throwable (doto (Exception. "boom")
                      (.setStackTrace
                        (into-array StackTraceElement
                                    [(StackTraceElement. "clojure.lang.Numbers" "add" "Numbers.java" 128)
                                     (StackTraceElement. "user$do_stuff" "invoke" "user.clj" 57)
                                     (StackTraceElement. "epiktetos.event$execute" "invoke" "event.clj" 60)])))]
      (t/is (= "user/do-stuff (user.clj:57)" (#'error/user-frame throwable)))))

  (t/testing "user-frame is nil when every frame is internal"
    (let [throwable (doto (Exception. "boom")
                      (.setStackTrace
                        (into-array StackTraceElement
                                    [(StackTraceElement. "clojure.core$inc" "invoke" "core.clj" 928)])))]
      (t/is (nil? (#'error/user-frame throwable)))))

  (t/testing "clean-message strips the JVM module clause"
    (t/is (= "class java.lang.String cannot be cast to class java.lang.Number"
             (#'error/clean-message
               (Exception. "class java.lang.String cannot be cast to class java.lang.Number (java.lang.String and java.lang.Number are in module java.base of loader 'bootstrap')")))))

  (t/testing "event-signature elides long and deep arguments"
    (let [signature (#'error/event-signature
                      [:enemies/spawn {:wave 3 :spawns (vec (repeat 50 {:pos [1 2 3]}))}])]
      (t/is (<= (count signature) 72))
      (t/is (re-find #"\.\.\." signature)))
    (t/is (= "[:do-stuff \"Doh!\"]" (#'error/event-signature [:do-stuff "Doh!"]))))

  (t/testing "failure-sentence names the stage and the failing piece"
    (t/is (= "[:do-stuff \"Doh!\"] blew up in its handler."
             (#'error/failure-sentence {:event [:do-stuff "Doh!"] :stage :handler})))
    (t/is (= "[:spawn 1] failed acquiring its coeffect :now."
             (#'error/failure-sentence {:event [:spawn 1]
                                        :stage :coeffects
                                        :coeffect :now})))
    (t/is (= "[:doom 1] failed executing its effect :audio/play."
             (#'error/failure-sentence {:event [:doom 1]
                                        :stage :effects
                                        :fx/failed [:audio/play :explosion]})))))

(t/deftest print-report-format-test
  (t/testing "a lookup error block: synthetic message, registration tip, full controls"
    (let [log (with-out-str
                (#'error/print-report!
                  {:event      [:game/save nil]
                   :stage      :effects
                   :severity   :recoverable
                   :fx/missing [:save/write]}))]
      (t/is (re-find #"⏸ Effect Lookup Error :save/write \(:game/save\) ─" log))
      (t/is (re-find #"│  no effect :save/write is registered" log))
      (t/is (re-find #"│  Register it with:" log))
      (t/is (re-find #"│  \(reg-fx :save/write handler-fn\)" log))
      (t/is (re-find #"├─ \(epiktetos.dev/retry!\)" log))))

  (t/testing "an execution error block: root cause, user frame, no tip"
    (let [throwable (doto (Exception. "saves/session.edn (No such file or directory)")
                      (.setStackTrace
                        (into-array StackTraceElement
                                    [(StackTraceElement. "user$save" "invoke" "user.clj" 16)])))
          log       (with-out-str
                      (#'error/print-report!
                        {:event     [:game/save nil]
                         :stage     :effects
                         :severity  :terminal
                         :error     throwable
                         :fx/failed [:save/write {}]}))]
      (t/is (re-find #"⏸ Effect Error :save/write \(:game/save\) ─" log))
      (t/is (re-find #"│  Exception: saves/session.edn" log))
      (t/is (re-find #"│  at user/save \(user.clj:16\)" log))
      (t/is (not (re-find #"Register it with:" log)))
      (t/is (not (re-find #"\(retry!\)" log)))
      (t/is (re-find #"├─ \(epiktetos.core/stop!\)" log))))

  (t/testing "a missing coeffect block maps on reg-cofx"
    (let [log (with-out-str
                (#'error/print-report!
                  {:event            [:loot/roll]
                   :stage            :coeffects
                   :severity         :recoverable
                   :coeffect/missing :random}))]
      (t/is (re-find #"⏸ Coeffect Lookup Error :random \(:loot/roll\) ─" log))
      (t/is (re-find #"│  no coeffect :random is registered" log))
      (t/is (re-find #"│  \(reg-cofx :random handler-fn\)" log)))))

(t/deftest controls-without-pending-error-test
  (t/testing "controls are inert and explain themselves"
    (with-redefs [error/wake-loop! (fn [] nil)]
      (let [log (with-out-str (error/retry!) (error/skip!) (error/stop!))]
        (t/is (false? (error/paused?)))
        (t/is (re-find #"No pending error" log))))))
