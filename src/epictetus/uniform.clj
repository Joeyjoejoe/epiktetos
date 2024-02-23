(ns epictetus.uniform
  (:require [epictetus.event :as event]
            [epictetus.utils.reflection :refer [arity-eql?]])

  (:import  (org.lwjgl.opengl GL20 GL21 GL30 GL45)))

;; Uniform stages represent a moment in the renderning process when
;; to get a uniform value with a call to get-value multi method.
(defonce u-stages #::{:global  [:db]
                      :program [:db :entities]
                      :entity  [:db :entities :entity]})

(defmacro method->fn
  [meth arity]
  (let [args (take arity (map symbol [:a :b :c :d
                                      :e :f :g :h
                                      :i :j :k :l]))
        signature (vec args)
        body (conj args meth)]
    `(fn ~signature
       ~body)))

(def types-fn {:float (method->fn GL20/glUniform1f 2)
               :int   (method->fn GL20/glUniform1i 2)
               :mat4  (method->fn GL20/glUniformMatrix4fv 3)})



(defn register-uniform
  "Register a uniform whose value is the result of executing handler function,
  with "
  ([u-path handler]
   (if (arity-eql? handler 2)
     (register-uniform ::program u-path handler)
     (println "program uniform" u-path "callback signature must be: [db entities]")))
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


;; TODO Actually, the queue contains uniforms keywords used to retrieve
;;      uniforms data in u-map. We could remove u-map parameter by placing
;;      [:model]
;;      uniforms vallues in the queue instead:
;;      [{:name :model :location 1 :type :mat4} ...]
(defn consume-u!
  "Consume given uniforms queue"
  ([r-context u-map]
   (consume-u! r-context u-map (apply conj clojure.lang.PersistentQueue/EMPTY (keys u-map))))
  ([r-context u-map queue]
   (loop [u-queue  queue
          q-max    (count u-queue)
          next-max (dec q-max)]

     (let [u       (peek u-queue)
           handler (or (event/get-handler (::stage r-context)
                                          [(:program r-context) u])
                       (event/get-handler (::stage r-context) u))
           g-value  (get (:global-u r-context) u)
           {:keys   [location type]} (get u-map u)
           stage-ks (get u-stages (::stage r-context))
           handler-params (mapv #(get r-context %) stage-ks)]

       (when-let [value (if handler
                          (apply handler handler-params)
                          g-value)]

         (let [f (get types-fn type)
               ar2-args [location value]
               ;; (GL20/glUniformMatrix4fv location false value))
               ar3-args [location false value]]
           ;; TODO Replace cond on arity with system to link
           ;;      f parameters to data
           (cond (arity-eql? f 2) (apply f ar2-args)
                 (arity-eql? f 3) (apply f ar3-args))))

       (cond
         ;; All queue uniforms processed once.
         (= next-max 0) u-queue

         ;; Not a global or a program uniform ?
         ;; Re-enqueue it for last attempt in ::entity stage
         (and (nil? handler)
              (nil? g-value)) (recur (conj (pop u-queue) u)
                                     next-max
                                     (dec next-max))

         ;; Set following uniform
         :else (recur (pop u-queue) next-max (dec next-max)))))))

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
