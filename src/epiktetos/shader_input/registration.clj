(ns epiktetos.shader-input.registration
  (:require [epiktetos.opengl.introspection :as introspect]
            [epiktetos.registrar :as registrar]
            [epiktetos.render.step :as render-step]
            [epiktetos.shader-input.buffer :as buffer])
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
    (let [registry (registrar/register-input! input)]
      (buffer/ensure-block-capacity! varname)
      registry)))
