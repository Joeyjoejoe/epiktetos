(ns epiktetos.opengl.glsl-test
  (:require [clojure.test :as t]
            [epiktetos.opengl.buffer :as gl-buffer]
            [epiktetos.opengl.glsl :as glsl]))

(t/deftest unsigned-type?-test
  (t/is (glsl/unsigned-type? (gl-buffer/glsl-type :uint)))
  (t/is (glsl/unsigned-type? (gl-buffer/glsl-type :uvec3)))
  (t/is (not (glsl/unsigned-type? (gl-buffer/glsl-type :int))))
  (t/is (not (glsl/unsigned-type? (gl-buffer/glsl-type :bool)))))

(t/deftest scalar-error-range-test
  (t/testing "unsigned range"
    (t/is (nil? (glsl/scalar-error (gl-buffer/glsl-type :uint) 0)))
    (t/is (nil? (glsl/scalar-error (gl-buffer/glsl-type :uint) 4294967295)))
    (t/is (= :integer-out-of-range (glsl/scalar-error (gl-buffer/glsl-type :uint) -1)))
    (t/is (= :integer-out-of-range (glsl/scalar-error (gl-buffer/glsl-type :uvec2) 4294967296))))
  (t/testing "signed range unchanged"
    (t/is (nil? (glsl/scalar-error (gl-buffer/glsl-type :int) -1)))
    (t/is (= :integer-out-of-range (glsl/scalar-error (gl-buffer/glsl-type :int) 3000000000))))
  (t/testing "bools unchanged"
    (t/is (nil? (glsl/scalar-error (gl-buffer/glsl-type :bool) true)))))
