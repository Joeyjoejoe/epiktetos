(ns epiktetos.shader-input.serializer-test
  (:require [clojure.test :as t]
            [clojure.walk :as walk]
            [epiktetos.shader-input.data :as data]
            [epiktetos.shader-input.fixtures :refer [member particles-schema
                                                     scene-schema
                                                     valid-particles-value
                                                     valid-scene-data]]
            [epiktetos.shader-input.types :as types])
  (:import (org.lwjgl BufferUtils)))

(defn- as-lists
  "Returns value with every vector replaced by a list."
  [value]
  (walk/postwalk #(if (and (vector? %) (not (map-entry? %))) (apply list %) %) value))

(defn- as-lazy-seqs
  "Returns value with every vector replaced by a lazy sequence."
  [value]
  (walk/postwalk #(if (and (vector? %) (not (map-entry? %))) (map identity %) %) value))

(defn- buffer-bytes
  "Returns the content of a ByteBuffer as a vector of bytes."
  [^java.nio.ByteBuffer buffer]
  (mapv #(.get buffer (int %)) (range (.capacity buffer))))

(defn- interpreted-bytes
  [schema value size]
  (buffer-bytes (data/serialize schema value size)))

(defn- compiled-bytes
  [schema value size]
  (buffer-bytes ((data/compile-serializer schema)
                 (BufferUtils/createByteBuffer size) 0 value)))

(defn- validate-error
  "Returns the ex-data of validate on value, or nil when valid."
  [schema value]
  (try (data/validate schema value) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- checker-error
  "Returns the ex-data of validate-with-checker on value, or nil when
   valid."
  [schema value]
  (try (data/validate-with-checker schema (data/compile-checker schema) value) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(def ^:private element-members
  "SceneElements of hd-2d-maker: uint count, then Element structs of
   128 bytes (mat4, two vec4, eight uint)."
  (let [element (fn [name glsl-name offset & props]
                  (apply member (str "elements[0]." name) glsl-name :offset offset
                         :top-level-array-size 0 :top-level-array-stride 128 props))]
    (into [(member "count" :uint :offset 0 :top-level-array-size 1)
           (element "model" :mat4 16 :matrix-stride 16)
           (element "aabbMin" :vec4 80)
           (element "aabbMax" :vec4 96)]
          (map-indexed (fn [i name] (element name :uint (+ 112 (* 4 i))))
                       ["id" "kind" "facing" "listIndex"
                        "tieRank" "maskOffset" "maskWidth" "maskHeight"]))))

(def ^:private elements-schema
  (-> (types/members->schema "SceneElements" element-members)
      (types/set-capacity 3)))

(defn- element-value
  [i]
  {"model"     (mapv double (concat [1 0 0 0 0 1 0 0 0 0 1 0] [i 2 3 1]))
   "aabbMin"   [-1.0 -1.0 -1.0 1.0]
   "aabbMax"   [1.0 1.0 1.0 1.0]
   "id"        (+ 3000000000 i)
   "kind"      1
   "facing"    0
   "listIndex" i
   "tieRank"   0
   "maskOffset" (* 64 i)
   "maskWidth"  32
   "maskHeight" 32})

(def ^:private valid-elements-value
  {"count" 3 "elements" (mapv element-value (range 3))})

(def ^:private flags-schema
  (types/members->schema "Flags" [(member "isActive" :bool :offset 0)
                                  (member "mask" :bvec2 :offset 8)]))

(def ^:private doubles-schema
  (types/members->schema "D" [(member "d" :double :offset 0)
                              (member "v" :dvec2 :offset 16)]))

(def ^:private matrix-schema
  (types/members->schema "M" [(member "m" :mat3 :offset 0 :matrix-stride 16)]))

(def ^:private valid-cases
  "[label schema value size] — size from block-size for runtime arrays."
  [["std140 scene" scene-schema valid-scene-data 192]
   ["std430 particles, 2 elements" particles-schema valid-particles-value
    (data/block-size particles-schema valid-particles-value 32)]
   ["std430 particles, 0 elements" particles-schema {"count" 0 "particles" []} 32]
   ["std430 particles, capacity" particles-schema
    {"count" 4 "particles" (vec (repeat 4 {"position" [1.0 2.0 3.0] "energy" 4.0}))}
    (data/block-size particles-schema {"particles" (repeat 4 nil)} 32)]
   ["SceneElements replica, uint above 2^31" elements-schema valid-elements-value
    (data/block-size elements-schema valid-elements-value 144)]
   ["bool and bvec" flags-schema {"isActive" true "mask" [false 1]} 16]
   ["double and dvec" doubles-schema {"d" 2.5 "v" [1.0 -2.0]} 32]
   ["mat3 column stride" matrix-schema {"m" [1.0 2.0 3.0 4.0 5.0 6.0 7.0 8.0 9.0]} 48]])

(t/deftest serializer-bytes-test
  (doseq [[label schema value size] valid-cases
          [form value] [[:vectors value]
                        [:lists (as-lists value)]
                        [:lazy-seqs (as-lazy-seqs value)]]]
    (t/testing (str label " as " (name form))
      (t/is (= (interpreted-bytes schema value size)
               (compiled-bytes schema value size))))))

(t/deftest serializer-offset-test
  (t/testing "the block is written at the given byte offset"
    (let [buffer ((data/compile-serializer flags-schema)
                  (BufferUtils/createByteBuffer 32) 16 {"isActive" true "mask" [true false]})]
      (t/is (= 0 (.getInt buffer 0)))
      (t/is (= 1 (.getInt buffer 16)))
      (t/is (= 1 (.getInt buffer 24))))))

(t/deftest checker-accepts-valid-values-test
  (doseq [[label schema value _] valid-cases
          [form value] [[:vectors value]
                        [:lists (as-lists value)]
                        [:lazy-seqs (as-lazy-seqs value)]]]
    (t/testing (str label " as " (name form))
      (t/is (true? ((data/compile-checker schema) value)))
      (t/is (nil? (checker-error schema value))))))

(def ^:private invalid-cases
  "[schema value expected-error]"
  [[scene-schema [1 2 3] :not-a-map]
   [scene-schema (assoc valid-scene-data "time" "abc") :not-a-number]
   [scene-schema (assoc valid-scene-data "time" nil) :not-a-number]
   [scene-schema (assoc valid-scene-data "time" true) :not-a-number]
   [scene-schema (assoc valid-scene-data "light_count" 1.5) :float-for-integer-type]
   [scene-schema (assoc valid-scene-data "light_count" 5000000000) :integer-out-of-range]
   [scene-schema (assoc valid-scene-data "camera_pos" 42) :not-sequential]
   [scene-schema (assoc valid-scene-data "camera_pos" [1.0 2.0]) :component-count-mismatch]
   [scene-schema (assoc valid-scene-data "camera_pos" (map identity [1.0 2.0])) :component-count-mismatch]
   [scene-schema (assoc valid-scene-data "camera_pos" (map identity [1.0 2.0 3.0 4.0])) :component-count-mismatch]
   [scene-schema (assoc valid-scene-data "camera_pos" [1.0 nil 3.0]) :not-a-number]
   [scene-schema (assoc valid-scene-data "view" (repeat 15 1.0)) :component-count-mismatch]
   [scene-schema (assoc valid-scene-data "sun" [1.0 2.0 3.0]) :not-a-map]
   [scene-schema (update valid-scene-data "sun" dissoc "intensity") :missing-field]
   [scene-schema (assoc valid-scene-data "bogus" 1.0) :unknown-field]
   [scene-schema (-> valid-scene-data (dissoc "time") (assoc "bogus" 1.0)) :missing-field]
   [scene-schema (assoc valid-scene-data "weights" [0.5 1.0]) :element-count-mismatch]
   [scene-schema (assoc valid-scene-data "lights" {}) :not-sequential]
   [scene-schema (assoc-in valid-scene-data ["lights" 1 "intensity"] "x") :not-a-number]
   [particles-schema (assoc valid-particles-value "particles"
                            (vec (repeat 5 {"position" [1.0 2.0 3.0] "energy" 4.0})))
    :element-count-exceeds-capacity]
   [particles-schema (assoc valid-particles-value "particles"
                            (map identity (repeat 5 {"position" [1.0 2.0 3.0] "energy" 4.0})))
    :element-count-exceeds-capacity]
   [particles-schema (update-in valid-particles-value ["particles" 0] dissoc "energy") :missing-field]
   [elements-schema (assoc-in valid-elements-value ["elements" 1 "id"] -1) :integer-out-of-range]
   [elements-schema (assoc-in valid-elements-value ["elements" 1 "id"] 4294967296) :integer-out-of-range]
   [elements-schema (assoc-in valid-elements-value ["elements" 2 "kind"] 1.0) :float-for-integer-type]
   [flags-schema {"isActive" 1.0 "mask" [true true]} :float-for-integer-type]
   [flags-schema {"isActive" true "mask" [true "x"]} :not-a-number]])

(t/deftest checker-rejects-invalid-values-test
  (doseq [[schema value expected] invalid-cases]
    (t/testing (str expected " " (pr-str (validate-error schema value)))
      (t/is (false? ((data/compile-checker schema) value)))
      (t/is (= expected (:error (validate-error schema value))))
      (t/is (= (validate-error schema value) (checker-error schema value))))))

(t/deftest checker-mismatch-test
  (t/testing "a checker rejecting a value validate accepts is an engine defect"
    (t/is (= :checker-mismatch
             (:error (try (data/validate-with-checker scene-schema (constantly false)
                                                      valid-scene-data)
                          nil
                          (catch clojure.lang.ExceptionInfo e (ex-data e))))))))
