(ns epiktetos.error
  "Event pipeline error confinement (ai-spec/specs/error-spec.md).

  Builds the error report of a failed event, prints the instructive
  log, and drives the error pause: the loop thread blocks in
  handle-error! until a control fn — retry!, skip!, stop!, called
  from the REPL through their epiktetos.core delegations — delivers a
  decision. A development feature, enabled from the engine
  configuration (:gl/engine :error-pause); disabled, the engine
  behaves as before, the report enriching the fatal exception for
  free."
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
                  :iter     (get-in @app-db/db [:core/window :iter]))
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
                (::call-site (meta event))
                (assoc :call-site (::call-site (meta event)))
                (#{:handler :effects} stage)
                (assoc :coeffects (:coeffects context))

                (= :coeffects stage)
                (merge (select-keys (ex-data throwable)
                                    [:coeffect :coeffect/missing]))

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
    (report (cond-> {:event event
                     :stage :lookup
                     :error throwable}
              (::call-site (meta event))
              (assoc :call-site (::call-site (meta event))))
            throwable)))

;; -- Declaration events --------------------------------------------------

(def DECLARATION-EVENTS
  "The engine's last-wins declaration events — one identity, the
  latest registration replaces the previous one — mapped to the pause
  header label of their failures. Their fix always lands in the
  queue: reloading a namespace re-dispatches the top level
  declaration, and retry! adopts it (epiktetos.event)."
  {:epiktetos.event/reg-input      "Shader Input Error"
   :epiktetos.event/reg-p          "Program Error"
   :epiktetos.event/reg-texture    "Texture Error"
   :epiktetos.render.entity/render "Render Error"})

(def ^:private DECLARATION-FORMS
  "The core API form behind each declaration event, for log subjects"
  {:epiktetos.event/reg-input      "reg-input"
   :epiktetos.event/reg-p          "reg-p"
   :epiktetos.event/reg-texture    "reg-texture"
   :epiktetos.render.entity/render "render"})

(defn declaration-event?
  "True for a declaration event (see DECLARATION-EVENTS)"
  [event]
  (contains? DECLARATION-EVENTS (get event 0)))

(defn declaration-identity
  "The last-wins identity of a declaration event: the varname of a
  reg-input, the id of a reg-p, reg-texture or render"
  [event]
  (get-in event [1 0]))

(defn- declaration-signature
  "The subject of a declaration event's log lines: its core API form
  and its identity"
  [event]
  (str (DECLARATION-FORMS (get event 0)) " "
       (pr-str (declaration-identity event))))

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

(defn call-site
  "The topmost user frame of the current stack, as \"file:line\" —
  captured at dispatch time, because events are consumed later on the
  loop thread, where the call site is gone from the stack.
  Returns a string, or nil when no user file frame exists (bare REPL
  input)"
  []
  (->> (.getStackTrace (Thread/currentThread))
       (filter (fn [^StackTraceElement frame]
                 (not (re-matches INTERNAL-FRAME (.getClassName frame)))))
       first
       ((fn [^StackTraceElement frame]
          (when-let [file (some-> frame .getFileName)]
            (when-not (= "NO_SOURCE_FILE" file)
              (str file ":" (.getLineNumber frame))))))))

(defn dev-mode?
  "True when the development error tooling should instrument: the
  error pause is enabled, or no engine runs yet — top level
  declarations dispatch before the configuration is known, and they
  are the development workflow par excellence"
  []
  (or (enabled?)
      (empty? (get @registrar/registry ::registrar/system-registry))))

(defn tag-call-site
  "Attach the dispatch call site to an event's metadata, in dev mode.
  A no-op in production: no stack walk, the event flows untouched.
  event - the reified event vector
  Returns the event, carrying ::call-site meta in dev mode"
  [event]
  (or (when (dev-mode?)
        (when-let [site (call-site)]
          (vary-meta event assoc ::call-site site)))
      event))

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
      :coeffects (if-let [missing (:coeffect/missing data)]
                   (str signature " has no registered coeffect " missing " — a typo?")
                   (str signature " failed acquiring its coeffect "
                        (:coeffect data) "."))
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

(defn- outcome-signature
  "The subject of an outcome line: declaration events name their form
  and identity, other events their elided signature"
  [event]
  (if (declaration-event? event)
    (declaration-signature event)
    (event-signature event)))

(defn print-retry-succeeded!
  "Log the outcome of a successful retry — the loop resumes.
  event - the retried event vector
  Returns nil"
  [event]
  (println (str "✔ retry succeeded — " (outcome-signature event))))

(defn print-retry-failed!
  "Log the outcome of a failed retry, right before the next error
  pause block.
  event - the retried event vector
  Returns nil"
  [event]
  (println (str "✖ retry failed — " (outcome-signature event))))

(defn print-skipped!
  "Log a skipped pending event — the loop resumes without it.
  event - the dropped event vector
  Returns nil"
  [event]
  (println (str "⏭ skipped — " (outcome-signature event))))

(defn print-aborted!
  "Log the abort decision on the pending event — the engine stops.
  event - the pending event vector
  Returns nil"
  [event]
  (println (str "⏹ aborted — " (outcome-signature event))))

(defn- header-title
  "The title of an error pause block: error type, failing identifier,
  and the dispatch call site when one was captured. The source event
  of coeffect and effect errors moves to the block body"
  [{:keys [event stage call-site] :as data}]
  (let [source (get event 0)
        title  (case stage
                 :lookup    (str "Event Lookup Error " source)
                 :handler   (if (declaration-event? event)
                              (str (DECLARATION-EVENTS (get event 0)) " "
                                   (pr-str (declaration-identity event)))
                              (str "Event Error " source))
                 :coeffects (if-let [missing (:coeffect/missing data)]
                              (str "Coeffect Lookup Error " missing)
                              (str "Coeffect Error " (:coeffect data)))
                 :effects   (if-let [missing (:fx/missing data)]
                              (str "Effect Lookup Error "
                                   (string/join ", " missing))
                              (str "Effect Error "
                                   (first (:fx/failed data)))))]
    (cond-> title
      call-site (str " (" call-site ")"))))

(defn- header-rule
  "The block header line: pause icon, title, trailing rule"
  [title]
  (let [prefix (str "⏸  " title " ")]
    (str prefix (apply str (repeat (max 4 (- 78 (count prefix))) "─")))))

(defn- allowed-line
  "One line listing the allowed values of a failed input parameter"
  [allowed]
  (str "Allowed: "
       (if (coll? allowed)
         (string/join ", " (map str (sort-by str allowed)))
         allowed)))

(defn- input-message-lines
  "The error lines of a shader input registration failure, shaped by
  its data: parameter and option errors name the offending value and
  the allowed ones, dry-run errors the validation path or the
  crashing handler's user frame — the header names the input, the
  skip! control carries the fix flow"
  [{:keys [error]}]
  (let [{:keys [input/varname input/unknown-options
                option value allowed requires step known-steps path]
         :as data} (ex-data error)
        description (:error data)]
    (-> (cond
          unknown-options
          [(str "Unknown option" (when (next unknown-options) "s") " "
                (string/join ", " unknown-options))
           (allowed-line allowed)]

          option
          (cond-> [(str "Invalid " option " "
                        (pr-str value))]
            allowed  (conj (allowed-line allowed))
            requires (conj (str "Requires " requires)))

          known-steps
          [(str "Unknown render step " step)
           (allowed-line known-steps)
           "Custom steps must be registered with reg-steps! before reg-input"]

          (contains? data :ssbo/capacity)
          [(str "Invalid :ssbo/capacity "
                (pr-str (:ssbo/capacity data)))
           "Allowed: a positive integer, in elements"]

          description
          [(str "Invalid value" (when path (str " at " path)) ": "
                (if (keyword? description) (name description) description))]

          (contains? data :handler)
          [(clean-message error)
           (str "Got: " (pr-str (:handler data)))]

          (:input/dry-run data)
          (let [cause (root-cause error)
                frame (user-frame error)]
            (cond-> [(str "Input \"" varname "\" — "
                          (.getSimpleName (class cause)) ": "
                          (clean-message cause))]
              frame (conj (str "at " frame))))

          :else
          [(clean-message error)
           (str "Got: " (pr-str varname))]))))

(declare message-body-lines)

(defn- message-lines
  "The error lines of a pause block: the source event of coeffect and
  effect errors first (their header names the failing key), then a
  synthetic message for lookup errors, the input lines for a shader
  input registration failure, the root cause and the topmost user
  frame otherwise"
  [{:keys [stage event] :as data}]
  (cond->> (message-body-lines data)
    (#{:coeffects :effects} stage)
    (into [(str "event " (event-signature event))])))

(defn- message-body-lines
  [{:keys [stage error] :as data}]
  (cond
    (= :lookup stage)
    [(str "no event " (get-in data [:event 0]) " is registered")]

    (:coeffect/missing data)
    [(str "no coeffect " (:coeffect/missing data) " is registered")]

    (:fx/missing data)
    (mapv #(str "no effect " % " is registered") (:fx/missing data))

    (:input/varname (ex-data error))
    (input-message-lines data)

    :else
    (let [cause (root-cause error)
          frame (user-frame error)]
      (cond-> [(str (.getSimpleName (class cause)) ": " (clean-message cause))]
        frame (conj (str "at " frame))))))

(defn- tip-lines
  "The registration forms fixing a lookup error, or nil when no tip
  applies"
  [{:keys [stage] :as data}]
  (cond
    (= :lookup stage)
    [(str "(reg-event " (get-in data [:event 0]) " handler-fn)")]

    (:coeffect/missing data)
    [(str "(reg-cofx " (:coeffect/missing data) " handler-fn)")]

    (:fx/missing data)
    (mapv #(str "(reg-fx " % " handler-fn)") (:fx/missing data))))

(defn- controls-of
  "The controls advertised by a pause block — only the pertinent
  ones: a terminal pause offers inspection and stop, a declaration
  failure offers the fix-reload-retry! flow (skip would leave the
  engine state degraded, retry! adopts the reloaded declaration),
  any other recoverable pause the full set.
  Returns a vector of [form description] pairs"
  [{:keys [severity event]}]
  (cond
    (= :terminal severity)
    [["(epiktetos.core/stop!)"       "stop the engine"]
     ["(epiktetos.dev/error-report)" "the full context"]]

    (declaration-event? event)
    [["(epiktetos.dev/retry!)"       "re-run the declaration — picks up your reloaded fix"]
     ["(epiktetos.core/stop!)"       "stop the engine"]
     ["(epiktetos.dev/error-report)" "the full context"]]

    :else
    [["(epiktetos.dev/retry!)"            "re-run the event and resume"]
     ["(epiktetos.dev/retry! [:id args])" "replace the event, re-run and resume"]
     ["(epiktetos.dev/skip!)"             "drop the event and resume"]
     ["(epiktetos.core/stop!)"            "stop the engine"]
     ["(epiktetos.dev/error-report)"      "the full context"]]))

(defn- print-controls!
  "Print the gutter branches of a pause block's controls, forms
  aligned"
  [controls]
  (let [width (apply max (map (comp count first) controls))]
    (doseq [[index [form description]] (map-indexed vector controls)
            :let [gutter (if (= index (dec (count controls))) "╰─ " "├─ ")]]
      (println (str gutter (format (str "%-" (+ width 2) "s") form)
                    description)))))

(defn- print-report!
  [data]
  (println "")
  (println (header-rule (header-title data)))
  (println "│")
  (doseq [line (message-lines data)]
    (println (str "│  " line)))
  (println "│")
  (when-let [tip (tip-lines data)]
    (println "│  Register it with:")
    (doseq [line tip]
      (println (str "│  " line)))
    (println "│"))
  (println "│  Debug with:")
  (print-controls! (controls-of data)))

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

(defn- window-should-close?
  "True when the window was asked to close — the close button or
  stop! during a pause"
  []
  (when-let [window-id (get-in @registrar/registry
                               [::registrar/system-registry :glfw/window :id])]
    (GLFW/glfwWindowShouldClose window-id)))

(defn- await-decision!
  "Block the calling thread — the loop thread — until a control fn
  delivers a decision. A window close request counts as an abort: the
  close button stays honest during a pause.
  Returns the decision map: {:action :retry|:skip|:abort, :event replacement-or-nil}"
  []
  (loop []
    (cond
      (:decision @pause-state) (:decision @pause-state)
      (window-should-close?)   {:action :abort :event nil}
      :else                    (do (pump-os-events!)
                                   (recur)))))

(defn stop-engine!
  "Ask the loop to exit — the halt then runs on the loop thread, in
  startup/start-engine!. Callable from any thread; a no-op when no
  engine is running.
  Returns nil"
  []
  (when-let [window-id (get-in @registrar/registry
                               [::registrar/system-registry :glfw/window :id])]
    (GLFW/glfwSetWindowShouldClose window-id true)
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
          (println "            in pure code. Inspect (epiktetos.dev/error-report), then")
          (println "            (epiktetos.core/stop!)."))

      (and (= :skip action)
           (declaration-event? (:event (ex-data report))))
      (do (println "[epiktetos] A declaration leaves no honest skip: its absence degrades")
          (println "            the engine state — unfed input, missing program, texture")
          (println "            or entity. Fix it, reload your namespace, then")
          (println "            (epiktetos.dev/retry!) — or (epiktetos.core/stop!)."))

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

(defn stop!
  "Stop the engine — the clean halt. During an error pause, delivers
  the abort decision to the paused loop thread — the only control of
  a terminal pause; otherwise asks the loop to exit at the end of its
  iteration. Callable from any thread; a no-op when no engine runs.
  Returns nil"
  []
  (if (paused?)
    (deliver-decision! :abort nil)
    (stop-engine!)))
