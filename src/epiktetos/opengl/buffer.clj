(ns epiktetos.opengl.buffer
  (:require [epiktetos.opengl.glsl :as glsl])
  (:import (org.lwjgl BufferUtils)
           (org.lwjgl.opengl GL11 GL30 GL44 GL45)))

(defonce BUFFER-STORAGE
  {:dynamic    GL44/GL_DYNAMIC_STORAGE_BIT
   :read       GL30/GL_MAP_READ_BIT
   :write      GL30/GL_MAP_WRITE_BIT
   :persistent GL44/GL_MAP_PERSISTENT_BIT
   :coherent   GL44/GL_MAP_COHERENT_BIT
   :client     GL44/GL_CLIENT_STORAGE_BIT})

(defn glsl-type
  "Returns the GLSL type map for a given glsl-name keyword"
  [glsl-name]
  (glsl/TRANSPARENT-TYPE (glsl/TYPE-BY-NAME glsl-name)))

(defonce PACKED-FORMATS
  {:half       {:base-type GL30/GL_HALF_FLOAT     :scalar-bytes 2 :normalized? false}
   :ubyte      {:base-type GL11/GL_UNSIGNED_BYTE  :scalar-bytes 1 :normalized? false :integer? true}
   :ushort     {:base-type GL11/GL_UNSIGNED_SHORT :scalar-bytes 2 :normalized? false :integer? true}
   :ubyte-norm {:base-type GL11/GL_UNSIGNED_BYTE  :scalar-bytes 1 :normalized? true}
   :byte-norm  {:base-type GL11/GL_BYTE           :scalar-bytes 1 :normalized? true}})

(defn type-length
  "Returns the total number of scalars in a GLSL type"
  [{:keys [size total-locations] :or {total-locations 1}}]
  (* size total-locations))

(defn attrib-type
  "Resolves a type-layout entry into its GLSL type map. A packed
   entry — a [glsl-name pack-keyword] pair — carries its storage
   format under :pack and its packed byte size under :bytes.
   entry - keyword, or [keyword pack-keyword]
   Returns the type map."
  [entry]
  (if (keyword? entry)
    (glsl-type entry)
    (let [[glsl-name pack] entry
          fmt (or (PACKED-FORMATS pack)
                  (throw (ex-info "Unknown attribute packing"
                                  {:packing pack :entry entry})))
          t   (glsl-type glsl-name)]
      (assoc t
             :pack  pack
             :bytes (* (type-length t) (:scalar-bytes fmt))))))

(defn type-layout-count
  "Returns the total number of scalars in a type layout :
  (type-layout-count [:vec3 :ivec2])
  => 5                                                  "
  [type-layout]
  (transduce (map (comp type-length attrib-type)) + type-layout))

(defn assert-scalar!
  "Validates a scalar is compatible with the expected GLSL type.
   Throws ex-info when glsl/scalar-error reports an error."
  [scalar glsl-type]
  (when-let [error (glsl/scalar-error glsl-type scalar)]
    (throw (ex-info "Invalid value for GLSL type"
                    {:error         error
                     :expected-type (:glsl-name glsl-type)
                     :actual-type   (type scalar)
                     :actual-value  scalar}))))

(defn- float->half
  "IEEE 754 half-float bits of a float value (no rounding, denormals
   flushed to zero).
   v - double
   Returns a long in [0, 0xFFFF]."
  [^double v]
  (let [bits (Float/floatToIntBits (float v))
        sign (bit-and (unsigned-bit-shift-right bits 16) 0x8000)
        e    (- (bit-and (unsigned-bit-shift-right bits 23) 0xFF) 127)
        m    (bit-and bits 0x7FFFFF)]
    (cond
      (> e 15)  (bit-or sign 0x7C00)
      (< e -14) sign
      :else     (bit-or sign
                        (bit-shift-left (+ e 15) 10)
                        (unsigned-bit-shift-right m 13)))))

(defn put-scalar!
  "Puts a scalar into a ByteBuffer, in the type's packed storage
   format when it carries one. Booleans are converted to 1/0."
  [^java.nio.ByteBuffer buffer {:keys [pack integer? double?]} scalar]
  (let [scalar (if (boolean? scalar) (if scalar 1 0) scalar)]
    (case pack
      nil
      (cond
        double?  (.putDouble buffer (clojure.core/double scalar))
        integer? (.putInt    buffer (clojure.core/int scalar))
        :else    (.putFloat  buffer (clojure.core/float scalar)))

      :half
      (.putShort buffer (unchecked-short (float->half (clojure.core/double scalar))))

      :ubyte
      (.put buffer (unchecked-byte (clojure.core/long scalar)))

      :ushort
      (.putShort buffer (unchecked-short (clojure.core/long scalar)))

      :ubyte-norm
      (.put buffer (unchecked-byte
                     (Math/round (* 255.0 (max 0.0 (min 1.0 (clojure.core/double scalar)))))))

      :byte-norm
      (.put buffer (unchecked-byte
                     (Math/round (* 127.0 (max -1.0 (min 1.0 (clojure.core/double scalar)))))))))
  buffer)

(defn scalar-bytes
  "Returns the byte size of one scalar of a GLSL type.
   glsl-type - map, GLSL type"
  [{:keys [bytes] :as glsl-type}]
  (quot bytes (type-length glsl-type)))

(defn put-scalar-at!
  "Puts a scalar into a ByteBuffer at an absolute byte offset.
   Booleans are converted to 1/0.
   buffer    - ByteBuffer
   glsl-type - map, GLSL type
   offset    - int, byte offset
   scalar    - number or boolean
   Returns buffer."
  [^java.nio.ByteBuffer buffer {:keys [integer? double?]} offset scalar]
  (let [scalar (if (boolean? scalar) (if scalar 1 0) scalar)]
    (cond
      double?  (.putDouble buffer (clojure.core/int offset) (clojure.core/double scalar))
      integer? (.putInt    buffer (clojure.core/int offset) (clojure.core/int scalar))
      :else    (.putFloat  buffer (clojure.core/int offset) (clojure.core/float scalar))))
  buffer)

(defn create-storage-buffer!
  "Creates a GPU buffer with immutable storage sized and initialized
   from a ByteBuffer.
   data  - ByteBuffer, initial content
   flags - coll of keywords from BUFFER-STORAGE
   Returns the GL buffer id."
  [^java.nio.ByteBuffer data flags]
  (let [buffer-id (GL45/glCreateBuffers)]
    (GL45/glNamedBufferStorage buffer-id data
                               (int (apply bit-or 0 (map BUFFER-STORAGE flags))))
    buffer-id))

(defn upload!
  "Uploads a ByteBuffer at the start of a GPU buffer.
   buffer-id - int, GL buffer id
   data      - ByteBuffer
   Returns nil."
  [buffer-id ^java.nio.ByteBuffer data]
  (GL45/glNamedBufferSubData (int buffer-id) 0 data))

(defn from-flat-layout
  "Creates and fills a ByteBuffer according to a flat layout of
   glsl types — plain keywords, or [glsl-name pack] pairs for packed
   storage (see PACKED-FORMATS) — and a flat collection of scalars:

  (from-flat-layout [:vec3 :int] [1.0 1.0 1.0 24])
  (from-flat-layout [[:vec3 :half] :int] [1.0 1.0 1.0 24])"
  [type-layout flat-data]
  (let [glsl-types (mapv attrib-type type-layout)
        type-bytes (reduce + (map :bytes glsl-types))
        type-count (reduce + (map type-length glsl-types))
        data-count (count flat-data)]

    (when-not (zero? (mod data-count type-count))
      (throw (ex-info "Flat data size is not a multiple of type"
                      {:type-count type-count
                       :data-count data-count})))

    (let [type-coll (vec (mapcat #(repeat (type-length %) %) glsl-types))
          buffer    (BufferUtils/createByteBuffer (* (/ data-count type-count) type-bytes))]

      (doseq [[glsl-type scalar] (map vector (cycle type-coll) flat-data)]
        (assert-scalar! scalar glsl-type)
        (put-scalar! buffer glsl-type scalar))

      (.flip buffer))))

(defn int-buffer
  "Create an integer array buffer from data"
  [data]
  (let [data (int-array data)]
    (-> (BufferUtils/createIntBuffer (count data))
        (.put data)
        (.flip))))

(defn float-buffer
  "Create an float array buffer from data"
  [data]
  (let [data (float-array data)]
    (-> (BufferUtils/createFloatBuffer (count data))
        (.put data)
        (.flip))))
