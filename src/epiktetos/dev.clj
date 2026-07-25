(ns epiktetos.dev
  (:require [portal.api :as p]
            [epiktetos.core :as epiktet :refer [reg-event reg-fx]]
            [epiktetos.loop :as epiktet-loop]
            [epiktetos.db :as app-db]
            [epiktetos.event :as event]
            [epiktetos.registrar :as registrar])
  (:import (org.lwjgl.glfw GLFW)))

(def inspector nil)

(defn- portal-load-state
  "Open portal with engine's current state (ready for inspection)"
  []
  (p/clear)
  (tap> {:registry      @registrar/registry
         :render-state  @registrar/render-state
         :event-queue  @event/queue
         :db            @app-db/db}))

(defn- open-inspector
  []
  (alter-var-root #'inspector (constantly (p/open {:window-title "Inspector"})))
  (add-tap #'p/submit))

(defn- close-inspector
  []
  (p/clear)
  (remove-tap #'p/submit)
  (p/close))

(defn install-dev!
  "Register the development tooling handlers: pause toggle and Portal
  inspector, engine stop, and evaluation in the loop thread.

  This tooling is a user of the engine, not part of it: the halt
  empties the registry, tooling included, so start registers it again
  at every launch — the same launch process any application built on
  Epiktetos writes for itself.

  Returns nil"
  []
  (reg-fx :loop/pause-toggle
          (fn [db]
            (let [paused (get-in @app-db/db [:core/loop :paused?])]

              (if-not paused
                (open-inspector)
                (close-inspector))

              (swap! app-db/db update-in [:core/loop :paused?] not)

              (when-not paused
                (portal-load-state)))))

  (reg-fx :engine/stop
          (fn [_]
            (let [window (get-in @registrar/registry [::registrar/system-registry :glfw/window])]
              (GLFW/glfwSetWindowShouldClose window true))))

  (reg-fx ::eval-in-onpengl-context
          (fn [f]
           (clojure.pprint/pprint (f))
           (println "-----------------------")))

  ;; Eval f in opengl context
  (reg-event :dev/eval
    (fn [{[_ f] :event} fx]
      (assoc fx ::eval-in-onpengl-context f)))

  (reg-event
    [:press :escape]
    (fn loop-stop [_ fx]
      (assoc fx :engine/stop true)))

  (reg-event
    [:press :enter]
    (fn loop-play [_ fx]
      (assoc fx :loop/pause-toggle true)))

  (reg-event ::event/loop.iter
             (fn loop-infos [cofx fx]
               (let [{[_ loop-iter] :event
                      db :db}
                     cofx

                     new-db (assoc db :core/loop loop-iter)]
                 (assoc fx :db new-db))))
  nil)

(defn start
  "Install the development tooling handlers, then start the engine and
  block until it stops. The halt empties the registry, this tooling
  included, so start is where it is registered. Nothing a user
  namespace declared at load time is replayed: reloading it is the
  developer's call, from the editor.

  Refuses to start while an engine is running — stop it with Escape
  first: halting from the REPL thread would issue GL calls outside the
  thread holding the context.

  config-path - string, classpath path to an edn config (optional)
  Returns the loop exit value, or nil when an engine is already running"
  ([]
   (if (seq (::registrar/system-registry @registrar/registry))
     (println "[epiktetos] Engine already running - stop it (Escape) before restarting.")
     (do (install-dev!)
         (epiktet/run))))
  ([config-path]
   (install-dev!)
   (epiktet/run config-path)))
