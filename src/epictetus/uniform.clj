(ns epictetus.uniform
  (:require [epictetus.event :as event]
            [epictetus.utils.reflection :refer [arity-eql?]])

  (:import  (org.lwjgl.opengl GL20 GL21 GL30 GL45)))

;; Uniform stages represent a moment in the renderning process when
;; to get a uniform value with a call to get-value multi method.
(defonce u-stages #{::global ::program ::entity})

(defn register-uniform
  "Register a uniform whose value is the result of executing handler function,
  with "
  ([u-path handler]
   (if (arity-eql? handler 2)
     (register-uniform ::program u-path handler)
     (println "program uniform" u-path "callback signature must be: [db p-entities]")))
  ([ustage u-path handler]
   (if (ustage u-stages)
     (event/register ustage u-path handler)
     (println "Unknown uniform stage" ustage))))

(defn register-global-uniform
  "Register a uniform whose computed value is the same for all programs
  in the current Loop iteration."
  [u-name handler]
  (if (arity-eql? handler 1)
    (register-uniform ::global u-name handler)
    (println "global uniform" u-name "callback signature must be: [db]")))

(defn register-entity-uniform
  "Register a uniform whose value is computed based on entity data"
  [u-path handler]
  (if (arity-eql? handler 2)
    (register-uniform ::entity u-path handler)
    (println "entity uniform" u-path "callback signature must be: [db entity]")))


;; Uniform path hierarchy :
;; :foo < [:program-name :foo] <


;;      - texture uniforms have sampler type
;;        (https://www.khronos.org/opengl/wiki/Sampler_(GLSL))

;; parsed from shader at startup [::name ::location ::type]
;; {::name     :default
;;  ::location 1
;;  ::type     :sampler2D
;;  ::stage    :program ;; or :entity
;;  ::get-fn   'foo ;; ran on each loop iteration
;;  ::set-fn   GL20/glUniformMatrix4fv}
;;
;;
;;
;; (defmacro method->fn
;;   [meth arity]
;;   (let [args (take arity (map symbol [:a :b :c :d
;;                                       :e :f :g :h
;;                                       :i :j :k :l]))
;;         signature (vec args)
;;         body (conj args meth)]
;;   `(fn ~signature
;;      ~body)))
;;
;; ((method->fn GL20/glUniform1f 2) 2 1)
;;
;; {:float (method->fn GL20/glUniform1f 2)
;;  : (method->fn GL20/glUniform1fv 3)
;; : (method->fn GL20/glUniform1i 2)
;; : (method->fn GL20/glUniform1iv 3)
;; : (method->fn GL30/glUniform1ui 2)
;; : (method->fn GL30/glUniform1uiv 3)
;; : (method->fn GL20/glUniform2f 3)
;; : (method->fn GL20/glUniform2fv 3)
;; : (method->fn GL20/glUniform2i 3)
;; : (method->fn GL20/glUniform2iv 3)
;;
;; : (method->fn GL30/glUniform2uiv 3)
;; : (method->fn GL20/glUniform3f 4)
;; : (method->fn GL20/glUniform3fv 3)
;; : (method->fn GL20/glUniform3i 4)
;; : (method->fn GL20/glUniform3iv 3)
;; : (method->fn GL30/glUniform3ui 4)
;; : (method->fn GL30/glUniform3uiv 3)
;; : (method->fn GL20/glUniform4f 5)
;; : (method->fn GL20/glUniform4fv 3)
;; : (method->fn GL20/glUniform4i 5)
;; : (method->fn GL20/glUniform4iv 3)
;; : (method->fn GL30/glUniform4ui 5)
;; : (method->fn GL30/glUniform4uiv 3)
;; : (method->fn GL20/glUniformMatrix2fv 4)
;; : (method->fn GL21/glUniformMatrix2x3fv 4)
;; : (method->fn GL21/glUniformMatrix2x4fv 4)
;; : (method->fn GL20/glUniformMatrix3fv 4)
;; : (method->fn GL21/glUniformMatrix3x2fv 4)
;; : (method->fn GL21/glUniformMatrix3x4fv 4)
;; : (method->fn GL20/glUniformMatrix4fv 3)
;; : (method->fn GL21/glUniformMatrix4x2fv 4)
;; : (method->fn GL21/glUniformMatrix4x3fv 4)
