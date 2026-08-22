(ns epiktetos.shader-input.registration
  (:require [clojure.string :as string]
            [epiktetos.opengl.introspection :as introspect]
            [epiktetos.registrar :as registrar]
            [epiktetos.render.step :as render-step]
            [epiktetos.shader-input.buffer :as buffer]
            [epiktetos.shader-input.data :as data]
            [epiktetos.shader-input.texture :as texture]
            [epiktetos.shader-input.types :as types])
  (:import (org.lwjgl.opengl GL31 GL43)))

(defn- setup-block-bindings!
  "Allocates and applies binding points for a program's interface blocks.
   program     - program map with :id
   resource    - keyword, :ubo or :ssbo
   interface   - introspection interface keyword
   bind!       - function (fn [program-id interface-index binding-point])
   register!   - function (fn [block]), registers the block in the registry
   Returns program with block varnames added to :inputs."
  [program resource interface bind! register!]
  (let [program-id (:id program)
        blocks     (try (->> interface
                             (introspect/resource-properties program-id)
                             (buffer/allocate-binding-points resource))
                        (catch clojure.lang.ExceptionInfo e
                          (throw (ex-info (ex-message e)
                                          (assoc (ex-data e) :in-program program-id)))))]

    (doseq [{:keys [interface-index buffer-binding]
             :as   block} blocks]
      (bind! program-id interface-index buffer-binding)
      (register! (buffer/ensure-block-buffer! resource block)))

    (update program :inputs into (map :varname blocks))))

(defn setup-ubos!
  "Auto allocate binding points of program ubos"
  [program]
  (setup-block-bindings! program :ubo ::introspect/uniform-block
                         (fn [program-id idx binding-point]
                           (GL31/glUniformBlockBinding program-id idx binding-point))
                         #(registrar/register-program-input! :ubo %)))

(defn setup-ssbos!
  "Auto allocate binding points of program ssbos"
  [program]
  (setup-block-bindings! program :ssbo ::introspect/shader-storage-block
                         (fn [program-id idx binding-point]
                           (GL43/glShaderStorageBlockBinding program-id idx binding-point))
                         #(registrar/register-program-input! :ssbo %)))

(defn- default-block-uniform?
  "True for an introspected uniform belonging to the default block,
   built-ins excluded.
   uniform - map, introspected uniform properties"
  [uniform]
  (and (= -1 (:block-index uniform))
       (not (string/starts-with? (:varname uniform) "gl_"))))

(defn setup-uniforms!
  "Introspects the default-block uniforms of a program and registers
   their fan-out targets: sampler uniforms become texture inputs
   (unit allocated, unit index written to the program), transparent
   uniforms become plain uniform inputs (location schemas shared with
   every other program declaring the name); other opaque types are
   skipped. The written-value cache of each plain uniform is dropped,
   so relinked programs are rewritten at the next step execution.
   Throws when a name is already registered as another input kind or
   with a different shape.
   program   - program map with :id
   program-k - keyword, program id in the registry
   Returns program with uniform and sampler varnames added to :inputs."
  [program program-k]
  (registrar/forget-program-uniforms! program-k)
  (let [program-id (:id program)
        uniforms   (->> (introspect/resource-properties program-id
                                                        ::introspect/uniform)
                        (filter default-block-uniform?))
        samplers   (filter texture/sampler-kind uniforms)
        schema     (types/uniforms->schema (filter :type uniforms))]
    (doseq [sampler samplers]
      (texture/setup-sampler-uniform! program-id program-k sampler))
    (doseq [[varname node] schema]
      (let [shape    (types/uniform-shape node)
            existing (registrar/lookup-program-input varname)]
        (when (and existing (not= :uniform (:resource existing)))
          (throw (ex-info "Uniform name already registered as a block"
                          {:varname    varname
                           :resource   (:resource existing)
                           :in-program program-id})))
        (when (and existing (not= shape (:shape existing)))
          (throw (ex-info "Uniform shape mismatch"
                          {:varname      varname
                           :registered   (:shape existing)
                           :introspected shape
                           :in-program   program-id})))
        (registrar/register-program-uniform! varname program-k
                                             {:program-id program-id
                                              :schema     node
                                              :shape      shape})
        (buffer/forget-input-value! varname)))
    (update program :inputs into (concat (keys schema)
                                         (map :varname samplers)))))

(defn- assert-known-step!
  "Validates that step is a core render step or a custom step already
   registered with reg-steps!. Throws ex-info otherwise.
   varname - string, input variable name, used as error context
   step    - keyword, render step to validate
   Returns nil."
  [varname step]
  (let [custom-steps (get @registrar/render-state
                          ::registrar/custom-step-order [])
        known-steps  (into render-step/CORE-STEPS custom-steps)]
    (when-not (contains? known-steps step)
      (throw (ex-info "Unknown render step"
                      {:varname     varname
                       :step        step
                       :known-steps known-steps
                       :cause "Custom steps must be registered with reg-steps! before reg-input."})))))

(defn- assert-capacity!
  "Validates the :ssbo/capacity option when present. Throws ex-info
   unless it is a positive integer.
   varname - string, input variable name
   options - map, reg-input options"
  [varname options]
  (let [capacity (:ssbo/capacity options)]
    (when (and (some? capacity)
               (not (and (integer? capacity) (pos? capacity))))
      (throw (ex-info "Invalid :ssbo/capacity"
                      {:varname       varname
                       :ssbo/capacity capacity
                       :cause ":ssbo/capacity must be a positive integer."})))))

(defn- dry-run!
  "Runs a :step/frame input handler against the current db and
   validates its output against the registered program input, so a
   bad handler bursts at registration — in the event pipeline, where
   the error pause is recoverable — instead of degrading at render
   time.
   program-input - map, registered program input
   varname       - string, input variable name
   handler       - function (fn [db step-value])
   options       - map, reg-input options
   db            - map, application state
   Returns nil."
  [program-input varname handler options db]
  (let [step-value (get-in db [:core/window :iter] 0)
        value      (try (handler db step-value)
                        (catch Throwable t
                          (throw (ex-info "Input handler failed its registration dry-run"
                                          {:input/varname varname
                                           :input/dry-run true}
                                          t))))]
    (try
      (case (:resource program-input)
        :uniform (data/validate-uniform (:shape program-input) value)
        :texture (when-not (keyword? value)
                   (throw (ex-info "Invalid shader input data"
                                   {:error "a texture input handler must return a texture id keyword"
                                    :value value})))
        (let [capacity (:ssbo/capacity options)
              schema   (cond-> (:schema program-input)
                         capacity (types/set-capacity capacity))]
          (data/validate schema value)))
      (catch clojure.lang.ExceptionInfo e
        (throw (ex-info (ex-message e)
                        (merge (ex-data e)
                               {:input/varname varname
                                :input/dry-run true})
                        e))))
    nil))

(defn validate-registration!
  "Validates a reg-input registration from the event handler — the
   pure stage, where an error pauses recoverably: static parameters
   first, then the dry-run of :step/frame handlers against the
   registered program input. Silent when no program declares the
   varname yet (registration order stays free) and for other steps
   (no honest step-value exists before rendering — they are confined
   at render time instead).
   program-input - map, registered program input, or nil
   varname       - string, input variable name
   handler       - function (fn [db step-value])
   options       - map, reg-input options
   db            - map, application state
   Returns nil."
  [program-input varname handler options db]
  (when-not (string? varname)
    (throw (ex-info "Input varname must be a string, the exact GLSL variable name"
                    {:input/varname varname})))
  (when-not (ifn? handler)
    (throw (ex-info "Input handler must be a function of [db step-value]"
                    {:input/varname varname
                     :handler       handler})))
  (let [known-options (into #{:step :ssbo/capacity} texture/SAMPLER-OPTION-KEYS)]
    (when-let [unknown (seq (remove known-options (keys options)))]
      (throw (ex-info "Unknown reg-input option"
                      {:input/varname         varname
                       :input/unknown-options (vec unknown)
                       :allowed               known-options}))))
  (try
    (assert-known-step! varname (get options :step :step/frame))
    (assert-capacity! varname options)
    (texture/validate-sampler-options varname options)
    (catch clojure.lang.ExceptionInfo e
      (throw (ex-info (ex-message e)
                      (assoc (ex-data e) :input/varname varname)
                      e))))
  (when (and program-input
             (= :step/frame (get options :step :step/frame)))
    (dry-run! program-input varname handler options db))
  nil)

(defn register-input-handler!
  "Registers a user input handler for a bindable shader input,
   reconciles the capacity of its GPU buffer when the matching program
   input is already registered, and rearms the degraded-input warning
   of the varname.
   varname - string, GLSL block variable name
   handler - function (fn [db step-value]), produces the buffer data
   options - map, :step defaults to :step/frame and must be a core
             render step or a custom step registered with reg-steps!,
             :ssbo/capacity must be a positive integer when present
   Returns the updated registry value."
  [varname handler options]
  (let [{:keys [step] :or {step :step/frame}} options
        input (merge options {:varname varname :handler handler :step step})]
    (assert-known-step! varname step)
    (assert-capacity! varname options)
    (texture/validate-sampler-options varname options)
    (let [registry (registrar/register-input! input)]
      (buffer/ensure-block-capacity! varname)
      (buffer/rearm-input! varname)
      registry)))
