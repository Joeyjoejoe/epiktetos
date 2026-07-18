(ns epiktetos.shader-input.types-test
  (:require [clojure.test :as t]
            [epiktetos.opengl.buffer :as gl-buffer]
            [epiktetos.shader-input.fixtures :refer [member particles-members
                                                     particles-schema
                                                     scene-schema]]
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

(t/deftest runtime-array-test
  (t/testing "unsized struct arrays produce a runtime array schema"
    (let [schema    (types/members->schema "Particles" particles-members)
          particles (get schema "particles")]
      (t/is (= :scalar (:kind (get schema "count"))))
      (t/is (= :array (:kind particles)))
      (t/is (= :runtime (:count particles)))
      (t/is (= 16 (:stride particles)))
      (t/is (= :struct (get-in particles [:element :kind])))
      (t/is (= 16 (get-in particles [:element :fields "position" :offset])))
      (t/is (= 28 (get-in particles [:element :fields "energy" :offset])))))

  (t/testing "unsized basic-type arrays produce a leaf element"
    (let [schema (types/members->schema
                   "Data" [(member "data[0]" :float :offset 0 :array-size 0
                                   :top-level-array-size 0
                                   :top-level-array-stride 4)])
          data   (get schema "data")]
      (t/is (= :runtime (:count data)))
      (t/is (= 4 (:stride data)))
      (t/is (= :scalar (get-in data [:element :kind])))
      (t/is (= 0 (get-in data [:element :offset])))))

  (t/testing "unsized basic-type arrays signaled by array-size only"
    (let [schema (types/members->schema
                   "Skeleton" [(member "bones[0]" :mat4 :offset 0
                                       :array-size 0 :array-stride 64
                                       :matrix-stride 16
                                       :top-level-array-size 1
                                       :top-level-array-stride 0)])
          bones  (get schema "bones")]
      (t/is (= :runtime (:count bones)))
      (t/is (= 64 (:stride bones)))
      (t/is (= :matrix (get-in bones [:element :kind])))))

  (t/testing "runtime-array finds the runtime array entry"
    (t/is (= "particles" (first (types/runtime-array particles-schema))))
    (t/is (nil? (types/runtime-array scene-schema))))

  (t/testing "set-capacity stores the capacity on the runtime array"
    (t/is (= 4 (get-in particles-schema ["particles" :capacity])))
    (t/is (= scene-schema (types/set-capacity scene-schema 8)))))
