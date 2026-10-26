(ns epiktetos.window.settings-test
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :as t]
            [integrant.core :as ig]
            [epiktetos.core :as core]
            [epiktetos.db :as app-db]
            [epiktetos.effect :as fx]
            [epiktetos.error :as error]
            [epiktetos.event :as event]
            [epiktetos.lang.glfw :as glfw]
            [epiktetos.registrar :as registrar]
            [epiktetos.window.settings :as settings]))

(defn- default-window-config
  "The :glfw/window map of the engine's default configuration.
  Returns a map"
  []
  (:glfw/window (ig/read-string (slurp (io/resource "epiktetos/default-config.edn")))))

(defn- recording-window
  "A window whose GLFW side effects are recorded instead of executed.
  current - map, its current runtime settings
  Returns {:id 1 :state atom}"
  [current]
  {:id 1 :state (atom {:settings current :windowed {:x 0 :y 0 :width 800 :height 600}})})

(defmacro ^:private with-recorded-glfw
  "Run body with the GLFW appliers replaced by recorders appending to
  the log atom."
  [log & body]
  `(with-redefs [settings/apply-setting! (fn [_# k# v#] (swap! ~log conj [k# v#]) v#)
                 settings/apply-size!    (fn [_# size#] (swap! ~log conj [:size size#]) nil)]
     ~@body))

(t/deftest key-categories-test
  (t/testing "every key of the default configuration is a creation or a runtime setting, never both"
    (let [config (default-window-config)]
      (t/is (empty? (settings/unknown-settings config)))
      (t/is (not-any? #(and (settings/creation-setting? %) (settings/runtime-setting? %))
                      (keys config)))))

  (t/testing "the application order covers exactly the runtime settings"
    (t/is (= (set (keys glfw/RUNTIME-SETTINGS)) (set settings/APPLICATION-ORDER)))
    (t/is (= (count settings/APPLICATION-ORDER) (count (set settings/APPLICATION-ORDER)))))

  (t/testing "the key name tells the category"
    (t/is (every? #(= "hint" (first (string/split (namespace %) #"\.")))
                  (filter settings/creation-setting? (keys (default-window-config)))))
    (t/is (not-any? #(and (namespace %) (.startsWith ^String (namespace %) "hint"))
                    (keys glfw/RUNTIME-SETTINGS))))

  (t/testing "renamed keys are unknown"
    (t/is (= [:hint.window/resizable :hint.window/visible :hint.window/gl.debug-context]
             (settings/unknown-settings (array-map :hint.window/resizable true
                                                   :hint.window/visible true
                                                   :hint.window/gl.debug-context true))))))

(t/deftest validate-window-settings-test
  (t/testing "a valid map is returned"
    (let [s {:mode/cursor :cursor/captured :title "x" :width 10 :display :fullscreen
             :window/visible false :mode/raw-mouse-motion true}]
      (t/is (= s (settings/validate-window-settings s)))))

  (t/testing "a creation setting is rejected"
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"fixed at window creation"
                            (settings/validate-window-settings {:hint.window/samples 4}))))

  (t/testing "an unknown key is rejected"
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown window setting"
                            (settings/validate-window-settings {:mode/cursr :cursor/normal}))))

  (t/testing "an invalid value is rejected"
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid value :disabled for :mode/cursor"
                            (settings/validate-window-settings {:mode/cursor :disabled})))
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid value"
                            (settings/validate-window-settings {:width 0})))
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid value"
                            (settings/validate-window-settings {:window/resizable 1}))))

  (t/testing "a non-map is rejected"
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes a map"
                            (settings/validate-window-settings [:mode/cursor :cursor/normal])))))

(t/deftest apply-window-settings-test
  (t/testing "settings apply in order, the size after the display, the visibility last"
    (let [log    (atom [])
          window (recording-window {})]
      (with-recorded-glfw log
        (settings/apply-window-settings! window {:window/visible true
                                                 :mode/cursor    :cursor/disabled
                                                 :width          640
                                                 :display        :fullscreen
                                                 :title          "t"}))
      (t/is (= [[:title "t"] [:display :fullscreen] [:size {:width 640}]
                [:mode/cursor :cursor/disabled] [:window/visible true]]
               @log))))

  (t/testing "a setting equal to the current one is skipped, the size never"
    (let [log    (atom [])
          window (recording-window {:mode/cursor :cursor/disabled :width 640})]
      (with-recorded-glfw log
        (t/is (= {} (settings/apply-window-settings! window {:mode/cursor :cursor/disabled
                                                             :width       640}))))
      (t/is (= [[:size {:width 640}]] @log))))

  (t/testing "the applied settings are returned and recorded, the size recorded only"
    (let [log    (atom [])
          window (recording-window {})]
      (with-recorded-glfw log
        (t/is (= {:mode/cursor :cursor/hidden}
                 (settings/apply-window-settings! window {:mode/cursor :cursor/hidden
                                                          :height      300}))))
      (t/is (= {:mode/cursor :cursor/hidden :height 300}
               (:settings @(:state window))))
      (t/is (= {:mode/cursor :cursor/hidden} (settings/current-settings window))))))

(t/deftest release-cursor-test
  (t/testing "no window, or no window state: nothing happens"
    (t/is (nil? (settings/release-cursor! nil)))
    (t/is (nil? (settings/release-cursor! {:id 1}))))

  (t/testing "a normal cursor is left alone"
    (let [log (atom [])]
      (with-recorded-glfw log
        (t/is (nil? (settings/release-cursor! (recording-window {:mode/cursor :cursor/normal})))))
      (t/is (empty? @log))))

  (t/testing "a captured cursor is released"
    (let [log (atom [])]
      (with-recorded-glfw log
        (t/is (= {:mode/cursor :cursor/normal}
                 (settings/release-cursor! (recording-window {:mode/cursor :cursor/disabled})))))
      (t/is (= [[:mode/cursor :cursor/normal]] @log)))))

(t/deftest error-release-cursor-test
  (t/testing "a pause releases the cursor and reflects it in [:core/window] at once"
    (let [log      (atom [])
          registry @registrar/registry
          db       @app-db/db]
      (try
        (swap! registrar/registry assoc-in [::registrar/system-registry :glfw/window]
               (recording-window {:mode/cursor :cursor/disabled}))
        (reset! app-db/db {:core/window {:mode/cursor :cursor/disabled :iter 3}})
        (with-recorded-glfw log
          (error/release-cursor!))
        (t/is (= {:mode/cursor :cursor/normal :iter 3} (:core/window @app-db/db)))
        (finally
          (reset! registrar/registry registry)
          (reset! app-db/db db))))))

(t/deftest window-pure-form-test
  (t/testing "several calls merge, the last value of a key wins"
    (t/is (= {:mode/cursor :cursor/normal :title "a"}
             (::fx/window (-> {}
                              (core/window {:mode/cursor :cursor/disabled :title "a"})
                              (core/window {:mode/cursor :cursor/normal}))))))

  (t/testing "an invalid map fails the calling handler"
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"fixed at window creation"
                            (core/window {} {:hint.window/samples 4}))))

  (t/testing "unrelated fx entries are preserved"
    (t/is (= {:score 1} (:db (core/window {:db {:score 1}} {:title "a"}))))))

(t/deftest window-effect-test
  (t/testing "an invalid map throws before anything is applied"
    (core/install-core!)
    (let [log (atom [])]
      (with-recorded-glfw log
        (t/is (thrown? clojure.lang.ExceptionInfo
                       ((event/get-handler :effects ::fx/window)
                        {:mode/cursor :cursor/disabled :hint.window/samples 4}))))
      (t/is (empty? @log)))))
