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
  "Returns a list of program inputs whose :resource field matches resource.
   resource: keyword — e.g. :ubo, :ssbo"
  [resource]
  (->> (get-in @registry [::opengl-registry :program-inputs])
       vals
       (filter #(= resource (:resource %)))))

(defn lookup-input
  "Returns the program input map for varname, or nil if not found.
   varname: string — GLSL variable name"
  [varname]
  (get-in @registry [::opengl-registry :program-inputs varname]))

(defn register-ubo!
  [ubo]
  (let [{:keys [varname buffer-binding alloc members buffer-data-size]} ubo]
    (swap! registry assoc-in [::opengl-registry :program-inputs varname]
           {:varname varname
            :resource :ubo
            :buffer-data-size buffer-data-size
            :members members
            :alloc alloc
            :binding-point buffer-binding})))

(defn register-ssbo!
  [ssbo]
  (let [{:keys [varname buffer-binding alloc members buffer-data-size]} ssbo]
    (swap! registry assoc-in [::opengl-registry :program-inputs varname]
           {:varname varname
            :resource :ssbo
            :buffer-data-size buffer-data-size
            :members members
            :alloc alloc
            :binding-point buffer-binding})))
