(ns epiktetos.shader-input.types-test
  (:require [clojure.test :as t]
            [epiktetos.opengl.buffer :as gl-buffer]
            [epiktetos.shader-input.fixtures :refer [member scene-schema]]
            [epiktetos.shader-input.types :as types]))

(t/deftest members->schema-test
  (t/testing "scalar, vector and matrix leaves"
    (t/is (= {:kind          :matrix
              :type          (gl-buffer/glsl-type :mat4)
              :offset        0
              :matrix-stride 16}
             (get scene-schema "view")))
    (t/is (= :vector (:kind (get scene-schema "camera_pos"))))
    (t/is (= :scalar (:kind (get scene-schema "time")))))

  (t/testing "basic-type arrays are expanded from array-size and array-stride"
    (let [weights (get scene-schema "weights")]
      (t/is (= :array (:kind weights)))
      (t/is (= 3 (:count weights)))
      (t/is (= [96 112 128] (mapv :offset (:elements weights))))))

  (t/testing "struct members are grouped under their field name"
    (let [sun (get scene-schema "sun")]
      (t/is (= :struct (:kind sun)))
      (t/is (= #{"position" "intensity"} (set (keys (:fields sun)))))
      (t/is (= 156 (get-in sun [:fields "intensity" :offset])))))

  (t/testing "arrays of structs keep per-element introspected offsets"
    (let [lights (get scene-schema "lights")]
      (t/is (= :array (:kind lights)))
      (t/is (= 2 (:count lights)))
      (t/is (= :struct (get-in lights [:elements 0 :kind])))
      (t/is (= 176 (get-in lights [:elements 1 :fields "position" :offset])))
      (t/is (= 188 (get-in lights [:elements 1 :fields "intensity" :offset])))))

  (t/testing "block name prefix of instanced blocks is stripped"
    (let [schema (types/members->schema
                   "Scene" [(member "Scene.time" :float :offset 0)])]
      (t/is (contains? schema "time"))))

  (t/testing "row-major members are rejected"
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (types/members->schema
                     "Scene" [(member "view" :mat4 :offset 0
                                      :matrix-stride 16 :is-row-major 1)])))))
