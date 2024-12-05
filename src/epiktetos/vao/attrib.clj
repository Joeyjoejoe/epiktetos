(ns epiktetos.vao.attrib
  (:import  (org.lwjgl.opengl GL11 GL20 GL45)))

;; https://www.khronos.org/opengl/wiki/Data_Type_(GLSL)
(def BASIC-TYPES (atom {}))
(def CUSTOM-TYPES (atom{}))

(defn get-attrib-type
  [type-k]
  (let [custom-type (get @CUSTOM-TYPES type-k)]
    (if-not (get @BASIC-TYPES type-k)
      (if-not custom-type
        (throw (Exception. (str "Unknown type " type-k ", use reg-attrib-type to register a custom type.")))
        custom-type)
      [type-k])))

(defn reg-basic-type
  [k gl-type item-bytes item-count]
  (let [basic-type {:bytes (* item-count item-bytes)
                    :type gl-type
                    :count item-count}]
    (swap! BASIC-TYPES assoc k basic-type)))

(defn reg-custom-type
  [type-k struct-ks]
  (let [attrib-layout (mapcat get-attrib-type struct-ks)]
    (swap! CUSTOM-TYPES assoc type-k (vec attrib-layout))))

(reg-basic-type :vec2f GL11/GL_FLOAT java.lang.Float/BYTES 2)
(reg-basic-type :vec3f GL11/GL_FLOAT java.lang.Float/BYTES 3)
(reg-basic-type :vec4f GL11/GL_FLOAT java.lang.Float/BYTES 4)
(reg-basic-type :vec2i GL11/GL_INT java.lang.Integer/BYTES 2)
(reg-basic-type :vec3i GL11/GL_INT java.lang.Integer/BYTES 3)
(reg-basic-type :vec4i GL11/GL_INT java.lang.Integer/BYTES 4)
(reg-basic-type :vec2b GL11/GL_BYTE java.lang.Byte/BYTES 2)
(reg-basic-type :vec3b GL11/GL_BYTE java.lang.Byte/BYTES 3)
(reg-basic-type :vec4b GL11/GL_BYTE java.lang.Byte/BYTES 4)

(reg-custom-type :mat2f [:vec4f :vec4f])
(reg-custom-type :mat3f [:vec4f :vec4f :vec4f])
(reg-custom-type :mat4f [:vec4f :vec4f :vec4f :vec4f])
(reg-custom-type :someStruct [:mat2f :vec2f :mat4f])

(defn get-offset
  "Given the index of an attrib in attribs collection,
  returns the bytes size sum of previous attribs"
  [attr-layout location]
  (let [prev-attribs (subvec (apply vector attr-layout) 0 location)]
    (reduce #(+ %1 (::byte-size %2)) 0 prev-attribs)))

(defn parse-key
  "Parse attribute key"
  ([attrib-key]
   (let [attrib-name (name attrib-key)
         attrib-type (-> attrib-key namespace keyword)]

     (if-let [{:keys [bytes type count length]
               :or   {length 1}}
              (get @BASIC-TYPES attrib-type)]

       ;; Basic type attrib
       #::{:key        attrib-key
           :type       attrib-type
           :length     length
           :data-type  type
           :data-count count
           :byte-size  bytes}

       ;; Custom type attrib
       (->> attrib-type
            get-attrib-type
            (map #(parse-key (keyword (name %) attrib-name)))))))

  ([attrib-key & attribs]
   (reduce #(conj %1 (parse-key %2)) [(parse-key attrib-key)] attribs)))

(defn add-attrib
  "Creates a new attribute to given vao"
  [vao attrib]
  (let [{::keys [location length
                data-count data-type normalized?
                byte-offset byte-size
                binding-index divisor]
         :or {length 1 binding-index 0 divisor 0 normalized? false}}
        attrib]

        (GL45/glVertexArrayAttribFormat vao location data-count data-type normalized? byte-offset)
        (GL45/glEnableVertexArrayAttrib vao location)
        (GL45/glVertexArrayAttribBinding vao location binding-index)

        ;; TODO Might not need to declare that for every attributes of
        ;; a buffer layout
        (GL45/glVertexArrayBindingDivisor vao binding-index divisor)))
