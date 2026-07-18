(ns epiktetos.shader-input.data
  (:require [epiktetos.opengl.buffer :as gl-buffer]
            [epiktetos.opengl.glsl :as glsl])
  (:import (org.lwjgl BufferUtils)))

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
   Returns nil, throws on the first invalid element."
  [schema path value]
  (cond
    (not (sequential? value))
    (invalid! path :not-sequential {:value value})

    (not= (:count schema) (count value))
    (invalid! path :element-count-mismatch
              {:expected-count (:count schema)
               :actual-count   (count value)})

    :else
    (doseq [[i element-schema element] (map vector (range) (:elements schema) value)]
      (validate-value element-schema (conj path i) element))))

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

(defn- write-value!
  "Writes a validated handler value into a ByteBuffer according to its
   schema. Matrix values are read in column-major order.
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
    (doseq [[element-schema element] (map vector (:elements schema) value)]
      (write-value! buffer element-schema element))))

(defn serialize
  "Creates and fills a ByteBuffer matching a block layout.
   schema - map {field-name schema}, from members->schema
   value  - map, validated handler output
   size   - int, block buffer-data-size in bytes
   Returns a ByteBuffer ready for upload to the block GPU buffer."
  [schema value size]
  (let [buffer (BufferUtils/createByteBuffer size)]
    (doseq [[fname fschema] schema]
      (write-value! buffer fschema (get value fname)))
    buffer))
