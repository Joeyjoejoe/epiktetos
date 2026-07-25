(ns epiktetos.registrar
  (:import  (org.lwjgl.opengl GL20)))

(defonce registry
  (atom {}))

(defonce render-state
  (atom {}))

(defn find-vao-by-layout
  "Finds a registered VAO by its layout hash"
  [layout-hash]
  (->> (get-in @registry [::opengl-registry :vaos])
       (filter (fn [[_ vao]] (= layout-hash (:layout-hash vao))))
       first
       second))

(defn register-vao
  [hash-k vao]
  (swap! registry assoc-in [::opengl-registry :vaos hash-k] vao))

(defn register-program
  [hash-k program]
  (swap! registry assoc-in [::opengl-registry :programs hash-k] program))

(defn get-program
  [program-k]
  (get-in @registry [::opengl-registry :programs program-k]))

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
