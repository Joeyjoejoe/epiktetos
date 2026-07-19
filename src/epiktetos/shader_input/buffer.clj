(ns epiktetos.shader-input.buffer
  (:require [epiktetos.opengl.buffer :as gl-buffer]
            [epiktetos.registrar :as registrar]
            [epiktetos.shader-input.data :as data]
            [epiktetos.shader-input.types :as types])
  (:import (org.lwjgl BufferUtils)
           (org.lwjgl.opengl GL11 GL15 GL20 GL30 GL31 GL41 GL42 GL43)))

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
  (let [{:keys [buffer-binding varname]} program-input
        {:keys [binding-point resource]} (registrar/lookup-program-input varname)]
    (cond
      (= :uniform resource)
      (throw (ex-info "Block name already registered as a plain uniform"
                      {:program  (:program program-input)
                       :resource varname}))

      (nil? binding-point)
      program-input

      (= buffer-binding 0)
      (assoc program-input :alloc :registrar :buffer-binding binding-point)

      (= buffer-binding binding-point)
      (assoc program-input :alloc :valid)

      :else
      (throw (ex-info "Binding conflict"
                      {:program (:program program-input)
                       :resource varname
                       :cause (str varname " can't be bound to " buffer-binding ", already registered at binding point " binding-point ".")})))))

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

(defn forget-input-value!
  "Drops the cached last-written value of an input, so the next
   update writes its (recreated or relinked) target again.
   varname - string, input variable name
   Returns nil."
  [varname]
  (swap! registrar/render-state
         update ::registrar/input-values dissoc varname)
  nil)

(defn- unchanged-value?
  "True when a handler output is member-per-member identical to the
   last value written to the block buffer: the buffer content is
   already up to date and the write can be skipped. Members are
   compared with identical?, so handlers benefit by returning cached
   immutable structures for static members.
   prev  - map, last written value, or nil
   value - map, handler output"
  [prev value]
  (and (map? prev)
       (map? value)
       (= (count prev) (count value))
       (every? (fn [[k v]] (identical? v (get prev k))) value)))

(defn- block-capacity
  "Element capacity of a block's runtime array, read from the
   registered input's :ssbo/capacity option.
   resource - keyword, :ubo or :ssbo
   schema   - map {field-name schema}
   varname  - string, block variable name
   Returns a positive integer, 1 by default. Warns and ignores the
   option when the block has no runtime array."
  [resource schema varname]
  (let [capacity (:ssbo/capacity (registrar/lookup-input varname))]
    (cond
      (nil? capacity)
      1

      (nil? (types/runtime-array schema))
      (do (println "[epiktetos] Ignoring :ssbo/capacity for" varname ":"
                   (name resource) "block without runtime array")
          1)

      :else capacity)))

(defn- block-buffer-size
  "Byte size of a block's GPU buffer for a given capacity.
   schema   - map {field-name schema}
   size     - int, introspected buffer-data-size (one runtime element)
   capacity - pos int, runtime array capacity
   Returns size unchanged when the schema has no runtime array."
  [schema size capacity]
  (if-let [[_ fschema] (types/runtime-array schema)]
    (+ size (* (:stride fschema) (dec capacity)))
    size))

(defn- create-block-buffer!
  "Creates a zeroed GPU buffer for a block and binds it to its
   allocated binding point.
   target        - int, GL buffer target
   binding-point - int, binding point index
   size          - int, buffer byte size
   Returns the GL buffer id."
  [target binding-point size]
  (let [buffer-id (gl-buffer/create-storage-buffer!
                    (BufferUtils/createByteBuffer size)
                    [:dynamic])]
    (GL30/glBindBufferBase target binding-point buffer-id)
    buffer-id))

(defn ensure-block-buffer!
  "Adds :buffer-id, :schema and :capacity to block, reusing the
   registered buffer when varname is already known with the same
   capacity, creating and binding a new one otherwise (the previous
   buffer, if any, is deleted). Throws when a block with the same
   varname is already registered with a different structure.
   resource - keyword, :ubo or :ssbo
   block    - map, introspected block
   Returns the block map with :buffer-id, :schema and :capacity."
  [resource block]
  (let [{:keys [varname members buffer-data-size buffer-binding]} block
        existing (registrar/lookup-program-input varname)]
    (when (and existing (not= members (:members existing)))
      (throw (ex-info "Block structure mismatch"
                      {:resource     resource
                       :varname      varname
                       :registered   (:members existing)
                       :introspected members})))
    (let [schema   (or (:schema existing) (types/members->schema varname members))
          capacity (block-capacity resource schema varname)
          schema   (types/set-capacity schema capacity)]
      (if (and existing (= capacity (:capacity existing)))
        (assoc block
               :buffer-id (:buffer-id existing)
               :schema    (:schema existing)
               :capacity  capacity)
        (let [size      (block-buffer-size schema buffer-data-size capacity)
              buffer-id (create-block-buffer! (BLOCK-BUFFER-TARGET resource)
                                              buffer-binding size)]
          (when existing
            (GL15/glDeleteBuffers (int (:buffer-id existing))))
          (forget-input-value! varname)
          (assoc block
                 :buffer-id buffer-id
                 :schema    schema
                 :capacity  capacity))))))

(defn ensure-block-capacity!
  "Recreates the GPU buffer of a registered program input when the
   registered input's :ssbo/capacity no longer matches its capacity:
   new zeroed buffer bound to the same binding point, previous buffer
   deleted, registry entry replaced. No-op when varname matches no
   program input or the capacity is unchanged.
   varname - string, block variable name
   Returns nil."
  [varname]
  (when-let [existing (registrar/lookup-program-input varname)]
    (when (BLOCK-BUFFER-TARGET (:resource existing))
      (let [{:keys [resource schema binding-point buffer-data-size]} existing
            capacity (block-capacity resource schema varname)]
      (when (not= capacity (:capacity existing))
        (let [schema    (types/set-capacity schema capacity)
              size      (block-buffer-size schema buffer-data-size capacity)
              buffer-id (create-block-buffer! (BLOCK-BUFFER-TARGET resource)
                                              binding-point size)]
          (GL15/glDeleteBuffers (int (:buffer-id existing)))
          (forget-input-value! varname)
          (registrar/register-program-input!
            resource
            (assoc existing
                   :buffer-binding binding-point
                   :buffer-id      buffer-id
                   :schema         schema
                   :capacity       capacity)))))))
  nil)

(defn inputs-by-step
  "Groups registered input definitions by render step.
   input-registry - map {varname input}
   Returns a map {step {varname input}}."
  [input-registry]
  (reduce-kv (fn [m varname input]
               (assoc-in m [(:step input) varname] input))
             {}
             input-registry))

(defn- unchanged-uniform-value?
  "True when a plain uniform handler output equals the last written
   value: identical for cached structures, member-per-member for
   maps, by value for numbers and booleans.
   prev  - last written value, or nil
   value - handler output"
  [prev value]
  (or (identical? prev value)
      (and (some? prev)
           (or (number? value) (boolean? value))
           (= prev value))
      (and (map? prev) (map? value)
           (unchanged-value? prev value))))

(defn- write-uniform-value!
  "Writes a validated plain uniform value to a program, at the
   locations of its schema.
   program-id - int, GL program id
   schema     - map, uniform schema node (see types/uniforms->schema)
   value      - the validated handler value
   Returns nil."
  [program-id schema value]
  (case (:kind schema)
    :scalar
    (let [{:keys [integer? double?]} (:type schema)
          location (:location schema)
          value    (if (boolean? value) (if value 1 0) value)]
      (cond
        double?  (GL41/glProgramUniform1d program-id location (double value))
        integer? (GL41/glProgramUniform1i program-id location (int value))
        :else    (GL41/glProgramUniform1f program-id location (float value))))

    :vector
    (let [{:keys [integer? double? size]} (:type schema)
          location (:location schema)
          scalars  (mapv #(if (boolean? %) (if % 1 0) %) value)]
      (cond
        double?
        (let [array (double-array scalars)]
          (case (int size)
            2 (GL41/glProgramUniform2dv program-id location array)
            3 (GL41/glProgramUniform3dv program-id location array)
            4 (GL41/glProgramUniform4dv program-id location array)))

        integer?
        (let [array (int-array scalars)]
          (case (int size)
            2 (GL41/glProgramUniform2iv program-id location array)
            3 (GL41/glProgramUniform3iv program-id location array)
            4 (GL41/glProgramUniform4iv program-id location array)))

        :else
        (let [array (float-array (map float scalars))]
          (case (int size)
            2 (GL41/glProgramUniform2fv program-id location array)
            3 (GL41/glProgramUniform3fv program-id location array)
            4 (GL41/glProgramUniform4fv program-id location array)))))

    :matrix
    (let [{:keys [glsl-name]} (:type schema)
          location (:location schema)
          array    (float-array (map float value))]
      (case glsl-name
        :mat2 (GL41/glProgramUniformMatrix2fv program-id location false array)
        :mat3 (GL41/glProgramUniformMatrix3fv program-id location false array)
        :mat4 (GL41/glProgramUniformMatrix4fv program-id location false array)
        (throw (ex-info "Unsupported plain uniform matrix type"
                        {:glsl-name glsl-name}))))

    :struct
    (doseq [[fname fschema] (:fields schema)]
      (write-uniform-value! program-id fschema (get value fname)))

    :array
    (doseq [[element-schema element] (map vector (:elements schema) value)]
      (write-uniform-value! program-id element-schema element)))
  nil)

(defn- update-uniform!
  "Executes one plain uniform handler and writes its output to every
   program declaring the uniform. The write is skipped when the
   output equals the last written value; the cache is dropped when a
   declaring program is (re)registered, so relinked programs are
   rewritten. Validation runs before any write: invalid data throws
   with the input varname added.
   db            - map, application state of the current frame
   program-input - map, registered uniform with :shape and :programs
   input         - map, input definition with :handler
   step-value    - the current step value, passed to the handler
   Returns nil."
  [db program-input input step-value]
  (let [{:keys [varname handler]} input
        value (handler db step-value)
        prev  (get-in @registrar/render-state
                      [::registrar/input-values varname])]
    (when-not (unchanged-uniform-value? prev value)
      (try
        (data/validate-uniform (:shape program-input) value)
        (catch clojure.lang.ExceptionInfo e
          (throw (ex-info (ex-message e)
                          (assoc (ex-data e) :varname varname)))))
      (doseq [[_ {:keys [program-id schema]}] (:programs program-input)]
        (write-uniform-value! program-id schema value))
      (swap! registrar/render-state
             assoc-in [::registrar/input-values varname] value))
    nil))

(defn- update-block!
  "Executes one input handler and writes its output to the block
   buffer. The write is skipped when the output is member-per-member
   identical to the last written value (see unchanged-value?); the
   cache is dropped whenever the buffer is recreated. Validation runs
   before the GPU write: invalid data throws with the input varname
   added, and leaves the buffer on its last valid content.
   db            - map, application state of the current frame
   program-input - map, registered block with :schema and :buffer-id
   input         - map, input definition with :handler
   step-value    - the current step value, passed to the handler
   Returns nil."
  [db program-input input step-value]
  (let [{:keys [varname handler]} input
        {:keys [schema buffer-id buffer-data-size]} program-input
        value (handler db step-value)
        prev  (get-in @registrar/render-state
                      [::registrar/input-values varname])]
    (when-not (unchanged-value? prev value)
      (try
        (data/validate schema value)
        (catch clojure.lang.ExceptionInfo e
          (throw (ex-info (ex-message e)
                          (assoc (ex-data e) :varname varname)))))
      (let [size (data/block-size schema value buffer-data-size)]
        (gl-buffer/upload! buffer-id (data/serialize schema value size)))
      (swap! registrar/render-state
             assoc-in [::registrar/input-values varname] value))
    nil))

(defn- update-input!
  "Executes one input handler and writes its output to its GPU
   target: the block buffer for :ubo and :ssbo inputs, the uniform
   locations of every declaring program for :uniform inputs.
   db            - map, application state of the current frame
   program-input - map, registered program input
   input         - map, input definition with :handler
   step-value    - the current step value, passed to the handler
   Returns nil."
  [db program-input input step-value]
  (if (= :uniform (:resource program-input))
    (update-uniform! db program-input input step-value)
    (update-block! db program-input input step-value)))

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
