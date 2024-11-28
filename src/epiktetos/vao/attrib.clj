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

