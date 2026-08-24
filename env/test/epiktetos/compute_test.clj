(ns epiktetos.compute-test
  (:require [clojure.test :as t]
            [epiktetos.core :as core]
            [epiktetos.compute :as compute]
            [epiktetos.error :as error]
            [epiktetos.event :as event]
            [epiktetos.registrar :as registrar]))

(def ^:private on-classpath
  "An existing classpath file — validation only checks presence"
  "shaders/default.frag")

(defn- validation-error
  [id spec computes]
  (try
    (compute/validate-declaration! id spec computes)
    nil
    (catch clojure.lang.ExceptionInfo e e)))

(t/deftest validate-declaration-test
  (t/testing "spec must be a map"
    (let [e (validation-error :c/sim [:source] {})]
      (t/is (= :c/sim (:compute/id (ex-data e))))))
  (t/testing "unknown keys are rejected with the allowed set"
    (let [e (validation-error :c/sim {:source on-classpath
                                      :invocations 1
                                      :groups [1 1 1]} {})]
      (t/is (= [:groups] (:compute/unknown-keys (ex-data e))))
      (t/is (contains? (:allowed (ex-data e)) :workgroups))))
  (t/testing "source must be a string on the classpath"
    (t/is (some? (validation-error :c/sim {:invocations 1} {})))
    (let [e (validation-error :c/sim {:source "shaders/nope.comp"
                                      :invocations 1} {})]
      (t/is (= "shaders/nope.comp" (:source (ex-data e))))))
  (t/testing "exactly one of :invocations and :workgroups"
    (t/is (some? (validation-error :c/sim {:source on-classpath} {})))
    (t/is (some? (validation-error :c/sim {:source on-classpath
                                           :invocations 1
                                           :workgroups [1 1 1]} {}))))
  (t/testing "dispatch size forms"
    (t/is (nil? (validation-error :c/sim {:source on-classpath
                                          :invocations 100000} {})))
    (t/is (nil? (validation-error :c/sim {:source on-classpath
                                          :invocations [1920 1080]} {})))
    (t/is (nil? (validation-error :c/sim {:source on-classpath
                                          :workgroups (fn [db] (:n db))} {})))
    (let [e (validation-error :c/sim {:source on-classpath
                                      :invocations -1} {})]
      (t/is (= -1 (:invocations (ex-data e)))))
    (t/is (some? (validation-error :c/sim {:source on-classpath
                                           :invocations [1 2 3 4]} {}))))
  (t/testing "step must be known, error tagged with the compute id"
    (let [e (validation-error :c/sim {:source on-classpath
                                      :invocations 1
                                      :step :step/frmae} {})]
      (t/is (= :c/sim (:compute/id (ex-data e))))
      (t/is (= :step/frmae (:step (ex-data e))))
      (t/is (contains? (:known-steps (ex-data e)) :step/frame))))
  (t/testing "after must be a keyword or vector of keywords"
    (t/is (some? (validation-error :c/sim {:source on-classpath
                                           :invocations 1
                                           :after "c/other"} {})))
    (t/is (nil? (validation-error :c/sim {:source on-classpath
                                          :invocations 1
                                          :after [:c/a :c/b]} {}))))
  (t/testing "cycles among a step's computes are named"
    (let [computes {:c/a {:source on-classpath :invocations 1 :after :c/b}
                    :c/b {:source on-classpath :invocations 1 :after :c/a}}
          e (validation-error :c/c {:source on-classpath
                                    :invocations 1} computes)]
      (t/is (= #{:c/a :c/b} (:compute/cycle (ex-data e)))))
    (t/testing "a cycle through the declaration itself"
      (let [computes {:c/a {:source on-classpath :invocations 1 :after :c/b}}
            e (validation-error :c/b {:source on-classpath
                                      :invocations 1
                                      :after :c/a} computes)]
        (t/is (= #{:c/a :c/b} (:compute/cycle (ex-data e))))))
    (t/testing "cross-step references never cycle"
      (let [computes {:c/a {:source on-classpath :invocations 1
                            :step :step/group :after :c/b}}]
        (t/is (nil? (validation-error :c/b {:source on-classpath
                                            :invocations 1
                                            :after :c/a} computes)))))))

(t/deftest topo-sort-test
  (t/testing "after dependencies precede their dependents"
    (t/is (= [:c/clear :c/accumulate]
             (compute/topo-sort {:c/accumulate {:after :c/clear}
                                 :c/clear      {}}))))
  (t/testing "diamonds resolve, unrelated computes in id order"
    (t/is (= [:c/a :c/b :c/c :c/d]
             (compute/topo-sort {:c/d {:after [:c/b :c/c]}
                                 :c/c {:after :c/a}
                                 :c/b {:after :c/a}
                                 :c/a {}}))))
  (t/testing "references to absent computes are ignored"
    (t/is (= [:c/a]
             (compute/topo-sort {:c/a {:after :c/gone}}))))
  (t/testing "cycles never throw — cyclic computes appended in id order"
    (t/is (= [:c/ok :c/a :c/b]
             (compute/topo-sort {:c/a  {:after :c/b}
                                 :c/b  {:after :c/a}
                                 :c/ok {}})))))

(t/deftest reg-compute-pause-test
  (try
    (core/install-core!)
    (swap! registrar/registry assoc-in
           [::registrar/system-registry :gl/engine :error-pause :enabled?]
           true)
    (event/dispatch [:epiktetos.event/reg-compute
                     [:sim/smoke {:source "shaders/nope.comp"
                                  :invocations 100}]])
    (let [installed (atom nil)
          pumps     (atom 0)
          log (with-out-str
                (with-redefs [compute/setup! (fn [id spec]
                                               (reset! installed [id spec]))
                              error/wake-loop! (fn [] nil)
                              error/pump-os-events!
                              (fn []
                                (case (swap! pumps inc)
                                  1 (error/skip!)
                                  (do (event/dispatch
                                        [:epiktetos.event/reg-compute
                                         [:sim/smoke {:source on-classpath
                                                      :invocations 100}]])
                                      (error/retry!))))]
                  (event/consume!)))]
      (t/is (re-find #"⏸  Compute Error :sim/smoke" log))
      (t/is (re-find #"not found on the classpath" log))
      (t/is (re-find #"no honest skip" log))
      (t/is (re-find #"✔ retry succeeded — reg-compute :sim/smoke" log))
      (t/is (false? (error/paused?)))
      (t/is (= [:sim/smoke {:source on-classpath :invocations 100}]
               @installed)))
    (finally
      (reset! event/queue clojure.lang.PersistentQueue/EMPTY)
      (swap! registrar/registry update
             ::registrar/system-registry dissoc :gl/engine)
      (error/clear-pause-state!))))

(t/deftest reg-compute-adoption-test
  (try
    (let [prepare @#'event/prepare-declaration-retry!
          pending [:epiktetos.event/reg-compute [:c/sim {:invocations 1}]]
          fix     [:epiktetos.event/reg-compute [:c/sim {:invocations 2}]]
          other   [:epiktetos.event/reg-compute [:c/other {:invocations 3}]]]
      (event/dispatch other)
      (event/dispatch fix)
      (t/is (= {:pending fix :upstream [other]}
               (prepare pending 0))))
    (finally
      (reset! event/queue clojure.lang.PersistentQueue/EMPTY))))

(defn- register-fake-compute!
  [compute-k compute]
  (registrar/register-compute!
    compute-k
    (merge {:id 1
            :step :step/frame
            :local-size [64 1 1]
            :max-workgroups [65535 65535 65535]}
           compute)))

(t/deftest dispatch-for-step-test
  (try
    (let [dispatched (atom [])]
      (with-redefs [compute/dispatch! (fn [program-id counts]
                                        (swap! dispatched conj
                                               [program-id counts]))]
        (t/testing "workgroup counts derive from invocations and local size"
          (register-fake-compute! :c/sim {:id 7 :invocations 100})
          (registrar/register-computes-by-step! {:step/frame [:c/sim]})
          (compute/dispatch-for-step! {} {:step/frame [:c/sim]}
                                      :step/frame nil)
          (t/is (= [[7 [2 1 1]]] @dispatched)))

        (t/testing "workgroups are verbatim, fn form evaluated against db"
          (reset! dispatched [])
          (register-fake-compute! :c/tiles {:id 8
                                            :workgroups (fn [db] (:n db))})
          (compute/dispatch-for-step! {:n [4 4]} {:step/frame [:c/tiles]}
                                      :step/frame nil)
          (t/is (= [[8 [4 4 1]]] @dispatched)))

        (t/testing "a zero axis skips the dispatch"
          (reset! dispatched [])
          (register-fake-compute! :c/idle {:id 9 :invocations (fn [_] 0)})
          (compute/dispatch-for-step! {} {:step/frame [:c/idle]}
                                      :step/frame nil)
          (t/is (= [] @dispatched)))

        (t/testing "counts clamp to the GL limits"
          (reset! dispatched [])
          (register-fake-compute! :c/huge {:id 10
                                           :invocations 10000000
                                           :max-workgroups [100 100 100]})
          (compute/dispatch-for-step! {} {:step/frame [:c/huge]}
                                      :step/frame nil)
          (t/is (= [[10 [100 1 1]]] @dispatched)))

        (t/testing "steps other than the transition's never dispatch"
          (reset! dispatched [])
          (compute/dispatch-for-step! {} {:step/frame [:c/sim]}
                                      :step/group nil)
          (t/is (= [] @dispatched)))

        (t/testing "an invalid dynamic size degrades once and rearms"
          (reset! dispatched [])
          (register-fake-compute! :c/bad {:id 11
                                          :invocations (fn [db] (:n db))})
          (let [log (with-out-str
                      (compute/dispatch-for-step! {:n :garbage}
                                                  {:step/frame [:c/bad]}
                                                  :step/frame nil)
                      (compute/dispatch-for-step! {:n :garbage}
                                                  {:step/frame [:c/bad]}
                                                  :step/frame nil))]
            (t/is (= 1 (count (re-seq #"✖ compute :c/bad degraded" log))))
            (t/is (= [] @dispatched)))
          (compute/dispatch-for-step! {:n 64} {:step/frame [:c/bad]}
                                      :step/frame nil)
          (t/is (= [[11 [1 1 1]]] @dispatched))
          (t/testing "rearmed: a relapse warns again"
            (let [log (with-out-str
                        (compute/dispatch-for-step! {:n :garbage}
                                                    {:step/frame [:c/bad]}
                                                    :step/frame nil))]
              (t/is (re-find #"✖ compute :c/bad degraded" log)))))))
    (finally
      (swap! registrar/registry update-in
             [::registrar/opengl-registry] dissoc :computes :computes-by-step)
      (swap! registrar/render-state dissoc ::registrar/warned-computes))))
