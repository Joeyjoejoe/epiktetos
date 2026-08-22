(ns epiktetos.shader-input.buffer-test
  (:require [clojure.test :as t]
            [epiktetos.registrar :as registrar]
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

(t/deftest update-inputs-confinement-test
  (let [inputs         {"A" {:varname "A" :handler (fn [_ _] {})}
                        "B" {:varname "B" :handler (fn [_ _] {})}}
        program-inputs {"A" {:resource :ubo}
                        "B" {:resource :ubo}}
        calls          (atom [])]
    (try
      (t/testing "a failing input degrades once, the others still run"
        (with-redefs [buffer/update-input!
                      (fn [_ _ input _]
                        (swap! calls conj (:varname input))
                        (when (= "A" (:varname input))
                          (throw (ex-info "Invalid shader input data"
                                          {:path ["x"] :error "boom"}))))]
          (let [first-log  (with-out-str
                             (buffer/update-inputs! {} program-inputs inputs 0))
                second-log (with-out-str
                             (buffer/update-inputs! {} program-inputs inputs 0))]
            (t/is (= ["A" "B" "A" "B"] @calls))
            (t/is (re-find #"✖ input \"A\" degraded" first-log))
            (t/is (re-find #"boom" first-log))
            (t/is (= "" second-log))
            (t/is (contains? (get @registrar/render-state
                                  ::registrar/warned-inputs)
                             "A")))))

      (t/testing "a successful update rearms the warning"
        (with-redefs [buffer/update-input! (fn [_ _ _ _] nil)]
          (buffer/update-inputs! {} program-inputs inputs 0))
        (t/is (not (contains? (get @registrar/render-state
                                   ::registrar/warned-inputs #{})
                              "A"))))

      (t/testing "rearm-input! drops the warning of a varname"
        (swap! registrar/render-state
               update ::registrar/warned-inputs (fnil conj #{}) "C")
        (buffer/rearm-input! "C")
        (t/is (not (contains? (get @registrar/render-state
                                   ::registrar/warned-inputs #{})
                              "C"))))

      (finally
        (swap! registrar/render-state dissoc ::registrar/warned-inputs)))))

(t/deftest absence-warnings-test
  (try
    (swap! registrar/registry assoc-in
           [::registrar/system-registry :gl/engine :error-pause :enabled?]
           true)
    (t/testing "a drawn program with an unregistered input warns once"
      (let [first-log  (with-out-str (buffer/warn-unfed-inputs! :p ["uTime"]))
            second-log (with-out-str (buffer/warn-unfed-inputs! :p ["uTime"]))]
        (t/is (re-find #"✖ input \"uTime\" unfed — no reg-input \(drawn by :p\)"
                       first-log))
        (t/is (= "" second-log))))
    (t/testing "an input matching no program warns once"
      (let [inputs     {"uTme" {:varname "uTme" :handler (fn [_ _] 1.0)}}
            first-log  (with-out-str (buffer/update-inputs! {} {} inputs 0))
            second-log (with-out-str (buffer/update-inputs! {} {} inputs 0))]
        (t/is (re-find #"✖ input \"uTme\" unmatched — no program declares it"
                       first-log))
        (t/is (= "" second-log))))
    (t/testing "production mode stays silent"
      (swap! registrar/registry update-in
             [::registrar/system-registry :gl/engine] dissoc :error-pause)
      (swap! registrar/render-state dissoc ::registrar/warned-inputs)
      (t/is (= "" (with-out-str (buffer/warn-unfed-inputs! :p ["uTime"]))))
      (t/is (= "" (with-out-str
                    (buffer/update-inputs!
                      {} {} {"uTme" {:varname "uTme"
                                     :handler (fn [_ _] 1.0)}} 0)))))
    (finally
      (swap! registrar/render-state dissoc ::registrar/warned-inputs)
      (swap! registrar/registry update
             ::registrar/system-registry dissoc :gl/engine))))
