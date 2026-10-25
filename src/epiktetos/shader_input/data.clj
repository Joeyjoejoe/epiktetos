(ns epiktetos.shader-input.data
  (:require [epiktetos.opengl.buffer :as gl-buffer]
            [epiktetos.opengl.glsl :as glsl]
            [epiktetos.shader-input.types :as types])
  (:import (java.nio ByteBuffer)
           (org.lwjgl BufferUtils)))

(declare validate-value)

(defn- invalid!
  "Throws an ex-info describing an invalid handler value.
   path  - vector, path of the value in the block
   error - keyword, error identifier
   data  - map, additional error context
   Returns nothing."
  [path error data]
  (throw (ex-info "Invalid shader input data"
                  (merge {:path path :error error} data))))

(defn- validate-scalars
  "Validates a flat sequential value against a vector or matrix schema.
   schema - map, vector or matrix schema
   path   - vector, path of the value in the block
   value  - the handler value for this schema
   Returns nil, throws on the first invalid scalar."
  [schema path value]
  (let [{:keys [glsl-name size] :as glsl-type} (:type schema)
        expected-count (* size (get glsl-type :total-locations 1))]
    (cond
      (not (sequential? value))
      (invalid! path :not-sequential {:expected glsl-name :value value})

      (not= expected-count (count value))
      (invalid! path :component-count-mismatch
                {:expected       glsl-name
                 :expected-count expected-count
                 :actual-count   (count value)})

      :else
      (doseq [[i scalar] (map-indexed vector value)]
        (when-let [error (glsl/scalar-error glsl-type scalar)]
          (invalid! (conj path i) error {:expected glsl-name :value scalar}))))))

(defn- validate-struct
  "Validates a map value against a struct schema.
   schema - map, struct schema
   path   - vector, path of the value in the block
   value  - the handler value for this schema
   Returns nil, throws on the first missing, invalid or unknown field."
  [schema path value]
  (let [fields (:fields schema)]
    (if-not (map? value)
      (invalid! path :not-a-map {:value value})
      (do
        (doseq [[fname fschema] fields]
          (if (contains? value fname)
            (validate-value fschema (conj path fname) (get value fname))
            (invalid! (conj path fname) :missing-field {})))
        (doseq [k (remove #(contains? fields %) (keys value))]
          (invalid! (conj path k) :unknown-field {}))))))

(defn- validate-array
  "Validates a sequential value against an array schema.
   schema - map, array schema
   path   - vector, path of the value in the block
   value  - the handler value for this schema
   Returns nil, throws on the first invalid element. Runtime arrays
   accept 0 to :capacity elements, fixed arrays exactly :count."
  [schema path value]
  (let [{:keys [capacity] :or {capacity 1}} schema
        runtime? (= :runtime (:count schema))]
    (cond
      (not (sequential? value))
      (invalid! path :not-sequential {:value value})

      (and runtime? (< capacity (count value)))
      (invalid! path :element-count-exceeds-capacity
                {:capacity     capacity
                 :actual-count (count value)})

      (and (not runtime?) (not= (:count schema) (count value)))
      (invalid! path :element-count-mismatch
                {:expected-count (:count schema)
                 :actual-count   (count value)})

      :else
      (let [element-schemas (if runtime?
                              (repeat (:element schema))
                              (:elements schema))]
        (doseq [[i element-schema element] (map vector (range) element-schemas value)]
          (validate-value element-schema (conj path i) element))))))

(defn- validate-value
  "Validates a handler value against its schema.
   schema - map, schema of the value
   path   - vector, path of the value in the block
   value  - the handler value for this schema
   Returns nil, throws ex-info with :path and :error on the first
   invalid value."
  [schema path value]
  (case (:kind schema)
    :scalar (when-let [error (glsl/scalar-error (:type schema) value)]
              (invalid! path error
                        {:expected (get-in schema [:type :glsl-name])
                         :value    value}))
    (:vector :matrix) (validate-scalars schema path value)
    :struct (validate-struct schema path value)
    :array  (validate-array schema path value)))

(defn validate
  "Validates handler output against a block schema. Throws ex-info
   with :path and :error on the first invalid value.
   schema - map {field-name schema}, from members->schema
   value  - the handler return value
   Returns nil when value is safe to serialize into the block buffer."
  [schema value]
  (validate-value {:kind :struct :fields schema} [] value))

(defn validate-uniform
  "Validates a plain uniform handler output against its schema node.
   Throws ex-info with :path and :error on the first invalid value.
   schema - map, uniform schema node (see types/uniforms->schema)
   value  - the handler return value
   Returns nil when value is safe to write to the uniform locations."
  [schema value]
  (validate-value schema [] value))

(defn- shift-offsets
  "Shifts every absolute offset of a schema by delta bytes.
   schema - map, schema
   delta  - int, byte offset to add
   Returns the schema."
  [schema delta]
  (case (:kind schema)
    (:scalar :vector :matrix)
    (update schema :offset + delta)

    :struct
    (update schema :fields (fn [fields]
                             (update-vals fields #(shift-offsets % delta))))

    :array
    (update schema :elements (fn [elements]
                               (mapv #(shift-offsets % delta) elements)))))

(defn- write-value!
  "Writes a validated handler value into a ByteBuffer according to its
   schema. Matrix values are read in column-major order, runtime array
   elements are written at their element stride.
   buffer - ByteBuffer
   schema - map, schema of the value
   value  - the validated handler value for this schema
   Returns nil."
  [buffer schema value]
  (case (:kind schema)
    :scalar
    (gl-buffer/put-scalar-at! buffer (:type schema) (:offset schema) value)

    :vector
    (let [{:keys [type offset]} schema
          scalar-bytes (gl-buffer/scalar-bytes type)]
      (doseq [[i scalar] (map-indexed vector value)]
        (gl-buffer/put-scalar-at! buffer type (+ offset (* i scalar-bytes)) scalar)))

    :matrix
    (let [{:keys [type offset matrix-stride]} schema
          {:keys [size total-locations]} type
          scalar-bytes (gl-buffer/scalar-bytes type)
          scalars      (vec value)]
      (doseq [column (range total-locations)
              row    (range size)]
        (gl-buffer/put-scalar-at! buffer type
                                  (+ offset (* column matrix-stride) (* row scalar-bytes))
                                  (scalars (+ (* column size) row)))))

    :struct
    (doseq [[fname fschema] (:fields schema)]
      (write-value! buffer fschema (get value fname)))

    :array
    (if (= :runtime (:count schema))
      (doseq [[i element] (map-indexed vector value)]
        (write-value! buffer
                      (shift-offsets (:element schema) (* i (:stride schema)))
                      element))
      (doseq [[element-schema element] (map vector (:elements schema) value)]
        (write-value! buffer element-schema element)))))

(defn block-size
  "Byte size of a block for a given handler value.
   schema - map {field-name schema}, from members->schema
   value  - map, validated handler output
   size   - int, introspected buffer-data-size (assumes one runtime
            array element)
   Returns size unchanged for fixed blocks; for blocks ending with a
   runtime array, size adjusted to the actual element count."
  [schema value size]
  (if-let [[fname fschema] (types/runtime-array schema)]
    (+ size (* (:stride fschema) (dec (count (get value fname)))))
    size))

(defn serialize
  "Creates and fills a ByteBuffer matching a block layout.
   schema - map {field-name schema}, from members->schema
   value  - map, validated handler output
   size   - int, block byte size, from block-size
   Returns a ByteBuffer ready for upload to the block GPU buffer."
  [schema value size]
  (let [buffer (BufferUtils/createByteBuffer size)]
    (doseq [[fname fschema] schema]
      (write-value! buffer fschema (get value fname)))
    buffer))

(def ^:private ABSENT
  "Sentinel telling a missing struct field from a nil value."
  (Object.))

(defn- scalar-checker
  "Compiles the validity predicate of one scalar, the compiled
   equivalent of glsl/scalar-error.
   glsl-type - map, GLSL type from TRANSPARENT-TYPE
   Returns (fn [scalar]) -> boolean."
  [glsl-type]
  (cond
    (glsl/bool-type? glsl-type)
    (fn [x] (or (boolean? x)
                (and (integer? x)
                     (<= Integer/MIN_VALUE x)
                     (<= x Integer/MAX_VALUE))))

    (glsl/unsigned-type? glsl-type)
    (fn [x] (and (integer? x) (<= 0 x) (<= x glsl/UNSIGNED-INT-MAX)))

    (:integer? glsl-type)
    (fn [x] (and (integer? x)
                 (<= Integer/MIN_VALUE x)
                 (<= x Integer/MAX_VALUE)))

    :else
    number?))

(defn- items-checker
  "Compiles the check of a sequential value holding exactly n items,
   each satisfying item?. Indexed and counted values are counted in
   O(1); other sequences are counted during the single traversal.
   n     - int, expected item count
   item? - function (fn [item]) -> boolean
   Returns (fn [value]) -> boolean."
  [n item?]
  (let [n (long n)]
    (fn [value]
      (and (sequential? value)
           (if (instance? clojure.lang.Indexed value)
             (and (== n (count value))
                  (loop [i 0]
                    (or (== i n)
                        (and (item? (nth value i)) (recur (inc i))))))
             (and (or (not (counted? value)) (== n (count value)))
                  (loop [s (seq value) i 0]
                    (if s
                      (and (< i n) (item? (first s)) (recur (next s) (inc i)))
                      (== i n)))))))))

(defn- runtime-items-checker
  "Compiles the check of a runtime array value: 0 to capacity
   elements, each satisfying element?. Other sequences than indexed
   ones are bounded during the single traversal.
   capacity - int, maximum element count
   element? - function (fn [element]) -> boolean
   Returns (fn [value]) -> boolean."
  [capacity element?]
  (let [capacity (long capacity)]
    (fn [value]
      (and (sequential? value)
           (if (instance? clojure.lang.Indexed value)
             (let [n (count value)]
               (and (<= n capacity)
                    (loop [i 0]
                      (or (== i n)
                          (and (element? (nth value i)) (recur (inc i)))))))
             (loop [s (seq value) i 0]
               (if s
                 (and (< i capacity) (element? (first s)) (recur (next s) (inc i)))
                 true)))))))

(defn- field-names
  "The field names of a struct schema as an array, strings interned so
   that the keys of handler maps, usually literals, match them by
   identity before any character comparison.
   fields - map {field-name schema}
   Returns an object array."
  ^objects [fields]
  (object-array (map #(if (string? %) (.intern ^String %) %) (keys fields))))

(declare ^:private node-checker)

(defn- fields-checker
  "Compiles the check of a struct value: a map holding every field,
   each valid, and no other key — counting the keys once every field
   is found rules unknown keys out.
   fields - map {field-name schema}
   Returns (fn [value]) -> boolean."
  [fields]
  (let [names    (field-names fields)
        checkers (object-array (map node-checker (vals fields)))
        n        (alength names)]
    (fn [value]
      (and (map? value)
           (== n (count value))
           (loop [i 0]
             (or (== i n)
                 (let [field (get value (aget names i) ABSENT)]
                   (and (not (identical? ABSENT field))
                        ((aget checkers i) field)
                        (recur (inc i))))))))))

(defn- node-checker
  "Compiles the check of one schema node.
   schema - map, schema node
   Returns (fn [value]) -> boolean."
  [schema]
  (case (:kind schema)
    :scalar (scalar-checker (:type schema))
    (:vector :matrix)
    (let [glsl-type (:type schema)]
      (items-checker (* (:size glsl-type) (get glsl-type :total-locations 1))
                     (scalar-checker glsl-type)))
    :struct (fields-checker (:fields schema))
    :array  (if (= :runtime (:count schema))
              (runtime-items-checker (get schema :capacity 1)
                                     (node-checker (:element schema)))
              (items-checker (:count schema)
                             (node-checker (first (:elements schema)))))))

(defn compile-checker
  "Compiles a block schema into its checker: a predicate telling
   whether a handler value is valid for the block, the compiled
   equivalent of validate — true exactly when validate does not
   throw. It never builds a report: on false, run validate for it.
   Elements of a fixed array share one checker, offsets playing no
   part in validity.
   schema - map {field-name schema}, from members->schema
   Returns (fn [value]) -> boolean."
  [schema]
  (fields-checker schema))

(defn validate-with-checker
  "Validates a handler value with a compiled checker, falling back on
   validate for the detailed report when the checker rejects it.
   Throws an internal error when validate accepts a value the checker
   rejected — an engine defect, never written to the GPU.
   schema  - map {field-name schema}, from members->schema
   checker - function, from compile-checker for schema
   value   - the handler return value
   Returns nil when value is safe to serialize, throws ex-info with
   :path and :error otherwise."
  [schema checker value]
  (when-not (checker value)
    (validate schema value)
    (throw (ex-info "Compiled checker rejected a value accepted by validate"
                    {:path   []
                     :error  :checker-mismatch
                     :schema schema})))
  nil)

(defn- scalar-serializer
  "Compiles the writer of one scalar of a GLSL type at an offset.
   glsl-type - map, GLSL type
   offset    - int, absolute byte offset of the scalar in the block
   Returns (fn [buffer delta scalar]) writing at offset + delta."
  [glsl-type offset]
  (let [offset (long offset)]
    (cond
      (:double? glsl-type)
      (fn [^ByteBuffer buffer delta scalar]
        (.putDouble buffer (int (+ offset (long delta))) (double scalar)))

      (glsl/bool-type? glsl-type)
      (fn [^ByteBuffer buffer delta scalar]
        (.putInt buffer (int (+ offset (long delta)))
                 (if (boolean? scalar)
                   (if scalar 1 0)
                   (unchecked-int (long scalar)))))

      (:integer? glsl-type)
      (fn [^ByteBuffer buffer delta scalar]
        (.putInt buffer (int (+ offset (long delta))) (unchecked-int (long scalar))))

      :else
      (fn [^ByteBuffer buffer delta scalar]
        (.putFloat buffer (int (+ offset (long delta))) (float scalar))))))

(defn- items-serializer
  "Compiles the writer of a sequential value: item i is written by the
   i-th writer. Indexed values are read with nth, other sequences
   traversed once.
   writers - vector of functions (fn [buffer delta item])
   Returns (fn [buffer delta value])."
  [writers]
  (let [writers (object-array writers)
        n       (alength writers)]
    (fn [buffer delta value]
      (if (instance? clojure.lang.Indexed value)
        (loop [i 0]
          (when (< i n)
            ((aget writers i) buffer delta (nth value i))
            (recur (inc i))))
        (loop [s (seq value) i 0]
          (when (and s (< i n))
            ((aget writers i) buffer delta (first s))
            (recur (next s) (inc i))))))))

(defn- scalars-serializer
  "Compiles the writer of a vector or matrix value: scalar i is written
   at offsets[i], in one primitive loop per value with the type
   dispatched at compile time. Sequences other than indexed ones are
   turned into a vector once.
   glsl-type - map, GLSL type
   offsets   - coll of int, absolute byte offset of each scalar
   Returns (fn [buffer delta value])."
  [glsl-type offsets]
  (let [offsets (long-array offsets)
        n       (alength offsets)
        indexed (fn [value]
                  (if (instance? clojure.lang.Indexed value) value (vec value)))]
    (cond
      (:double? glsl-type)
      (fn [^ByteBuffer buffer delta value]
        (let [delta (long delta) value (indexed value)]
          (dotimes [i n]
            (.putDouble buffer (int (+ delta (aget offsets i))) (double (nth value i))))))

      (glsl/bool-type? glsl-type)
      (fn [^ByteBuffer buffer delta value]
        (let [delta (long delta) value (indexed value)]
          (dotimes [i n]
            (let [scalar (nth value i)]
              (.putInt buffer (int (+ delta (aget offsets i)))
                       (if (boolean? scalar) (if scalar 1 0) (unchecked-int (long scalar))))))))

      (:integer? glsl-type)
      (fn [^ByteBuffer buffer delta value]
        (let [delta (long delta) value (indexed value)]
          (dotimes [i n]
            (.putInt buffer (int (+ delta (aget offsets i))) (unchecked-int (long (nth value i)))))))

      :else
      (fn [^ByteBuffer buffer delta value]
        (let [delta (long delta) value (indexed value)]
          (dotimes [i n]
            (.putFloat buffer (int (+ delta (aget offsets i))) (float (nth value i)))))))))

(declare ^:private node-serializer)

(defn- fields-serializer
  "Compiles the writer of a struct value, field by field.
   fields - map {field-name schema}
   Returns (fn [buffer delta value])."
  [fields]
  (let [names   (field-names fields)
        writers (object-array (map node-serializer (vals fields)))
        n       (alength names)]
    (fn [buffer delta value]
      (loop [i 0]
        (when (< i n)
          ((aget writers i) buffer delta (get value (aget names i)))
          (recur (inc i)))))))

(defn- runtime-items-serializer
  "Compiles the writer of a runtime array value: element i is written
   by the element writer shifted by i × stride.
   stride - int, byte stride between two consecutive elements
   writer - function (fn [buffer delta element]), element 0 writer
   Returns (fn [buffer delta value])."
  [stride writer]
  (let [stride (long stride)]
    (fn [buffer delta value]
      (let [delta (long delta)]
        (if (instance? clojure.lang.Indexed value)
          (let [n (count value)]
            (loop [i 0]
              (when (< i n)
                (writer buffer (+ delta (* i stride)) (nth value i))
                (recur (inc i)))))
          (loop [s (seq value) i 0]
            (when s
              (writer buffer (+ delta (* i stride)) (first s))
              (recur (next s) (inc i)))))))))

(defn- node-serializer
  "Compiles the writer of one schema node. Matrices are read in
   column-major order.
   schema - map, schema node
   Returns (fn [buffer delta value]) writing at the node's absolute
   offsets shifted by delta."
  [schema]
  (case (:kind schema)
    :scalar
    (scalar-serializer (:type schema) (:offset schema))

    :vector
    (let [{:keys [type offset]} schema
          scalar-bytes (gl-buffer/scalar-bytes type)]
      (scalars-serializer type (map #(+ offset (* % scalar-bytes))
                                    (range (:size type)))))

    :matrix
    (let [{:keys [type offset matrix-stride]} schema
          {:keys [size total-locations]} type
          scalar-bytes (gl-buffer/scalar-bytes type)]
      (scalars-serializer type (for [column (range total-locations)
                                     row    (range size)]
                                 (+ offset
                                    (* column matrix-stride)
                                    (* row scalar-bytes)))))

    :struct
    (fields-serializer (:fields schema))

    :array
    (if (= :runtime (:count schema))
      (runtime-items-serializer (:stride schema) (node-serializer (:element schema)))
      (items-serializer (mapv node-serializer (:elements schema))))))

(defn compile-serializer
  "Compiles a block schema into its serializer: a function writing a
   validated handler value into a provided ByteBuffer, the compiled
   equivalent of serialize. The value is written at the schema's
   offsets shifted by offset; bytes the value does not cover are left
   as they are.
   schema - map {field-name schema}, from members->schema
   Returns (fn [buffer offset value]) -> buffer, where buffer is a
   ByteBuffer, offset the int byte offset of the block in it and value
   a value accepted by the block's checker."
  [schema]
  (let [writer (fields-serializer schema)]
    (fn [buffer offset value]
      (writer buffer offset value)
      buffer)))
