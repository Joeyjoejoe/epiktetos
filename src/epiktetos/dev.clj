(ns epiktetos.dev
  (:require [portal.api :as p]
            [epiktetos.core :refer [reg-event reg-fx]]
            [epiktetos.loop :as epiktet-loop]
            [epiktetos.db :as app-db]
            [epiktetos.error :as error]
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
  "Register the development tooling handlers: pause toggle with Portal
  inspector (see inspect!), engine stop effect, and evaluation in the
  loop thread. No keyboard binding: the tooling is driven by functions
  called from the REPL/editor — inspect!, resume!, and
  epiktetos.core/stop! — keys stay free for the application.

  This tooling is a user of the engine, not part of it: the halt
  empties the registry, tooling included, so it is registered again at
  every launch — automatically by the engine startup when the error
  pause is enabled (startup/install-dev-tooling! — development mode is
  declared by the configuration). A session running with the pause
  disabled calls it explicitly before epiktetos.core/start.

  Returns nil"
  []
  (reg-fx :loop/pause-toggle
          (fn [db]
            (let [paused (get-in @app-db/db [:core/loop :paused?])]

              (if-not paused
                (do (open-inspector)
                    (error/print-paused! "state inspection"))
                (do (close-inspector)
                    (error/print-resumed!)))

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

  (reg-event :dev/pause-toggle
    (fn pause-toggle [_ fx]
      (assoc fx :loop/pause-toggle true)))

  (reg-event ::event/loop.iter
             (fn loop-infos [cofx fx]
               (let [{[_ loop-iter] :event
                      db :db}
                     cofx

                     new-db (assoc db :core/loop loop-iter)]
                 (assoc fx :db new-db))))
  nil)

(defn- wake-paused-loop!
  "Wake a loop thread blocked waiting for OS events, so a dispatch
  made from the REPL is consumed without moving the mouse.
  Returns nil"
  []
  (when (get-in @registrar/registry [::registrar/system-registry :glfw/window])
    (GLFW/glfwPostEmptyEvent))
  nil)

(defn inspect!
  "Pause the loop and open the Portal inspector on a full snapshot of
  the engine state: registry, render-state, event queue, db. A toggle:
  called on a paused loop, closes the inspector and resumes.
  Returns nil"
  []
  (event/dispatch [:dev/pause-toggle])
  (wake-paused-loop!)
  nil)

(defn error-report
  "Return the report data of the pending error — event, stage,
  severity, coeffects, effects bookkeeping — or nil when the engine is
  not paused on an error (ai-spec/specs/error-spec.md)"
  []
  (error/error-report))

(defn retry!
  "Re-execute the pending event of a recoverable error pause — from
  the start of its chain, coeffects re-acquired — replaced by the
  given event when provided. Inert outside a recoverable error pause.
  event - optional replacement event vector
  Returns nil"
  ([] (error/retry!))
  ([event] (error/retry! event)))

(defn skip!
  "Drop the pending event of a recoverable error pause entirely and
  resume the loop. Inert outside a recoverable error pause.
  Returns nil"
  []
  (error/skip!))


