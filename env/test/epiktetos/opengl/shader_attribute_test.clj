(ns epiktetos.opengl.shader-attribute-test
  (:require [clojure.test :refer [deftest testing is]]
            [epiktetos.opengl.shader-attribute :as attribute])
  (:import (org.lwjgl.opengl GL11 GL30)))

(def vec3-local
  {:varname "vLocal" :location 0
   :type {:glsl-name :vec3 :base-type GL11/GL_FLOAT :size 3 :bytes 12}})

(def vec2-local
  {:varname "vLocal" :location 0
   :type {:glsl-name :vec2 :base-type GL11/GL_FLOAT :size 2 :bytes 8}})

(def vec3-color
  {:varname "vColor" :location 1
   :type {:glsl-name :vec3 :base-type GL11/GL_FLOAT :size 3 :bytes 12}})

(def ivec4-joints
  {:varname "vJoints" :location 1
   :type {:glsl-name :ivec4 :base-type GL11/GL_INT :size 4 :bytes 16
          :integer? true}})

(def mat4-model
  {:varname "iModel" :location 2
   :type {:glsl-name :mat4 :base-type GL11/GL_FLOAT :size 4 :bytes 64
          :total-locations 4}})

(def dmat3-frame
  {:varname "iFrame" :location 2
   :type {:glsl-name :dmat3 :base-type GL11/GL_DOUBLE :size 3 :bytes 72
          :total-locations 3 :double? true}})

(defn- attribs
  "Introspected attributes map keyed by varname, as attributes-infos
  returns them."
  [& attributes]
  (into {} (map (juxt :varname identity) attributes)))

(deftest identity-follows-introspected-types
  (testing "same DSL, different introspected types, different vertex-formats"
    (let [vb-map {:layout ["vLocal"] :handler :mesh}
          as-vec3 (attribute/resolve-vertex-format (attribs vec3-local) 0 vb-map)
          as-vec2 (attribute/resolve-vertex-format (attribs vec2-local) 0 vb-map)]
      (is (not= (:vertex-format as-vec3) (:vertex-format as-vec2)))
      (is (= 12 (get-in as-vec3 [:vertex-format :stride])))
      (is (= 8  (get-in as-vec2 [:vertex-format :stride])))))

  (testing "handlers do not participate in the vertex-format"
    (let [prog-attribs (attribs vec3-local)
          with-keyword (attribute/resolve-vertex-format
                         prog-attribs 0 {:layout ["vLocal"] :handler :mesh})
          with-fn      (attribute/resolve-vertex-format
                         prog-attribs 0 {:layout ["vLocal"] :handler (fn [e] e)})]
      (is (= (:vertex-format with-keyword) (:vertex-format with-fn))))))

(deftest interleaved-offsets-and-stride
  (let [{:keys [vertex-format vbo]}
        (attribute/resolve-vertex-format
          (attribs vec3-local vec3-color) 0
          {:layout ["vLocal" "vColor"] :handler :vertices})]
    (is (= 24 (:stride vertex-format)))
    (is (= [0 12] (mapv :offset (:attributes vertex-format))))
    (is (= [0 1]  (mapv :location (:attributes vertex-format))))
    (is (= [:vec3 :vec3] (:type-layout vbo)))))

(deftest matrix-columns-expand-to-locations
  (testing "one entry per column, float matrices step by one location"
    (let [{:keys [vertex-format]}
          (attribute/resolve-vertex-format
            (attribs mat4-model) 0
            {:layout ["iModel"] :handler :models :divisor 1})]
      (is (= 1 (:divisor vertex-format)))
      (is (= [2 3 4 5] (mapv :location (:attributes vertex-format))))
      (is (= [0 16 32 48] (mapv :offset (:attributes vertex-format))))
      (is (= [4 4 4 4] (mapv :size (:attributes vertex-format))))))

  (testing "wide double matrices step by two locations"
    (let [{:keys [vertex-format]}
          (attribute/resolve-vertex-format
            (attribs dmat3-frame) 0
            {:layout ["iFrame"] :handler :frames})]
      (is (= [2 4 6] (mapv :location (:attributes vertex-format))))
      (is (= [:double :double :double]
             (mapv :kind (:attributes vertex-format)))))))

(deftest packing-and-normalize-shape-the-format
  (testing "packing changes base-type, normalized flag and stride"
    (let [{:keys [vertex-format vbo]}
          (attribute/resolve-vertex-format
            (attribs vec3-local vec3-color) 0
            {:layout ["vLocal" "vColor"] :handler :vertices
             :packing {"vColor" :ubyte-norm}})
          [local color] (:attributes vertex-format)]
      (is (= 15 (:stride vertex-format)))
      (is (= GL11/GL_FLOAT (:base-type local)))
      (is (= GL11/GL_UNSIGNED_BYTE (:base-type color)))
      (is (true? (:normalized? color)))
      (is (= [:vec3 [:vec3 :ubyte-norm]] (:type-layout vbo)))))

  (testing "normalize set raises the normalized flag"
    (let [{:keys [vertex-format]}
          (attribute/resolve-vertex-format
            (attribs vec3-color) 0
            {:layout ["vColor"] :handler :colors :normalize #{"vColor"}})]
      (is (true? (:normalized? (first (:attributes vertex-format)))))))

  (testing "integer attributes keep an integer kind"
    (let [{:keys [vertex-format]}
          (attribute/resolve-vertex-format
            (attribs ivec4-joints) 0
            {:layout ["vJoints"] :handler :joints})]
      (is (= :integer (:kind (first (:attributes vertex-format))))))))

(deftest validations-run-at-resolution
  (testing "unknown layout attribute throws"
    (is (thrown? Exception
                 (attribute/resolve-vertex-format
                   (attribs vec3-local) 0
                   {:layout ["vTypo"] :handler :mesh}))))

  (testing "unknown normalize attribute throws"
    (is (thrown? Exception
                 (attribute/resolve-vertex-format
                   (attribs vec3-local) 0
                   {:layout ["vLocal"] :handler :mesh :normalize #{"vTypo"}}))))

  (testing "unknown packing format throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (attribute/resolve-vertex-format
                   (attribs vec3-local) 0
                   {:layout ["vLocal"] :handler :mesh
                    :packing {"vLocal" :float16}}))))

  (testing "packing on a matrix attribute throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (attribute/resolve-vertex-format
                   (attribs mat4-model) 0
                   {:layout ["iModel"] :handler :models
                    :packing {"iModel" :half}})))))
