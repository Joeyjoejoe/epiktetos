(ns epiktetos.opengl.shader-attribute
  (:require [epiktetos.event :as event]
            [epiktetos.registrar :as registrar]
            [epiktetos.opengl.buffer :as buffer]
            [epiktetos.opengl.introspection :as introspect])
  (:import (java.security MessageDigest)
           (org.lwjgl.opengl GL45)))

(defn sha256
  "Convert a clojure data structure to a string use sha-256 algorithm.
  Using anonymous function forms in the data structure will generate
  a different string each time. Use function symbols to provide this
  behavior"
  [data]
  (let [string (pr-str data)
        digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes string "UTF-8"))]
    (apply str (map (partial format "%02x") digest))))


(defn prep-vertex-buffer
  "Interpret a vertex-buffer-map valid to DSL :

  {:layout [\"coordinates\" \"color\" \"texture\"]
  :handler (fn [entity] []) ;; For buffer creation when first render entity
  :storage :dynamic  ;; For buffer creation when first render entity
  :normalize #{\"color\"}
  :divisor 0}
  "
  [prog-id vao-id binding-index vb-map]
  (let [{:keys [layout handler normalize storage divisor]
         :or   {storage :dynamic divisor 0 normalize #{}}} vb-map

        prog-attribs    (introspect/attributes-infos prog-id)
        vb-attribs      (keep prog-attribs layout)
        attribs-offsets (reductions + 0 (keep #(get-in % [:type :bytes]) vb-attribs))
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

    ;; Initialize VAO attributes
    (doseq [[attrib offset] (map list vb-attribs attribs-offsets)
            :let [{:keys [varname location type]}  attrib
                  {:keys [base-type size total-locations integer? double?]
                   :or   {total-locations 1}} type
                  normalize (-> normalize (get varname) boolean)]]

      (doseq [loc (range location (+ location total-locations))]
        (cond
          double?  (GL45/glVertexArrayAttribLFormat vao-id loc size base-type offset)
          integer? (GL45/glVertexArrayAttribIFormat vao-id loc size base-type offset)
          :else    (GL45/glVertexArrayAttribFormat vao-id loc size base-type normalize offset))

        (GL45/glEnableVertexArrayAttrib vao-id loc)
        (GL45/glVertexArrayAttribBinding vao-id loc binding-index)))

    (GL45/glVertexArrayBindingDivisor vao-id binding-index divisor)

    {:handler       handler
     :handler-spec  (fn [] true)
     :binding-index binding-index
     :offset        0 ;; might lives at entity scope for buffer data management
     :stride        stride
     :storage       (storage buffer/BUFFER-STORAGE)
     :type-layout   (mapv #(get-in % [:type :glsl-name]) vb-attribs)}))

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
        layout-hash  (sha256 vertex-layout)
        existing-vao (registrar/find-vao-by-layout layout-hash)]

    (if existing-vao
      (assoc prog-map :vao-id (:id existing-vao))
      (let [vao-id         (GL45/glCreateVertexArrays)
            vertex-buffers (doall (map-indexed #(prep-vertex-buffer id vao-id %1 %2)
                                               vertex-layout))]
        (registrar/register-vao vao-id {:id          vao-id
                                        :layout-hash layout-hash
                                        :vbos        vertex-buffers})
        (assoc prog-map :vao-id vao-id)))))

(comment


    (event/dispatch [:dev/eval #(build 3 ::attribute)])

 )
