(ns epiktetos.error
  "Event pipeline error confinement (ai-spec/specs/error-spec.md).

  Builds the error report of a failed event, prints the instructive
  log, and drives the error pause: the loop thread blocks in
  handle-error! until a control fn — retry!, skip!, abort!, called
  from the REPL — delivers a decision. A development feature, enabled
  from the engine configuration (:gl/engine :error-pause); disabled,
  the engine behaves as before, the report enriching the fatal
  exception for free."
  (:require [epiktetos.db :as app-db]
            [epiktetos.registrar :as registrar])
  (:import (org.lwjgl.glfw GLFW)))

(def STAGES
  "Pipeline stage of a report, by failing interceptor id"
  {:coeffects :coeffects
   :event-fn  :handler
   :effects   :effects})

(defonce pause-state
  ;; {:report ex-info, :decision nil | {:action kw :event replacement}}
  (atom nil))

(defn enabled?
  "True when the error pause is enabled in the engine configuration,
  under [:gl/engine :error-pause :enabled?]"
  []
  (boolean (get-in @registrar/registry
                   [::registrar/system-registry :gl/engine :error-pause :enabled?])))

(defn paused?
  "True while the loop is paused on an error"
  []
  (some? @pause-state))

(defn error-report
  "Return the report data of the pending error, or nil when the engine
  is not paused on an error"
  []
  (when-let [{:keys [report]} @pause-state]
    (ex-data report)))

(defn clear-pause-state!
  "Drop any pending error and decision, on engine halt.
  Returns nil"
  []
  (reset! pause-state nil)
  nil)

;; -- Reports -------------------------------------------------------------

(defn- severity-of
  [stage]
  (if (= :effects stage) :terminal :recoverable))

(defn- report
  "Build the report ex-info from its data map and original throwable"
  [{:keys [stage] :as data} throwable]
  (ex-info "Event pipeline error"
           (assoc data
                  :severity (severity-of stage)
                  :iter     (get-in @app-db/db [:core/loop :iter]))
           throwable))

(defn chain-report
  "Build the report of an event whose interceptor chain failed.
  event - the reified event vector
  error - map, the ::epiktetos.interceptors/error data of the thrown
          ex-info: :throwable, :interceptor, :direction, :context
  Returns an ex-info whose ex-data is the report map of the spec"
  [event {:keys [throwable interceptor direction context]}]
  (let [stage (or (STAGES interceptor)
                  (if (= :after direction) :effects :coeffects))
        data  (cond-> {:event event
                       :stage stage
                       :error throwable}
                (#{:handler :effects} stage)
                (assoc :coeffects (:coeffects context))

                (= :effects stage)
                (-> (assoc :effects (:effects context))
                    (merge (select-keys (ex-data throwable)
                                        [:fx/executed :fx/failed :fx/remaining]))))]
    (report data throwable)))

(defn lookup-report
  "Build the report of an event consumed without registered handler.
  event - the reified event vector
  Returns an ex-info whose ex-data is the report map of the spec"
  [event]
  (let [throwable (ex-info "No handler registered for event" {:event event})]
    (report {:event event
             :stage :lookup
             :error throwable}
            throwable)))

;; -- Instructive log -----------------------------------------------------

(defn- error-line
  "One line naming the root cause of the report: class and message"
  [throwable]
  (let [cause (or (.getCause ^Throwable throwable) throwable)]
    (str (.getSimpleName (class cause)) ": " (.getMessage ^Throwable cause))))

(defn- print-recoverable!
  [{:keys [event stage error]}]
  (println "[epiktetos] Error - engine paused")
  (println "  event    " (pr-str event))
  (println "  stage    " stage)
  (println "  error    " (error-line error))
  (println "  status    recoverable - no effect was applied, the event can be retried")
  (println "  context   (epiktetos.core/error-report)")
  (println "  controls  (retry!)             re-run the event (after reloading your fix)")
  (println "            (retry! [:id args])  replace the event and re-run")
  (println "            (skip!)              drop the event and resume")
  (println "            (abort!)             stop the engine"))

(defn- print-terminal!
  [{:keys [event stage error] :fx/keys [executed failed remaining]}]
  (println "[epiktetos] Error - engine paused")
  (println "  event    " (pr-str event))
  (println "  stage    " stage)
  (println "  error    " (error-line error))
  (println "  status    terminal - effects partially applied")
  (println "           " (str "applied: " (pr-str executed)
                              " - failed: " (pr-str (first failed))
                              " - never ran: " (pr-str remaining)))
  (println "            The engine can no longer guarantee its state.")
  (println "            Inspect freely - (epiktetos.core/error-report) - then (abort!).")
  (println "  controls  (abort!)  stop the engine"))

(defn- print-report!
  [{:keys [severity] :as data}]
  (if (= :terminal severity)
    (print-terminal! data)
    (print-recoverable! data)))

(defn- print-dropped!
  "Log a report confined without pause (error pause disabled)"
  [{:keys [event stage error]}]
  (println "[epiktetos] Event dropped -" (pr-str event)
           "- stage" stage "-" (error-line error)))

;; -- Error pause ---------------------------------------------------------

(defn pump-os-events!
  "Service the OS event pump so the frozen window stays responsive
  while the loop thread waits for a decision. Internal, redefined by
  tests.
  Returns nil"
  []
  (GLFW/glfwWaitEventsTimeout 0.1))

(defn wake-loop!
  "Wake a loop thread blocked in pump-os-events!. Internal, redefined
  by tests.
  Returns nil"
  []
  (GLFW/glfwPostEmptyEvent))

(defn- await-decision!
  "Block the calling thread — the loop thread — until a control fn
  delivers a decision.
  Returns the decision map: {:action :retry|:skip|:abort, :event replacement-or-nil}"
  []
  (loop []
    (if-let [decision (:decision @pause-state)]
      decision
      (do (pump-os-events!)
          (recur)))))

(defn handle-error!
  "React to a failed event, on the loop thread.

  Error pause disabled: :lookup and :coeffects reports are printed and
  the event dropped — the confinement existing before the error pause —
  while :handler and :effects reports are thrown: the session ends as
  before, the report carried by the exception.

  Enabled: print the instructive log, hold the report as the pending
  error and block until a control fn delivers a decision.

  report - the report ex-info (chain-report, lookup-report)
  Returns the decision map: {:action :retry|:skip|:abort, :event replacement-or-nil}"
  [report]
  (let [{:keys [stage] :as data} (ex-data report)]
    (if-not (enabled?)
      (if (#{:lookup :coeffects} stage)
        (do (print-dropped! data)
            {:action :skip :event nil})
        (throw report))
      (do (print-report! data)
          (reset! pause-state {:report report :decision nil})
          (let [decision (await-decision!)]
            (clear-pause-state!)
            decision)))))

;; -- Controls ------------------------------------------------------------

(defn- deliver-decision!
  "Deliver a control decision to the paused loop thread, when the
  pending error allows it.
  Returns nil"
  [action replacement]
  (let [{:keys [report]} @pause-state]
    (cond
      (nil? report)
      (println "[epiktetos] No pending error - nothing to" (name action))

      (and (= :terminal (:severity (ex-data report)))
           (not= :abort action))
      (do (println "[epiktetos] The error is terminal: effects were partially applied,")
          (println "            the engine can no longer guarantee its state.")
          (println "            Keep your effects small, feed them validated data, decide")
          (println "            in pure code. Inspect (error-report), then (abort!)."))

      :else
      (do (swap! pause-state assoc :decision {:action action :event replacement})
          (wake-loop!)))
    nil))

(defn retry!
  "Re-execute the pending event of a recoverable error pause — from
  the start of its chain, coeffects re-acquired — replaced by the
  given event when provided. Inert outside a recoverable error pause.
  event - optional replacement event vector
  Returns nil"
  ([] (retry! nil))
  ([event]
   (deliver-decision! :retry event)))

(defn skip!
  "Drop the pending event of a recoverable error pause entirely and
  resume the loop. Inert outside a recoverable error pause.
  Returns nil"
  []
  (deliver-decision! :skip nil))

(defn abort!
  "Stop the engine from an error pause — the clean halt, and the only
  control of a terminal pause. Inert outside an error pause.
  Returns nil"
  []
  (deliver-decision! :abort nil))
