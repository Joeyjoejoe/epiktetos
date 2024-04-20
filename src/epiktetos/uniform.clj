(ns epiktetos.uniform
  (:require [epiktetos.event :as event]
            [epiktetos.utils.interop :refer [m->f]]
            [epiktetos.utils.reflection :refer [arity-eql?]])

  (:import  (org.lwjgl.opengl GL20 GL21 GL30 GL45)))

;; Uniform stages represent a moment in the renderning process when
;; to get a uniform value with a call to get-value multi method.
;; Stages values are vector of rendering context keys to us as
;; parameter for the handler being exectued during this stage.
(defonce UNIFORM-STAGES #::{:global  [:db]
                            :program [:db :entities]
                            :entity  [:db :entities :entity]})

;; TYPE-FN and TYPE-COLL-FN map GLSL types to
;; opengl method used to set values of uniforms
;; TODO - add struct support
;; TODO - add arrays support, ex: uniform float foo[6]
;; TODO - add "modern" types: unsigned int, double
(defonce TYPE-FN
  {:int         (m->f GL20/glUniform1i 2)
   :float       (m->f GL20/glUniform1f 2)
   :bool        (m->f GL20/glUniform1i 2)
   :sampler2D   (m->f GL20/glUniform1i 2)
   :samplerCube (m->f GL20/glUniform1i 2)})

(defonce TYPE-COLL-FN
  {;; Scalars types
   :int         (m->f GL20/glUniform1iv 2)
   :float       (m->f GL20/glUniform1fv 2)
   :bool        (m->f GL20/glUniform1iv 2)

   ;; Opaque types
   :sampler2D   (m->f GL20/glUniform1iv 2)
   :samplerCube (m->f GL20/glUniform1iv 2)

   ;; Vectors types
   :vec2  (m->f GL20/glUniform2fv 2)
   :vec3  (m->f GL20/glUniform3fv 2)
   :vec4  (m->f GL20/glUniform4fv 2)
   :ivec2 (m->f GL20/glUniform2iv 2)
   :ivec3 (m->f GL20/glUniform3iv 2)
   :ivec4 (m->f GL20/glUniform4iv 2)
   :bvec2 (m->f GL20/glUniform2iv 2)
   :bvec3 (m->f GL20/glUniform3iv 2)
   :bvec4 (m->f GL20/glUniform4iv 2)

   ;; Matrix types
   :mat2   (m->f GL20/glUniformMatrix2fv 3)
   :mat2x2 (m->f GL20/glUniformMatrix2fv 3)
   :mat2x3 (m->f GL21/glUniformMatrix2x3fv 3)
   :mat2x4 (m->f GL21/glUniformMatrix2x4fv 3)
   :mat3   (m->f GL20/glUniformMatrix3fv 3)
   :mat3x2 (m->f GL21/glUniformMatrix3x2fv 3)
   :mat3x3 (m->f GL20/glUniformMatrix3fv 3)
   :mat3x4 (m->f GL21/glUniformMatrix3x4fv 3)
   :mat4   (m->f GL20/glUniformMatrix4fv 3)
   :mat4x2 (m->f GL21/glUniformMatrix4x2fv 3)
   :mat4x3 (m->f GL21/glUniformMatrix4x3fv 3)
   :mat4x4 (m->f GL20/glUniformMatrix4fv 3)})

(defn matnxm?
  [typ]
  (-> #{:mat2 :mat2x2 :mat2x3 :mat2x4
        :mat3 :mat3x2 :mat3x3 :mat3x4
        :mat4 :mat4x2 :mat4x3 :mat4x4}
      typ
      boolean))

(defn register-uniform
  "Register a uniform whose value is the result of executing handler function,
  with "
  ([u-path handler]
   (if (arity-eql? handler 2)
     (register-uniform ::program u-path handler)
     (println "program uniform" u-path "callback signature must be: [db entities]")))
  ([ustage u-path handler]
   (if (ustage UNIFORM-STAGES)
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
  (if (arity-eql? handler 3)
    (register-uniform ::entity u-path handler)
    (println "entity uniform" u-path "callback signature must be: [db entities entity]")))

(defn locate-u
  "Return uniform location in program"
  [p-id u-name]
  (GL20/glGetUniformLocation ^Long p-id ^String u-name))

(defn compute-global-u
  "Compute value of all registered global uniforms"
  [db]
  (let [gus (event/get-handlers ::global)]
    (update-vals gus #(% db))))

(defn purge-u!
  "Purge u-queue of its stage uniforms by setting their
   values and returns a new queue of remaining uniforms.
   Side effects occurs when setting a uniform value through
   opengl, u-queue remains unchanged.

  Returns a queue with remaining uniforms that were not set
  during provided u-stage"
  ([u-queue u-stage r-context]
   (loop [u-queue  u-queue
          i-max    (-> u-queue count)]

     (let [[u-name u-type u-loc :as u]      (peek u-queue)
           {:keys [program global-u]} r-context
           handler      (or (event/get-handler u-stage [program u-name])
                            (event/get-handler u-stage u-name))
           g-value      (get global-u u-name)
           stage-ks     (get UNIFORM-STAGES u-stage)
           handler-args (mapv #(get r-context %) stage-ks)]

       ;; Set uniform value
       (when-let [u-val (if handler (apply handler handler-args)
                                    g-value)]

         ;; TODO Most of this could be cached on first uniform-handler execution
         ;; TODO (coll? u-val) => u-val not supported (clojure data structure vs java)
         (let [f (if (or (instance? java.nio.Buffer u-val)
                         (-> u-val class .isArray))
                   (get TYPE-COLL-FN u-type)
                   (get TYPE-FN u-type))]

            (if (matnxm? u-type)
              (f u-loc false u-val) ;; Add transpose parameter (false) for matrix u-types
              (f u-loc u-val))))

       (cond
         ;; All queue uniforms processed once.
         (= (dec i-max) 0) u-queue

         ;; Not a global or a program uniform ?
         ;; Re-enqueue it for last attempt in ::entity stage
         (and (nil? handler)
              (nil? g-value)) (recur (conj (pop u-queue) u)
                                     (dec i-max))

         ;; Handle next uniform
         :else (recur (pop u-queue) (dec i-max)))))))
