(ns epiktetos.shader-input.registration
  (:require [clojure.string :as string]
            [epiktetos.opengl.introspection :as introspect]
            [epiktetos.registrar :as registrar]
            [epiktetos.render.step :as render-step]
            [epiktetos.shader-input.buffer :as buffer]
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
      (register! (buffer/ensure-block-buffer! resource block))
      (when-not (registrar/lookup-input (:varname block))
        (println "[epiktetos] No input registered for block" (:varname block))))

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
        (buffer/forget-input-value! varname)
        (when-not (registrar/lookup-input varname)
          (println "[epiktetos] No input registered for uniform" varname))))
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

(defn register-input-handler!
  "Registers a user input handler for a bindable shader input, and
   reconciles the capacity of its GPU buffer when the matching program
   input is already registered.
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
      registry)))
