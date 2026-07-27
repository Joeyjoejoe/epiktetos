(ns epiktetos.error
  "Event pipeline error confinement (ai-spec/specs/error-spec.md).

  Builds the error report of a failed event, prints the instructive
  log, and drives the error pause: the loop thread blocks in
  handle-error! until a control fn — retry!, skip!, abort!, called
  from the REPL — delivers a decision. A development feature, enabled
  from the engine configuration (:gl/engine :error-pause); disabled,
  the engine behaves as before, the report enriching the fatal
  exception for free."
  (:require [clojure.string :as string]
            [epiktetos.db :as app-db]
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
  "Terminal only when effects have started mutating: a missing effect
  handler is detected before any execution (do-fx preflight) and stays
  recoverable"
  [stage throwable]
  (if (and (= :effects stage)
           (not (:fx/missing (ex-data throwable))))
    :terminal
    :recoverable))

(defn- report
  "Build the report ex-info from its data map and original throwable"
  [{:keys [stage] :as data} throwable]
  (ex-info "Event pipeline error"
           (assoc data
                  :severity (severity-of stage throwable)
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
                                        [:fx/executed :fx/failed
                                         :fx/remaining :fx/missing]))))]
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

(def ^:private INTERNAL-FRAME
  #"(epiktetos|clojure|java|jdk|sun|nrepl)\..*")

(defn- root-cause
  "The deepest cause of a throwable — the user's exception, under the
  engine's stage wrappers"
  [^Throwable throwable]
  (if-let [cause (.getCause throwable)]
    (recur cause)
    throwable))

(defn- clean-message
  "The throwable message, stripped of the JVM module clause noise
  (\"... are in module java.base of loader 'bootstrap'\")"
  [^Throwable throwable]
  (when-let [message (.getMessage throwable)]
    (string/replace message #"\s*\([^()]*in (?:unnamed )?module [^()]*\)" "")))

(defn- user-frame
  "The topmost stacktrace frame of the root cause that lives outside
  the engine and the language internals — where the user's code
  failed, in Clojure notation.
  Returns \"ns/fn (file:line)\", or nil when every frame is internal"
  [^Throwable throwable]
  (->> (.getStackTrace (root-cause throwable))
       (filter (fn [^StackTraceElement frame]
                 (not (re-matches INTERNAL-FRAME (.getClassName frame)))))
       first
       ((fn [^StackTraceElement frame]
          (when frame
            (str (clojure.lang.Compiler/demunge (.getClassName frame))
                 " (" (.getFileName frame) ":" (.getLineNumber frame) ")"))))))

(defn- event-signature
  "The event printed for a log line: long or deep arguments are elided
  (... and #) — the full data stays in the error report"
  [event]
  (let [signature (binding [*print-length* 4
                            *print-level*  3]
                    (pr-str event))]
    (if (> (count signature) 56)
      (str (subs signature 0 55) "…")
      signature)))

(defn- failure-sentence
  "One sentence naming the event and the stage where it failed"
  [{:keys [event stage error] :as data}]
  (let [signature (event-signature event)]
    (case stage
      :lookup    (str signature " has no registered handler — a typo?")
      :coeffects (str signature " failed acquiring its coeffect "
                      (:coeffect (ex-data error)) ".")
      :handler   (str signature " blew up in its handler.")
      :effects   (if-let [missing (:fx/missing data)]
                   (str signature " has no registered handler for effect "
                        (string/join ", " missing) " — a typo?")
                   (str signature " failed executing its effect "
                        (first (:fx/failed data)) ".")))))

(defn print-paused!
  "Log the unified loop pause header with its reason.
  Returns nil"
  [reason]
  (println (str "⏸ paused ─ " reason)))

(defn print-resumed!
  "Log the unified loop resume line.
  Returns nil"
  []
  (println "▶ resumed"))

(defn print-retry-succeeded!
  "Log the outcome of a successful retry — the loop resumes.
  event - the retried event vector
  Returns nil"
  [event]
  (println (str "✔ retry succeeded — " (event-signature event))))

(defn print-retry-failed!
  "Log the outcome of a failed retry, right before the next error
  pause block.
  event - the retried event vector
  Returns nil"
  [event]
  (println (str "✖ retry failed — " (event-signature event))))

(defn print-skipped!
  "Log a skipped pending event — the loop resumes without it.
  event - the dropped event vector
  Returns nil"
  [event]
  (println (str "⏭ skipped — " (event-signature event))))

(defn print-aborted!
  "Log the abort decision on the pending event — the engine stops.
  event - the pending event vector
  Returns nil"
  [event]
  (println (str "⏹ aborted — " (event-signature event))))

(defn- print-report!
  [{:keys [stage severity error] :as data}]
  (println "")
  (println "⏸ paused ─ event error ─────────────────────────────────────")
  (println (str "│ " (failure-sentence data)))
  (when-not (or (= :lookup stage) (:fx/missing data))
    (println "│")
    (let [cause (root-cause error)]
      (println (str "│ " (.getSimpleName (class cause)) ": " (clean-message cause)))
      (when-let [frame (user-frame error)]
        (println (str "│ at " frame)))))
  (println "│")
  (if (= :terminal severity)
    (do
      (println "│ Terminal — effects were partially applied, the state can't")
      (println "│ be guaranteed. Inspect, then restart:")
      (println "├─ (abort!)         stop the engine")
      (println "╰─ (error-report)   the full context"))
    (do
      (println (cond
                 (= :lookup stage)
                 "│ Recoverable — the event is kept. Register the handler, then:"

                 (:fx/missing data)
                 "│ Recoverable — nothing was applied. Register the effect, then:"

                 :else
                 "│ Recoverable — nothing was applied. Fix, reload, then:"))
      (println "├─ (retry!)             re-run the event and resume")
      (println "├─ (retry! [:id args])  replace the event, re-run and resume")
      (println "├─ (skip!)              drop the event and resume")
      (println "├─ (abort!)             stop the engine")
      (println "╰─ (error-report)       the full context"))))

(defn- print-dropped!
  "Log a report confined without pause (error pause disabled)"
  [{:keys [stage error] :as data}]
  (println "[epiktetos] Event dropped -"
           (failure-sentence data)
           (if (= :lookup stage)
             ""
             (str "(" (clean-message (root-cause error)) ")"))))

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

(defn stop-engine!
  "Ask the loop to exit — the halt then runs on the loop thread, in
  startup/start-engine!. Callable from any thread; a no-op when no
  engine is running.
  Returns nil"
  []
  (when-let [window (get-in @registrar/registry
                            [::registrar/system-registry :glfw/window])]
    (GLFW/glfwSetWindowShouldClose window true)
    (GLFW/glfwPostEmptyEvent))
  nil)

(defn handle-error!
  "React to a failed event, on the loop thread.

  Error pause disabled: :lookup and :coeffects reports, and the
  missing effect handler case (:fx/missing — nothing was applied), are
  printed and the whole event dropped, while :handler reports and
  effect execution errors are thrown: the session ends as before, the
  report carried by the exception.

  Enabled: print the instructive log, hold the report as the pending
  error and block until a control fn delivers a decision.

  report - the report ex-info (chain-report, lookup-report)
  Returns the decision map: {:action :retry|:skip|:abort,
  :event replacement-or-nil, :paused? true after an actual pause}"
  [report]
  (let [{:keys [stage] :as data} (ex-data report)]
    (if-not (enabled?)
      (if (or (#{:lookup :coeffects} stage)
              (:fx/missing data))
        (do (print-dropped! data)
            {:action :skip :event nil})
        (throw report))
      (do (print-report! data)
          (reset! pause-state {:report report :decision nil})
          (let [decision (await-decision!)]
            (clear-pause-state!)
            (when (= :abort (:action decision))
              (stop-engine!))
            (assoc decision :paused? true))))))

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
