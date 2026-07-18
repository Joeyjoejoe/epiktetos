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
