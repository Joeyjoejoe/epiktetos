(ns epiktetos.startup-test
  (:require [clojure.test :as t]
            [epiktetos.event :as event]
            [epiktetos.registrar :as registrar]
            [epiktetos.startup :as startup]))

(t/deftest install-dev-tooling-test
  (t/testing "error pause disabled: the tooling is not installed"
    (swap! registrar/registry update-in
           [::registrar/event-registry :effects] dissoc :loop/pause-toggle)
    (swap! registrar/registry update ::registrar/system-registry dissoc :gl/engine)
    (startup/install-dev-tooling!)
    (t/is (nil? (event/get-handler :effects :loop/pause-toggle))))

  (t/testing "error pause enabled: epiktetos.dev is loaded and installed"
    (swap! registrar/registry assoc-in
           [::registrar/system-registry :gl/engine :error-pause :enabled?] true)
    (startup/install-dev-tooling!)
    (t/is (some? (find-ns 'epiktetos.dev)))
    (t/is (some? (event/get-handler :effects :loop/pause-toggle)))
    (t/is (some? (event/get-handler :events :dev/pause-toggle)))
    (swap! registrar/registry update ::registrar/system-registry dissoc :gl/engine)))
