(ns epiktetos.spec.glsl-type
  (:require [clojure.spec.alpha :as s]))

(def INT32-MIN Integer/MIN_VALUE)
(def INT32-MAX Integer/MAX_VALUE)

;;; Basic scalar types
(s/def ::float float?) ;; float (32-bit single precision floating point)
(s/def ::double double?) ;; double (64-bit double precision floating point)
(s/def ::int integer?)
(s/def ::int32 (s/and integer? #(<= INT32-MIN % INT32-MAX))) ;; int (32-bit signed integer)
(s/def ::uint (s/and integer? #(>= % 0) #(< % 4294967296))) ;; uint (32-bit unsigned integer)
(s/def ::bool boolean?) ;; bool (boolean value)

;;; Additional scalar types in GLSL 4.6+
(s/def ::short (s/and integer? #(<= Short/MIN_VALUE % Short/MAX_VALUE))) ;; short (16-bit signed integer)
(s/def ::ushort (s/and integer? #(>= % 0) #(< % 65536))) ;; ushort (16-bit unsigned integer)
(s/def ::long integer?) ;; long (64-bit signed integer)
(s/def ::int64 (s/and integer? #(<= Long/MIN_VALUE % Long/MAX_VALUE)))
(s/def ::ulong (s/and integer? #(>= % 0) #(<= % 18446744073709551615N))) ;; ulong (64-bit unsigned integer)

;; half (16-bit half-precision floating point)
;; Note: Clojure doesn't natively support half-precision floats,
;; so we use a float with value range constraints
(s/def ::half (s/and float?
                     #(or (zero? %)
                          (and (>= (Math/abs %) 5.96e-8)
                               (<= (Math/abs %) 65504.0)))))

;;; Range-constrained versions (for GLSL normalized values)
(s/def ::normalized-float (s/and float? #(>= % 0.0) #(<= % 1.0))) ;; Normalized float values (0.0 to 1.0)
(s/def ::signed-normalized-float (s/and float? #(>= % -1.0) #(<= % 1.0))) ;; Signed normalized float values (-1.0 to 1.0)

;; Map of all GLSL scalar types to their Clojure specs
(def glsl-scalar-specs
  {:float       ::float
   :double      ::double
   :int         ::int
   :uint        ::uint
   :bool        ::bool
   :short       ::short
   :ushort      ::ushort
   :long        ::long
   :ulong       ::ulong
   :half        ::half})

(comment

  (s/explain ::int32 24999999999999999999999999999999)





)


