(ns epiktetos.opengl.buffer-test
  (:require [clojure.test :as t]
            [epiktetos.opengl.buffer :as buffer]))

(t/deftest packed-type-layout-test
  (t/testing "scalar count ignores packing"
    (t/is (= 5 (buffer/type-layout-count [[:vec3 :half] :ivec2])))
    (t/is (= 17 (buffer/type-layout-count
                  [:vec3 [:vec3 :byte-norm] [:ivec4 :ubyte] [:vec4 :ubyte-norm]
                   :vec3]))))

  (t/testing "packed entries shrink the byte layout"
    (let [buf (buffer/from-flat-layout [[:vec3 :half] [:ivec4 :ubyte]]
                                       [1.0 0.5 -1.0 1 2 3 4])]
      (t/is (= 10 (.limit buf)))
      (t/is (= (unchecked-short 0x3C00) (.getShort buf 0)))
      (t/is (= (unchecked-short 0x3800) (.getShort buf 2)))
      (t/is (= (unchecked-short 0xBC00) (.getShort buf 4)))
      (t/is (= 1 (.get buf 6)))
      (t/is (= 4 (.get buf 9)))))

  (t/testing "normalized bytes clamp and scale"
    (let [buf (buffer/from-flat-layout [[:vec3 :ubyte-norm]] [0.0 1.0 2.0])]
      (t/is (= 3 (.limit buf)))
      (t/is (= 0 (.get buf 0)))
      (t/is (= -1 (.get buf 1)))
      (t/is (= -1 (.get buf 2)))))

  (t/testing "unknown packing throws"
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (buffer/from-flat-layout [[:vec3 :float16]] [0.0 0.0 0.0])))))
