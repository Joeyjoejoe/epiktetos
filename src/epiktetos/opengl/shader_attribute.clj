(ns epiktetos.opengl.shader-attribute
  (:require [clojure.set :as set]
            [epiktetos.registrar :as registrar]
            [epiktetos.opengl.buffer :as buffer]
            [epiktetos.opengl.introspection :as introspect])
  (:import (org.lwjgl.opengl GL30 GL45)))

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

(defn resolve-vertex-format
  "Resolve one vertex-buffer-map of the DSL against the introspected
  attributes of a linked program, without touching GL state:

  {:layout [\"coordinates\" \"color\" \"texture\"]
  :handler (fn [entity] []) ;; For buffer creation when first render entity
  :storage :dynamic  ;; For buffer creation when first render entity
  :normalize #{\"color\"}
  :packing {\"color\" :ubyte-norm} ;; packed VBO storage per attribute
  :divisor 0}

  Validates the layout names, the normalize set and the packing map,
  then derives two maps: the vertex-format — the resolved GL
  configuration of the binding, matrix columns expanded to one entry
  per location — and the vbo template the program prepares its
  entities with. The vertex-format is the identity of a VAO: two
  programs share one exactly when their resolved vertex-formats are
  equal.
  prog-attribs  - map {varname attrib}, introspected attributes
  binding-index - int, VAO binding index of this buffer
  vb-map        - map, one entry of the :vertex-layout DSL
  Returns {:vertex-format {:binding-index int :divisor int :stride int
                           :attributes [{:location int :size int
                                         :base-type int :kind keyword
                                         :normalized? bool :offset int}]}
           :vbo {:handler fn :binding-index int :divisor int :offset 0
                 :stride int :storage int :type-layout vector}}"
  [prog-attribs binding-index vb-map]
  (let [{:keys [layout handler normalize storage divisor packing]
         :or   {storage :dynamic divisor 0 normalize #{} packing {}}} vb-map

        vb-attribs (keep prog-attribs layout)]

    ;; Validates attribute names in layout
    (when-not (= (count layout) (count vb-attribs))
      (-> (str "Vertex-layout attribute not found or used in shader : "
               (set/difference (set layout) (set (mapv :varname vb-attribs))))
          Exception.
          throw))

    ;; Validates attribute names in normalize set
    (when-let [bad-attribs (seq (set/difference normalize (set layout)))]
      (-> (str "Unknkown attribute(s) " bad-attribs " in normalize set : " normalize)
          Exception.
          throw))

    (assert-packing! packing prog-attribs)

    (let [attrib-bytes    (fn [{:keys [varname type]}]
                            (if-let [pack (packing varname)]
                              (* (:size type)
                                 (:scalar-bytes (buffer/PACKED-FORMATS pack)))
                              (:bytes type)))
          attribs-offsets (reductions + 0 (keep attrib-bytes vb-attribs))
          stride          (last attribs-offsets)]

      {:vertex-format
     {:binding-index binding-index
      :divisor       divisor
      :stride        stride
      :attributes
      (vec
        (for [[attrib offset] (map list vb-attribs attribs-offsets)
              :let [{:keys [varname location type]} attrib
                    {:keys [base-type size bytes total-locations integer? double?]
                     :or   {total-locations 1}} type
                    fmt         (some-> (packing varname) buffer/PACKED-FORMATS)
                    base-type   (if fmt (:base-type fmt) base-type)
                    kind        (cond double?  :double
                                      integer? :integer
                                      :else    :float)
                    normalized? (boolean (or (:normalized? fmt)
                                             (get normalize varname)))
                    col-bytes   (quot bytes total-locations)
                    loc-step    (if (and double? (> size 2)) 2 1)]
              col (range total-locations)]
          {:location    (+ location (* col loc-step))
           :size        size
           :base-type   base-type
           :kind        kind
           :normalized? normalized?
           :offset      (+ offset (* col col-bytes))}))}

     :vbo
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
                           vb-attribs)}})))

(defn apply-vertex-format!
  "Apply a resolved vertex-format to a VAO: attribute formats and
  enables, binding association and binding divisor.
  vao-id        - int, GL vertex array id
  vertex-format - map, see resolve-vertex-format
  Returns nil."
  [vao-id {:keys [binding-index divisor attributes]}]
  (doseq [{:keys [location size base-type kind normalized? offset]} attributes]
    (case kind
      :double  (GL45/glVertexArrayAttribLFormat vao-id location size base-type offset)
      :integer (GL45/glVertexArrayAttribIFormat vao-id location size base-type offset)
      :float   (GL45/glVertexArrayAttribFormat vao-id location size base-type normalized? offset))
    (GL45/glEnableVertexArrayAttrib vao-id location)
    (GL45/glVertexArrayAttribBinding vao-id location binding-index))
  (GL45/glVertexArrayBindingDivisor vao-id binding-index divisor)
  nil)

(defn setup!
  "Set up the vertex attributes of a linked program: resolve each
  vertex-layout entry against the introspected attributes, share a
  registered VAO of equal vertex-formats or create and configure a
  new one, and carry the vbo templates on the program.
  prog-map - map, linked program with :id and :vertex-layout
  Returns prog-map with :vao-id and :vbos."
  [prog-map]
  (let [{:keys [id vertex-layout]} prog-map
        prog-attribs   (introspect/attributes-infos id)
        resolved       (mapv #(resolve-vertex-format prog-attribs %1 %2)
                             (range)
                             vertex-layout)
        vertex-formats (mapv :vertex-format resolved)
        vbos           (mapv :vbo resolved)
        existing-vao   (registrar/find-vao-by-format vertex-formats)
        vao-id         (if existing-vao
                         (:id existing-vao)
                         (let [vao-id (GL45/glCreateVertexArrays)]
                           (doseq [vertex-format vertex-formats]
                             (apply-vertex-format! vao-id vertex-format))
                           (registrar/register-vao vao-id
                                                   {:id             vao-id
                                                    :vertex-formats vertex-formats})
                           vao-id))]
    (assoc prog-map :vao-id vao-id :vbos vbos)))

(defn delete-vao!
  "Delete a VAO and unregister it, unless a registered program still
   references it.
   vao-id - int, GL vertex array id
   Returns nil."
  [vao-id]
  (when-not (registrar/vao-referenced? vao-id)
    (GL30/glDeleteVertexArrays (int vao-id))
    (registrar/unregister-vao! vao-id))
  nil)

(defn delete-vaos!
  "Delete every registered VAO.
   registry - map, the registry value
   Returns nil."
  [registry]
  (doseq [vao-id (keys (get-in registry [::registrar/opengl-registry :vaos]))]
    (GL30/glDeleteVertexArrays (int vao-id)))
  nil)
