(ns epiktetos.shader-input.data-test
  (:require [clojure.test :as t]
            [epiktetos.shader-input.data :as data]
            [epiktetos.shader-input.fixtures :refer [member scene-schema
                                                     valid-scene-data]]
            [epiktetos.shader-input.types :as types]))

(defn- validation-error
  "Returns the :error keyword of the ex-info thrown when validating
   value against scene-schema, or nil when validation passes.
   value - the handler output to validate"
  [value]
  (try
    (data/validate scene-schema value)
    nil
    (catch clojure.lang.ExceptionInfo e
      (:error (ex-data e)))))

(defn- validation-path
  "Returns the :path of the ex-info thrown when validating value
   against scene-schema, or nil when validation passes.
   value - the handler output to validate"
  [value]
  (try
    (data/validate scene-schema value)
    nil
    (catch clojure.lang.ExceptionInfo e
      (:path (ex-data e)))))

(t/deftest validate-test
  (t/testing "valid value passes"
    (t/is (nil? (data/validate scene-schema valid-scene-data))))

  (t/testing "top-level"
    (t/is (= :not-a-map (validation-error [1 2 3])))
    (t/is (= :missing-field (validation-error (dissoc valid-scene-data "time"))))
    (t/is (= :unknown-field
             (validation-error (assoc valid-scene-data "bogus" 1.0))))
    (t/is (= ["bogus"]
             (validation-path (assoc valid-scene-data "bogus" 1.0)))))

  (t/testing "scalars"
    (t/is (= :not-a-number
             (validation-error (assoc valid-scene-data "time" "abc"))))
    (t/is (= :float-for-integer-type
             (validation-error (assoc valid-scene-data "light_count" 1.5))))
    (t/is (= :integer-out-of-range
             (validation-error (assoc valid-scene-data "light_count" 5000000000)))))

  (t/testing "vectors and matrices"
    (t/is (= :not-sequential
             (validation-error (assoc valid-scene-data "camera_pos" 42))))
    (t/is (= :component-count-mismatch
             (validation-error (assoc valid-scene-data "camera_pos" [1.0 2.0]))))
    (t/is (= :component-count-mismatch
             (validation-error (assoc valid-scene-data "view" (repeat 15 1.0)))))
    (t/is (= :not-a-number
             (validation-error (assoc valid-scene-data "camera_pos" [1.0 nil 3.0])))))

  (t/testing "structs"
    (t/is (= :not-a-map
             (validation-error (assoc valid-scene-data "sun" [1.0 2.0 3.0]))))
    (t/is (= ["sun" "intensity"]
             (validation-path (update valid-scene-data "sun" dissoc "intensity")))))

  (t/testing "arrays"
    (t/is (= :element-count-mismatch
             (validation-error (assoc valid-scene-data "weights" [0.5 1.0]))))
    (t/is (= :not-sequential
             (validation-error (assoc valid-scene-data "lights" {}))))
    (t/is (= ["lights" 1 "intensity"]
             (validation-path
               (assoc-in valid-scene-data ["lights" 1 "intensity"] "x")))))

  (t/testing "booleans are accepted for bool types only"
    (let [schema (types/members->schema
                   "Flags" [(member "isActive" :bool :offset 0)])]
      (t/is (nil? (data/validate schema {"isActive" true})))
      (t/is (nil? (data/validate schema {"isActive" 1})))
      (t/is (thrown? clojure.lang.ExceptionInfo
                     (data/validate schema {"isActive" 1.0}))))
    (t/is (= :not-a-number
             (validation-error (assoc valid-scene-data "time" true)))))

  (t/testing "integer vector types reject floating-point components"
    (let [schema (types/members->schema
                   "Grid" [(member "cell" :ivec2 :offset 0)])]
      (t/is (thrown? clojure.lang.ExceptionInfo
                     (data/validate schema {"cell" [1 2.5]}))))))

(t/deftest serialize-test
  (t/testing "values are written at their introspected offsets"
    (let [^java.nio.ByteBuffer buf
          (data/serialize scene-schema valid-scene-data 192)]
      (t/is (= 192 (.capacity buf)))
      (t/is (= 1.0 (.getFloat buf 0)))
      (t/is (= 1.0 (.getFloat buf 20)))
      (t/is (= 1.0 (.getFloat buf 40)))
      (t/is (= 1.0 (.getFloat buf 60)))
      (t/is (= 5.0 (.getFloat buf 68)))
      (t/is (= 1.5 (.getFloat buf 76)))
      (t/is (= 2 (.getInt buf 80)))
      (t/is (= 0.5 (.getFloat buf 96)))
      (t/is (= 1.0 (.getFloat buf 112)))
      (t/is (= 0.75 (.getFloat buf 128)))
      (t/is (= 30.0 (.getFloat buf 152)))
      (t/is (= 3.0 (.getFloat buf 156)))
      (t/is (= 1.0 (.getFloat buf 180)))
      (t/is (= 0.5 (.getFloat buf 188)))))

  (t/testing "matrix columns follow the introspected matrix stride"
    (let [schema (types/members->schema
                   "M" [(member "m" :mat3 :offset 0 :matrix-stride 16)])
          ^java.nio.ByteBuffer buf
          (data/serialize schema {"m" [1.0 2.0 3.0
                                       4.0 5.0 6.0
                                       7.0 8.0 9.0]} 48)]
      (t/is (= 3.0 (.getFloat buf 8)))
      (t/is (= 0.0 (.getFloat buf 12)))
      (t/is (= 4.0 (.getFloat buf 16)))
      (t/is (= 9.0 (.getFloat buf 40)))))

  (t/testing "booleans are written as integers"
    (let [schema (types/members->schema
                   "Flags" [(member "isActive" :bool :offset 0)])
          ^java.nio.ByteBuffer buf
          (data/serialize schema {"isActive" true} 4)]
      (t/is (= 1 (.getInt buf 0)))))

  (t/testing "double types are written as doubles"
    (let [schema (types/members->schema
                   "D" [(member "d" :double :offset 0)])
          ^java.nio.ByteBuffer buf
          (data/serialize schema {"d" 2.5} 8)]
      (t/is (= 2.5 (.getDouble buf 0))))))
