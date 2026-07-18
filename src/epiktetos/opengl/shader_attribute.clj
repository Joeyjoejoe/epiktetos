(ns epiktetos.opengl.shader-attribute
  (:require [epiktetos.registrar :as registrar]
            [epiktetos.opengl.buffer :as buffer]
            [epiktetos.opengl.introspection :as introspect]
            [epiktetos.utils.hash :as hash])
  (:import (org.lwjgl.opengl GL45)))

(defn- assert-packing!
  "Validates the :packing map of a vertex-buffer-map against the
   introspected attributes: known attribute names, known packed
   formats (see buffer/PACKED-FORMATS), single-location non-double
   attributes only, and integer storage for integer attributes.
   Throws ex-info otherwise.
   packing      - map {varname pack-keyword}
   prog-attribs - map {varname attrib}, introspected attributes
   Returns nil."
  [packing prog-attribs]
  (doseq [[varname pack] packing]
    (let [fmt    (buffer/PACKED-FORMATS pack)
          attrib (get prog-attribs varname)
          {:keys [total-locations integer? double?]
           :or   {total-locations 1}} (:type attrib)]
      (when-not fmt
        (throw (ex-info "Unknown attribute packing"
                        {:attribute varname :packing pack
                         :known (set (keys buffer/PACKED-FORMATS))})))
      (when-not attrib
        (throw (ex-info "Packed attribute not found in shader"
                        {:attribute varname :packing pack})))
      (when (or double? (> total-locations 1))
        (throw (ex-info "Packing is not supported on matrix or double attributes"
                        {:attribute varname :packing pack})))
      (when (not= (boolean integer?) (boolean (:integer? fmt)))
        (throw (ex-info "Packed format does not match the attribute base type"
                        {:attribute varname :packing pack
                         :integer-attribute? (boolean integer?)}))))))

(defn prep-vertex-buffer
  "Interpret a vertex-buffer-map valid to DSL :

  {:layout [\"coordinates\" \"color\" \"texture\"]
  :handler (fn [entity] []) ;; For buffer creation when first render entity
  :storage :dynamic  ;; For buffer creation when first render entity
  :normalize #{\"color\"}
  :packing {\"color\" :ubyte-norm} ;; packed VBO storage per attribute
  :divisor 0}
  "
  [prog-id vao-id binding-index vb-map]
  (let [{:keys [layout handler normalize storage divisor packing]
         :or   {storage :dynamic divisor 0 normalize #{} packing {}}} vb-map

        prog-attribs    (introspect/attributes-infos prog-id)
        vb-attribs      (keep prog-attribs layout)
        attrib-bytes    (fn [{:keys [varname type]}]
                          (if-let [pack (packing varname)]
                            (* (:size type)
                               (:scalar-bytes (buffer/PACKED-FORMATS pack)))
                            (:bytes type)))
        attribs-offsets (reductions + 0 (keep attrib-bytes vb-attribs))
        stride          (last attribs-offsets)]

    ;; Validates attribute names in layout
    (when-not (= (count layout) (count vb-attribs))
      (-> (str "Vertex-layout attribute not found or used in shader : "
               (clojure.set/difference (set layout) (set (mapv :varname vb-attribs))))
          Exception.
          throw))

    ;; Validates attribute names in normalize set
    (when-let [bad-attribs (seq (clojure.set/difference normalize (set layout)))]
      (-> (str "Unknkown attribute(s) " bad-attribs " in normalize set : " normalize)
          Exception.
          throw))

    (assert-packing! packing prog-attribs)

    ;; Initialize VAO attributes
    (doseq [[attrib offset] (map list vb-attribs attribs-offsets)
            :let [{:keys [varname location type]}  attrib
                  {:keys [base-type size bytes total-locations integer? double?]
                   :or   {total-locations 1}} type
                  fmt       (some-> (packing varname) buffer/PACKED-FORMATS)
                  base-type (if fmt (:base-type fmt) base-type)
                  normalize (boolean (or (:normalized? fmt)
                                         (get normalize varname)))]]

      (let [col-bytes (quot bytes total-locations)
            loc-step  (if (and double? (> size 2)) 2 1)]
        (doseq [col (range total-locations)
                :let [loc        (+ location (* col loc-step))
                      col-offset (+ offset (* col col-bytes))]]
          (cond
            double?  (GL45/glVertexArrayAttribLFormat vao-id loc size base-type col-offset)
            integer? (GL45/glVertexArrayAttribIFormat vao-id loc size base-type col-offset)
            :else    (GL45/glVertexArrayAttribFormat vao-id loc size base-type normalize col-offset))

          (GL45/glEnableVertexArrayAttrib vao-id loc)
          (GL45/glVertexArrayAttribBinding vao-id loc binding-index))))

    (GL45/glVertexArrayBindingDivisor vao-id binding-index divisor)

    {:handler       handler
     :binding-index binding-index
     :divisor       divisor
     :offset        0 ;; might lives at entity scope for buffer data management
     :stride        stride
     :storage       (storage buffer/BUFFER-STORAGE)
     :type-layout   (mapv (fn [{:keys [varname type]}]
                            (if-let [pack (packing varname)]
                              [(:glsl-name type) pack]
                              (:glsl-name type)))
                          vb-attribs)}))

(defn setup!
  "Setup a shader program attributes. It produce :
    - One vao (register or lookup one)
    - For each vbo :
      - Specify attributes layout
      - One clojure spec to validate vbo handler output

  To render attributes :
    - vao id
    - vbos : spec, id, binding-index, handler, stride, storage, buffer offset (see. glVertexArrayVertexBuffer)
    -
  "
  [prog-map]
  (let [{:keys [id vertex-layout]} prog-map
        layout-hash  (hash/sha256 vertex-layout)
        existing-vao (registrar/find-vao-by-layout layout-hash)]

    (if existing-vao
      (assoc prog-map :vao-id (:id existing-vao))
      (let [vao-id         (GL45/glCreateVertexArrays)
            vertex-buffers (mapv #(prep-vertex-buffer id vao-id %1 %2)
                                 (range)
                                 vertex-layout)]
        (registrar/register-vao vao-id {:id          vao-id
                                        :layout-hash layout-hash
                                        :vbos        vertex-buffers})
        (assoc prog-map :vao-id vao-id)))))
