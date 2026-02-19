(ns epiktetos.opengl.buffer
  (:require [epiktetos.opengl.glsl :as glsl])
  (:import (org.lwjgl BufferUtils)
           (org.lwjgl.opengl GL30 GL44)))

(defonce BUFFER-STORAGE
  {:dynamic    GL44/GL_DYNAMIC_STORAGE_BIT
   :read       GL30/GL_MAP_READ_BIT
   :write      GL30/GL_MAP_WRITE_BIT
   :persistent GL44/GL_MAP_PERSISTENT_BIT
   :coherent   GL44/GL_MAP_COHERENT_BIT
   :client     GL44/GL_CLIENT_STORAGE_BIT})

(defn- type-info
  "Returns the GLSL type info map for a given glsl-name keyword"
  [glsl-name]
  (glsl/TRANSPARENT-TYPE (glsl/TYPE-BY-NAME glsl-name)))

(defn- type-length
  "Returns the total number of scalar elements in a GLSL type"
  [{:keys [size total-locations] :or {total-locations 1}}]
  (* size total-locations))

(defn type-layout-count
  "Returns the total number of scalar in a type layout :
  (type-layout-count [:vec3 :ivec2])
  => 5                                                  "
  [type-layout]
  (transduce (map (comp type-length type-info)) + type-layout))

(defn- assert-value-type!
  "Validates value is compatible with the expected GLSL type."
  [v {:keys [integer?]}]
  (cond
    (not (number? v))
    (throw (ex-info "Non-numeric value in flat vertex data"
                    {:expected-type Number
                     :actual-type   (type v)
                     :actual-value  v}))

    (and integer? (not (clojure.core/integer? v)))
    (throw (ex-info "Floating-point value provided for integer GLSL attribute"
                    {:expected-type Integer
                     :actual-type   (type v)
                     :actual-value  v}))))

(defn- put-value!
  "Puts a value into a ByteBuffer"
  [^java.nio.ByteBuffer buf {:keys [integer? double?]} v]
  (cond
    double?  (.putDouble buf (clojure.core/double v))
    integer? (.putInt    buf (clojure.core/int v))
    :else    (.putFloat  buf (clojure.core/float v)))
  buf)

(defn fill-byte-buffer
  "Creates and fills a ByteBuffer from a flat sequence of vertex data,
  ensuring the seaquence match expeted type layout"
  [type-layout flat-data]
  (let [tinfos     (mapv type-info type-layout)
        type-bytes (reduce + (map :bytes tinfos))
        type-count (reduce + (map type-length tinfos))
        data-count (count flat-data)]

    (when-not (zero? (mod data-count type-count))
      (throw (ex-info "Flat data size is not a multiple of type"
                      {:type-count type-count
                       :data-count data-count})))

    (let [type-coll (vec (mapcat #(repeat (type-length %) %) tinfos))
          buf       (BufferUtils/createByteBuffer (* (/ data-count type-count) type-bytes))]

      (doseq [[tinfo v] (map vector (cycle type-coll) flat-data)]
        (assert-value-type! v tinfo)
        (put-value! buf tinfo v))

      (.flip buf))))

(defn int-buffer
  "Create an integer array buffer from data"
  [data]
  (let [data (int-array data)]
    (-> (BufferUtils/createIntBuffer (count data))
        (.put data)
        (.flip))))
