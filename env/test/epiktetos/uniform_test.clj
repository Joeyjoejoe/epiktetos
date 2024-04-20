(ns epiktetos.uniform-test
  (:require [clojure.test :as t]
            [epiktetos.core :as core :refer [reg-u]]
            [epiktetos.utils.buffer :refer [float-buffer int-buffer]]))

;; Uniform functions must be pure

;; GLSL   uniform float floatUniq;
;; OPENGL glUniform1f(int location, float v0)
(reg-u :floatUniq
       (fn [_] 0.0))

;; GLSL   uniform float floatArray[2];
;; OPENGL glUniform1fv(int location, float[] value)
(reg-u :floatArray
       (fn [_] [0.0 1.0])) ;; (float-array [...]) may be needed

;; GLSL   uniform float floatBuffer[2];
;; OPENGL glUniform1fv(int location, FloatBuffer value)
(reg-u :floatBuffer
       (fn [_] (float-buffer [0.0 1.0])))



