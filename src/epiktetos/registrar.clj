(ns epiktetos.registrar
  (:import  (org.lwjgl.opengl GL20)))

(defonce registry
  (atom {}))

(defonce render-state
  (atom {}))

(defn find-vao-by-format
  "Finds a registered VAO by its resolved vertex-formats.
   vertex-formats - vector of vertex-format maps (see
                    epiktetos.opengl.shader-attribute/resolve-vertex-format)
   Returns the VAO entry, or nil."
  [vertex-formats]
  (->> (get-in @registry [::opengl-registry :vaos])
       (filter (fn [[_ vao]] (= vertex-formats (:vertex-formats vao))))
       first
       second))

(defn register-vao
  [hash-k vao]
  (swap! registry assoc-in [::opengl-registry :vaos hash-k] vao))

(defn unregister-vao!
  "Removes a VAO from the registry.
   vao-id - int, GL vertex array id
   Returns the updated registry value."
  [vao-id]
  (swap! registry update-in [::opengl-registry :vaos] dissoc vao-id))

(defn vao-referenced?
  "True when at least one registered program uses the VAO.
   vao-id - int, GL vertex array id"
  [vao-id]
  (->> (get-in @registry [::opengl-registry :programs])
       vals
       (some #(= vao-id (:vao-id %)))
       boolean))

(defn register-program
  [hash-k program]
  (swap! registry assoc-in [::opengl-registry :programs hash-k] program))

(defn get-program
  [program-k]
  (get-in @registry [::opengl-registry :programs program-k]))

(defn register-compute!
  "Registers a compute under its id.
   compute-k - keyword, compute id
   compute   - map, linked and introspected compute
   Returns the updated registry value."
  [compute-k compute]
  (swap! registry assoc-in [::opengl-registry :computes compute-k] compute))

(defn get-compute
  "Returns the compute registered under compute-k, or nil.
   compute-k - keyword, compute id"
  [compute-k]
  (get-in @registry [::opengl-registry :computes compute-k]))

(defn computes
  "Returns the map of registered computes {compute-k compute}."
  []
  (get-in @registry [::opengl-registry :computes]))

(defn register-computes-by-step!
  "Registers the derived dispatch plan of the computes.
   computes-by-step - map {step [compute-k]}, topologically ordered
   Returns the updated registry value."
  [computes-by-step]
  (swap! registry assoc-in [::opengl-registry :computes-by-step] computes-by-step))

(defn lookup-resource-inputs
  "Returns a list of shader inputs of same `resource` type.
   resource: keyword — e.g. :ubo, :ssbo"
  [resource]
  (->> (get-in @registry [::opengl-registry :program-inputs])
       vals
       (filter #(= resource (:resource %)))))

(defn lookup-program-input
  "Returns the program input map for varname, or nil if not found.
   varname: string — GLSL variable name"
  [varname]
  (get-in @registry [::opengl-registry :program-inputs varname]))

(defn register-input!
  "Registers a shader input handler definition.
   input: map — with :varname, :handler and :step
   Returns the updated registry value."
  [input]
  (swap! registry assoc-in [::input-registry (:varname input)] input))

(defn lookup-input
  "Returns the input definition registered for varname, or nil.
   varname: string — GLSL variable name"
  [varname]
  (get-in @registry [::input-registry varname]))

(defn register-program-uniform!
  "Registers the program side of a plain uniform, merging the
   program's fan-out target into the entry shared by every program
   declaring the name.
   varname   - string, uniform name
   program-k - keyword, program id in the registry
   target    - map with :program-id, :schema and :shape
   Returns the updated registry value."
  [varname program-k target]
  (swap! registry update-in [::opengl-registry :program-inputs varname]
         (fn [entry]
           (-> (or entry {:varname  varname
                          :resource :uniform
                          :shape    (:shape target)})
               (assoc-in [:programs program-k] (dissoc target :shape))))))

(defn forget-program-uniforms!
  "Removes a program from the fan-out targets of every registered
   plain uniform and texture input, before its re-introspection.
   program-k - keyword, program id in the registry
   Returns the updated registry value."
  [program-k]
  (swap! registry update-in [::opengl-registry :program-inputs]
         (fn [inputs]
           (if inputs
             (update-vals inputs
                          (fn [entry]
                            (if (contains? #{:uniform :texture} (:resource entry))
                              (update entry :programs dissoc program-k)
                              entry)))
             inputs))))

(defn register-program-texture!
  "Registers the program side of a texture input, merging the
   program's fan-out target into the entry shared by every program
   declaring the sampler name.
   varname   - string, sampler uniform name
   program-k - keyword, program id in the registry
   base      - map with :unit and :sampler-type, set at first registration
   target    - map with :program-id and :location
   Returns the updated registry value."
  [varname program-k base target]
  (swap! registry update-in [::opengl-registry :program-inputs varname]
         (fn [entry]
           (-> (or entry (merge {:varname varname :resource :texture} base))
               (assoc-in [:programs program-k] target)))))

(defn register-texture!
  "Registers a texture resource under its id.
   id      - keyword, texture id
   texture - map with :texture-id, :target, :width, :height, :format,
             :mips?
   Returns the updated registry value."
  [id texture]
  (swap! registry assoc-in [::opengl-registry :textures id] texture))

(defn lookup-texture
  "Returns the texture registered under id, or nil.
   id - keyword, texture id"
  [id]
  (get-in @registry [::opengl-registry :textures id]))

(defn register-fallback-texture!
  "Registers the engine's fallback texture, bound whenever a texture
   input cannot be resolved.
   texture-id - int, GL texture id
   Returns the updated registry value."
  [texture-id]
  (swap! registry assoc-in [::opengl-registry :fallback-texture] texture-id))

(defn lookup-fallback-texture
  "Returns the id of the engine's fallback texture, or nil when it has
   not been created yet."
  []
  (get-in @registry [::opengl-registry :fallback-texture]))

(defn register-sampler!
  "Registers a GL sampler object under the reg-input options it was
   built from, so inputs sharing a read configuration share it.
   options    - map, the :sampler/* options of a texture input
   sampler-id - int, GL sampler id
   Returns the updated registry value."
  [options sampler-id]
  (swap! registry assoc-in [::opengl-registry :samplers options] sampler-id))

(defn lookup-sampler
  "Returns the GL sampler object registered for a set of read options,
   or nil.
   options - map, the :sampler/* options of a texture input"
  [options]
  (get-in @registry [::opengl-registry :samplers options]))

(defn register-program-input!
  "Registers the program side of a shader input.
   resource      - keyword, e.g. :ubo, :ssbo
   program-input - map, introspected program input with allocated
                   binding point, :buffer-id and :schema
   Returns the updated registry value."
  [resource program-input]
  (let [{:keys [varname buffer-binding alloc members buffer-data-size
                buffer-id schema capacity]} program-input]
    (swap! registry assoc-in [::opengl-registry :program-inputs varname]
           {:varname          varname
            :resource         resource
            :buffer-data-size buffer-data-size
            :members          members
            :schema           schema
            :buffer-id        buffer-id
            :capacity         capacity
            :alloc            alloc
            :binding-point    buffer-binding})))
