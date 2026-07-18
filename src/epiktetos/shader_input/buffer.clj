(ns epiktetos.shader-input.buffer
  (:require [epiktetos.opengl.buffer :as gl-buffer]
            [epiktetos.registrar :as registrar]
            [epiktetos.shader-input.data :as data]
            [epiktetos.shader-input.types :as types])
  (:import (org.lwjgl BufferUtils)
           (org.lwjgl.opengl GL11 GL20 GL30 GL31 GL42 GL43)))

(defonce ^:private RESOURCE-BINDING-MAX
  {:ubo            GL31/GL_MAX_UNIFORM_BUFFER_BINDINGS
   :ssbo           GL43/GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS
   :atomic-counter GL42/GL_MAX_ATOMIC_COUNTER_BUFFER_BINDINGS
   :texture-unit   GL20/GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS
   :image-unit     GL42/GL_MAX_IMAGE_UNITS})

(defn- binding-points
  "Returns the set of all valid binding points for a resource.
   Binding point 0 is reserved for implicit binding detection and
   never returned.
   resource - keyword, e.g. :ubo, :ssbo"
  [resource]
  (->> resource
      RESOURCE-BINDING-MAX
      GL11/glGetInteger
      (range 1)
      set))

(defn- allocate-known-binding
  "Allocates the binding point already registered for a program input,
   when its varname is known to the registrar.
   program-input - map, introspected program input
   Returns program-input unchanged when the varname is unknown, tagged
   with :alloc and its registered binding point otherwise. Throws on a
   conflicting explicit binding."
  [program-input]
  (let [{:keys [buffer-binding varname]} program-input]

    (if-let [{:keys [binding-point]} (registrar/lookup-program-input varname)]
      (cond
        (= buffer-binding 0)             (assoc program-input :alloc :registrar :buffer-binding binding-point)
        (= buffer-binding binding-point) (assoc program-input :alloc :valid)
        :else (throw (ex-info "Binding conflict"
                              {:program (:program program-input)
                               :resource varname
                               :cause (str varname " can't be bound to " buffer-binding ", already registered at binding point " binding-point ".")})))
      program-input)))

(defn- allocate-known-bindings
  "Allocates registered binding points across program inputs.
   program-inputs - coll of maps, introspected program inputs
   Returns the program inputs, allocated ones tagged with :alloc."
  [program-inputs]
  (map allocate-known-binding program-inputs))

(defn- tag-explicit-binding
  "Tags a program input carrying an explicit GLSL binding.
   program-input - map, introspected program input
   Returns program-input, tagged with :alloc :explicit when its
   binding is non-zero."
  [program-input]
  (if (> (:buffer-binding program-input) 0)
    (assoc program-input :alloc :explicit)
    program-input))

(defn- tag-explicit-bindings
  "Tags program inputs carrying an explicit GLSL binding.
   program-inputs - coll of maps, introspected program inputs
   Returns the program inputs."
  [program-inputs]
  (map tag-explicit-binding program-inputs))

(defn- detect-binding-conflict
  "Throws when two allocated program inputs share a binding point.
   program-inputs - coll of maps, introspected program inputs
   Returns the program inputs unchanged."
  [program-inputs]
  (if-let [conflicts (->> program-inputs
                          (filter :alloc)
                          (group-by :buffer-binding)
                          (vals)
                          (filter #(> (count %) 1))
                          (mapcat identity)
                          not-empty)]
           (throw (ex-info "Binding conflict"
                           {:conflicts conflicts}))
           program-inputs))

(defn- allocate-new-bindings
  "Allocates free binding points to program inputs left untagged by
   the previous allocation passes.
   resource       - keyword, e.g. :ubo, :ssbo
   program-inputs - coll of maps, introspected program inputs
   Returns the program inputs, all tagged with :alloc. Throws when no
   binding point remains."
  [resource program-inputs]
  (let [bindings (binding-points resource)

        allocated-bindings (->> (registrar/lookup-resource-inputs resource)
                                (map :binding-point))

        free-bindings (sort (remove (set allocated-bindings) bindings))]

    (first
      (reduce
        (fn [[acc remaining-bindings] program-input]
          (if (:alloc program-input)
            [(conj acc program-input) remaining-bindings]
            (if-let [binding-point (first remaining-bindings)]
              [(conj acc (assoc program-input :buffer-binding binding-point :alloc :auto))
               (rest remaining-bindings)]
              (throw (ex-info "No more binding points available"
                              {:element program-input})))))
        [[] free-bindings]
        program-inputs))))

(defn allocate-binding-points
  "Allocates a binding point to every introspected program input:
   explicit GLSL bindings and varnames known to the registrar keep
   theirs, the others receive a free one.
   resource       - keyword, e.g. :ubo, :ssbo
   program-inputs - coll of maps, introspected program inputs
   Returns the program inputs, all tagged with :alloc."
  [resource program-inputs]
  (->> program-inputs
       tag-explicit-bindings
       allocate-known-bindings
       detect-binding-conflict
       (allocate-new-bindings resource)))

(defonce ^:private BLOCK-BUFFER-TARGET
  {:ubo  GL31/GL_UNIFORM_BUFFER
   :ssbo GL43/GL_SHADER_STORAGE_BUFFER})

(defn- create-block-buffer!
  "Creates a zeroed GPU buffer for a block and binds it to its
   allocated binding point.
   target - int, GL buffer target
   block  - map, introspected block with :buffer-data-size and :buffer-binding
   Returns the GL buffer id."
  [target {:keys [buffer-data-size buffer-binding]}]
  (let [buffer-id (gl-buffer/create-storage-buffer!
                    (BufferUtils/createByteBuffer buffer-data-size)
                    [:dynamic])]
    (GL30/glBindBufferBase target buffer-binding buffer-id)
    buffer-id))

(defn ensure-block-buffer!
  "Adds :buffer-id and :schema to block, reusing the registered buffer
   when varname is already known, creating and binding a new one
   otherwise. Throws when a block with the same varname is already
   registered with a different structure.
   resource - keyword, :ubo or :ssbo
   block    - map, introspected block
   Returns the block map with :buffer-id and :schema."
  [resource block]
  (let [{:keys [varname members]} block]
    (if-let [existing (registrar/lookup-program-input varname)]
      (if (= members (:members existing))
        (assoc block
               :buffer-id (:buffer-id existing)
               :schema    (:schema existing))
        (throw (ex-info "Block structure mismatch"
                        {:resource     resource
                         :varname      varname
                         :registered   (:members existing)
                         :introspected members})))
      (assoc block
             :buffer-id (create-block-buffer! (BLOCK-BUFFER-TARGET resource) block)
             :schema    (types/members->schema varname members)))))

(defn inputs-by-step
  "Groups registered input definitions by render step.
   input-registry - map {varname input}
   Returns a map {step {varname input}}."
  [input-registry]
  (reduce-kv (fn [m varname input]
               (assoc-in m [(:step input) varname] input))
             {}
             input-registry))

(defn- update-input!
  "Executes one input handler and writes its output to the block
   buffer. Validation runs before the GPU write: invalid data throws
   with the input varname added, and leaves the buffer on its last
   valid content.
   db            - map, application state of the current frame
   program-input - map, registered block with :schema and :buffer-id
   input         - map, input definition with :handler
   step-value    - the current step value, passed to the handler
   Returns nil."
  [db program-input input step-value]
  (let [{:keys [varname handler]} input
        {:keys [schema buffer-id buffer-data-size]} program-input
        value (handler db step-value)]
    (try
      (data/validate schema value)
      (catch clojure.lang.ExceptionInfo e
        (throw (ex-info (ex-message e)
                        (assoc (ex-data e) :varname varname)))))
    (gl-buffer/upload! buffer-id (data/serialize schema value buffer-data-size))
    nil))

(defn update-inputs!
  "Executes the input handlers registered on a render step whose
   varname matches a registered program input, and writes their output
   to the GPU block buffers. Inputs with no matching program input are
   silently skipped.
   db             - map, application state of the current frame
   program-inputs - map {varname program-input}, registered blocks
   inputs         - map {varname input}, input definitions of the step
   step-value     - the current step value, passed to handlers
   Returns nil."
  [db program-inputs inputs step-value]
  (doseq [[varname input] inputs
          :let  [program-input (get program-inputs varname)]
          :when program-input]
    (update-input! db program-input input step-value)))
