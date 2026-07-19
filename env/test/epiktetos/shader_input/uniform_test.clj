(ns epiktetos.shader-input.uniform-test
  (:require [clojure.test :as t]
            [epiktetos.opengl.buffer :as gl-buffer]
            [epiktetos.shader-input.data :as data]
            [epiktetos.shader-input.fixtures :refer [member]]
            [epiktetos.shader-input.types :as types]))

(def ^:private uniforms
  [(member "uTime"       :float :location 0)
   (member "uWeights[0]" :float :location 1 :array-size 3)
   (member "uFog.color"  :vec3  :location 4)
   (member "uFog.density" :float :location 5)
   (member "uModel"      :mat4  :location 6)])

(def ^:private schema
  (types/uniforms->schema uniforms))

(t/deftest uniforms->schema-test
  (t/testing "leaves carry their kind, type and location"
    (t/is (= {:kind     :scalar
              :type     (gl-buffer/glsl-type :float)
              :location 0}
             (get schema "uTime")))
    (t/is (= :matrix (get-in schema ["uModel" :kind])))
    (t/is (= 6 (get-in schema ["uModel" :location]))))

  (t/testing "arrays expand to consecutive locations"
    (let [weights (get schema "uWeights")]
      (t/is (= :array (:kind weights)))
      (t/is (= 3 (:count weights)))
      (t/is (= [1 2 3] (mapv :location (:elements weights))))))

  (t/testing "struct leaves group under the uniform name"
    (let [fog (get schema "uFog")]
      (t/is (= :struct (:kind fog)))
      (t/is (= 4 (get-in fog [:fields "color" :location])))
      (t/is (= 5 (get-in fog [:fields "density" :location]))))))

(t/deftest uniform-shape-test
  (t/testing "shape strips locations at any depth"
    (let [other  (types/uniforms->schema
                   [(member "uFog.color"  :vec3  :location 10)
                    (member "uFog.density" :float :location 11)])]
      (t/is (= (types/uniform-shape (get schema "uFog"))
               (types/uniform-shape (get other "uFog"))))
      (t/is (nil? (get (types/uniform-shape (get schema "uTime"))
                       :location))))))

(t/deftest validate-uniform-test
  (t/testing "valid values pass"
    (t/is (nil? (data/validate-uniform (get schema "uTime") 1.5)))
    (t/is (nil? (data/validate-uniform (get schema "uWeights") [0.1 0.2 0.3])))
    (t/is (nil? (data/validate-uniform (get schema "uFog")
                                       {"color" [0.1 0.1 0.1] "density" 0.3}))))

  (t/testing "invalid values throw with a path"
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (data/validate-uniform (get schema "uTime") "soon")))
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (data/validate-uniform (get schema "uWeights") [0.1 0.2])))
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (data/validate-uniform (get schema "uFog")
                                          {"color" [0.1 0.1 0.1]})))))
