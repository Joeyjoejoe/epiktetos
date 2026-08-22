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




