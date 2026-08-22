(ns epiktetos.shader-input.registration-test
  (:require [clojure.test :as t]
            [epiktetos.core :as core]
            [epiktetos.error :as error]
            [epiktetos.event :as event]
            [epiktetos.opengl.buffer :as gl-buffer]
            [epiktetos.registrar :as registrar]
            [epiktetos.shader-input.buffer :as buffer]
            [epiktetos.shader-input.registration :as registration]))

(def ^:private count-schema
  {"count" {:kind :scalar :type (gl-buffer/glsl-type :int)}})

(def ^:private ubo-target
  {:varname "Blk" :resource :ubo :schema count-schema})

(t/deftest register-input-step-validation-test
  (let [handler (fn [_ _] {})]
    (try
      (t/testing "core steps are accepted"
        (t/is (some? (registration/register-input-handler!
                       "TestBlock" handler {})))
        (t/is (= :step/frame
                 (:step (registrar/lookup-input "TestBlock"))))
        (t/is (some? (registration/register-input-handler!
                       "TestBlock" handler {:step :step/entity}))))

      (t/testing "unknown steps are rejected at registration"
        (t/is (thrown? clojure.lang.ExceptionInfo
                       (registration/register-input-handler!
                         "TestBlock" handler {:step :step/frmae})))
        (t/is (thrown? clojure.lang.ExceptionInfo
                       (registration/register-input-handler!
                         "TestBlock" handler {:step :per-material}))))

      (t/testing "custom steps are accepted once registered with reg-steps!"
        (swap! registrar/render-state
               assoc ::registrar/custom-step-order [:per-material])
        (t/is (some? (registration/register-input-handler!
                       "TestBlock" handler {:step :per-material}))))

      (finally
        (swap! registrar/render-state
               dissoc ::registrar/custom-step-order)
        (swap! registrar/registry
               update ::registrar/input-registry dissoc "TestBlock")))))

(t/deftest validate-registration-test
  (t/testing "static parameters burst first"
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (registration/validate-registration!
                     nil :not-a-string (fn [_ _] {}) {} {})))
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (registration/validate-registration!
                     nil "Blk" 42 {} {}))))

  (t/testing "no matching program input: silent, handler untouched"
    (t/is (nil? (registration/validate-registration!
                  nil "Blk" (fn [_ _] (throw (ex-info "boom" {}))) {} {}))))

  (t/testing "non-frame steps skip the dry-run"
    (t/is (nil? (registration/validate-registration!
                  ubo-target "Blk" (fn [_ _] (throw (ex-info "boom" {})))
                  {:step :step/entity} {}))))

  (t/testing "dry-run: a crashing handler is wrapped with its input"
    (let [e (try (registration/validate-registration!
                   ubo-target "Blk"
                   (fn [_ _] (throw (RuntimeException. "boom"))) {} {})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (t/is (some? e))
      (t/is (= "Blk" (:input/varname (ex-data e))))
      (t/is (true? (:input/dry-run (ex-data e))))
      (t/is (instance? RuntimeException (ex-cause e)))))

  (t/testing "dry-run: an invalid output carries the validation path"
    (let [e (try (registration/validate-registration!
                   ubo-target "Blk" (fn [_ _] {"count" "doh"}) {} {})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (t/is (some? e))
      (t/is (= ["count"] (:path (ex-data e))))
      (t/is (= "Blk" (:input/varname (ex-data e))))
      (t/is (true? (:input/dry-run (ex-data e))))))

  (t/testing "dry-run: a valid output registers silently"
    (t/is (nil? (registration/validate-registration!
                  ubo-target "Blk" (fn [_ _] {"count" 3}) {} {}))))

  (t/testing "dry-run: a texture handler must return a texture id keyword"
    (t/is (nil? (registration/validate-registration!
                  {:resource :texture} "uAlbedo" (fn [_ _] :skin) {} {})))
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (registration/validate-registration!
                     {:resource :texture} "uAlbedo" (fn [_ _] 3) {} {})))))

(t/deftest reg-input-dry-run-pause-test
  (try
    (core/install-core!)
    (swap! registrar/registry assoc-in
           [::registrar/opengl-registry :program-inputs "Blk"]
           ubo-target)
    (swap! registrar/registry assoc-in
           [::registrar/system-registry :gl/engine :error-pause :enabled?]
           true)
    (event/dispatch [:epiktetos.event/reg-input
                     ["Blk" (fn [_ _] {"count" "doh"}) {}]])
    (let [log (with-out-str
                (with-redefs [error/wake-loop!      (fn [] nil)
                              buffer/ensure-block-capacity! (fn [_] nil)
                              error/pump-os-events!
                              (fn []
                                (event/dispatch
                                  [:epiktetos.event/reg-input
                                   ["Blk" (fn [_ _] {"count" 3}) {}]])
                                (error/retry!))]
                  (event/consume!)))]
      (t/is (re-find #"Shader Input Error \"Blk\"" log))
      (t/is (re-find #"Invalid value at \[\"count\"\]" log))
      (t/is (re-find #"picks up your reloaded fix" log))
      (t/is (re-find #"✔ retry succeeded — reg-input \"Blk\"" log))
      (t/is (false? (error/paused?)))
      (t/is (= {"count" 3}
               ((:handler (registrar/lookup-input "Blk")) {} 0))))
    (finally
      (reset! event/queue clojure.lang.PersistentQueue/EMPTY)
      (swap! registrar/registry update-in
             [::registrar/opengl-registry :program-inputs] dissoc "Blk")
      (swap! registrar/registry update
             ::registrar/system-registry dissoc :gl/engine)
      (error/clear-pause-state!))))

(t/deftest validate-registration-options-test
  (t/testing "unknown option keys are rejected with the known set"
    (let [e (try (registration/validate-registration!
                   nil "uAlbedo" (fn [_ _] :skin)
                   {:sampler/mag-filtre :nearest} {})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (t/is (some? e))
      (t/is (= [:sampler/mag-filtre]
               (:input/unknown-options (ex-data e))))
      (t/is (contains? (:allowed (ex-data e)) :sampler/mag-filter))
      (t/is (= "uAlbedo" (:input/varname (ex-data e))))))

  (t/testing "an invalid sampler value is tagged with its input"
    (let [e (try (registration/validate-registration!
                   nil "uAlbedo" (fn [_ _] :skin)
                   {:sampler/mag-filter :dnearest} {})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (t/is (some? e))
      (t/is (= "uAlbedo" (:input/varname (ex-data e))))
      (t/is (= :sampler/mag-filter (:option (ex-data e))))
      (t/is (= :dnearest (:value (ex-data e))))
      (t/is (= #{:nearest :linear} (:allowed (ex-data e))))))

  (t/testing "border-color requires a clamp-to-border wrap"
    (let [e (try (registration/validate-registration!
                   nil "uAlbedo" (fn [_ _] :skin)
                   {:sampler/border-color [0.0 0.0 0.0 1.0]} {})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (t/is (some? e))
      (t/is (= :sampler/border-color (:option (ex-data e))))
      (t/is (some? (:requires (ex-data e)))))
    (t/is (nil? (registration/validate-registration!
                  nil "uAlbedo" (fn [_ _] :skin)
                  {:sampler/wrap         :clamp-to-border
                   :sampler/border-color [0.0 0.0 0.0 1.0]} {})))))

(t/deftest reg-input-option-pause-test
  (try
    (core/install-core!)
    (swap! registrar/registry assoc-in
           [::registrar/system-registry :gl/engine :error-pause :enabled?]
           true)
    (event/dispatch [:epiktetos.event/reg-input
                     ["uAlbedo" (fn [_ _] :skin)
                      {:sampler/mag-filter :dnearest}]])
    (let [pumps (atom 0)
          log   (with-out-str
                  (with-redefs [error/wake-loop! (fn [] nil)
                                error/pump-os-events!
                                (fn []
                                  (case (swap! pumps inc)
                                    1 (error/skip!)
                                    (do (event/dispatch
                                          [:epiktetos.event/reg-input
                                           ["uAlbedo" (fn [_ _] :skin)
                                            {:sampler/mag-filter :nearest}]])
                                        (error/retry!))))]
                    (event/consume!)))]
      (t/is (re-find #"Shader Input Error \"uAlbedo\"" log))
      (t/is (re-find #"Invalid :sampler/mag-filter :dnearest" log))
      (t/is (re-find #"Allowed: :linear, :nearest" log))
      (t/is (re-find #"no honest skip" log))
      (t/is (re-find #"✔ retry succeeded — reg-input \"uAlbedo\"" log))
      (t/is (false? (error/paused?)))
      (t/is (= :nearest
               (:sampler/mag-filter (registrar/lookup-input "uAlbedo")))))
    (finally
      (reset! event/queue clojure.lang.PersistentQueue/EMPTY)
      (swap! registrar/registry update
             ::registrar/system-registry dissoc :gl/engine)
      (swap! registrar/registry update
             ::registrar/input-registry dissoc "uAlbedo")
      (error/clear-pause-state!))))

(t/deftest reg-input-validation-disabled-test
  (try
    (core/install-core!)
    (swap! registrar/registry assoc-in
           [::registrar/system-registry :glfw/window] {:id 1})
    (t/testing "production: no dev validation, the fx assert throws as before"
      (event/dispatch [:epiktetos.event/reg-input
                       ["uAlbedo" (fn [_ _] :skin)
                        {:sampler/mag-filter :dnearest}]])
      (t/is (thrown? clojure.lang.ExceptionInfo (event/consume!)))
      (t/is (nil? (registrar/lookup-input "uAlbedo"))))
    (t/testing "production: a valid registration goes through"
      (event/dispatch [:epiktetos.event/reg-input
                       ["uAlbedo" (fn [_ _] :skin) {}]])
      (event/consume!)
      (t/is (some? (registrar/lookup-input "uAlbedo"))))
    (finally
      (reset! event/queue clojure.lang.PersistentQueue/EMPTY)
      (swap! registrar/registry update
             ::registrar/system-registry dissoc :glfw/window)
      (swap! registrar/registry update
             ::registrar/input-registry dissoc "uAlbedo")
      (error/clear-pause-state!))))


(t/deftest declaration-adoption-test
  (try
    (let [prepare @#'event/prepare-declaration-retry!
          pending [:epiktetos.event/reg-input ["A" :old {}]]
          fix     [:epiktetos.event/reg-input ["A" :new {}]]
          other-b [:epiktetos.event/reg-input ["B" :b {}]]
          plain   [:player/damage 10]]
      (t/testing "adoption of the latest same-identity fix, other tail
                 declarations extracted as upstream, plain events kept"
        (event/dispatch plain)
        (event/dispatch [:epiktetos.event/reg-input ["A" :stale {}]])
        (event/dispatch other-b)
        (event/dispatch fix)
        (t/is (= {:pending fix :upstream [other-b]}
                 (prepare pending 0)))
        (t/is (= [plain] (vec @event/queue)))
        (reset! event/queue clojure.lang.PersistentQueue/EMPTY))
      (t/testing "the frozen batch remainder is never touched"
        (event/dispatch [:epiktetos.event/reg-input ["A" :stale {}]])
        (event/dispatch plain)
        (event/dispatch fix)
        (t/is (= {:pending fix :upstream []}
                 (prepare pending 1)))
        (t/is (= [[:epiktetos.event/reg-input ["A" :stale {}]] plain]
                 (vec @event/queue)))
        (reset! event/queue clojure.lang.PersistentQueue/EMPTY))
      (t/testing "no tail match: the pending declaration is retried as is"
        (t/is (= {:pending pending :upstream []}
                 (prepare pending 0))))
      (t/testing "plain events are never reconciled"
        (event/dispatch plain)
        (t/is (= {:pending [:player/damage 99] :upstream []}
                 (prepare [:player/damage 99] 0)))
        (t/is (= [plain] (vec @event/queue)))))
    (finally
      (reset! event/queue clojure.lang.PersistentQueue/EMPTY))))

(t/deftest render-declaration-pause-test
  (try
    (core/install-core!)
    (swap! registrar/registry assoc-in
           [::registrar/system-registry :gl/engine :error-pause :enabled?]
           true)
    (event/dispatch [:epiktetos.render.entity/render [:x {:program :inexistant}]])
    (let [log (with-out-str
                (with-redefs [error/wake-loop! (fn [] nil)
                              epiktetos.render.entity/add-entity! (fn [_ _] nil)
                              error/pump-os-events!
                              (fn []
                                (swap! registrar/registry assoc-in
                                       [::registrar/opengl-registry
                                        :programs :inexistant]
                                       {:id 1 :vao-id 1 :vbos []})
                                (error/retry!))]
                  (event/consume!)))]
      (t/is (re-find #"⏸  Render Error :x" log))
      (t/is (re-find #"Unknown shader program :inexistant" log))
      (t/is (re-find #"✔ retry succeeded — render :x" log))
      (t/is (false? (error/paused?))))
    (finally
      (reset! event/queue clojure.lang.PersistentQueue/EMPTY)
      (swap! registrar/registry update-in
             [::registrar/opengl-registry :programs] dissoc :inexistant)
      (swap! registrar/registry update
             ::registrar/system-registry dissoc :gl/engine)
      (error/clear-pause-state!))))

(t/deftest reg-p-declaration-pause-test
  (try
    (core/install-core!)
    (swap! registrar/registry assoc-in
           [::registrar/system-registry :gl/engine :error-pause :enabled?]
           true)
    (event/dispatch [:epiktetos.event/reg-p
                     [:typo/prog {:pipeline [[:vertex "shaders/nope.vert"]]}]])
    (let [log (with-out-str
                (with-redefs [error/wake-loop!      (fn [] nil)
                              error/pump-os-events! (fn [] (epiktetos.core/stop!))]
                  (event/consume!)))]
      (t/is (re-find #"⏸  Program Error :typo/prog" log))
      (t/is (re-find #"not found on the classpath" log))
      (t/is (re-find #"⏹ aborted — reg-p :typo/prog" log))
      (t/is (false? (error/paused?))))
    (finally
      (reset! event/queue clojure.lang.PersistentQueue/EMPTY)
      (swap! registrar/registry update
             ::registrar/system-registry dissoc :gl/engine)
      (error/clear-pause-state!))))
