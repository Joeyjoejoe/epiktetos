(ns epiktetos.shader-parser
  (:require [clojure.java.io :as io]))

(def UNIFORMS-RE
  #"(?m)^\s*(?:layout\s*\(\s*(?<layout>[^)]*)\s*\)\s+)?(?<storage>uniform)(?:\s+(?<memory>readonly|writeonly|coherent|volatile|restrict))?(?:\s+(?<precision>highp|mediump|lowp))?\s+(?:(?<type>[a-zA-Z][a-zA-Z0-9_]*(?:\s*<[^>]*>)?)\s+(?<variables>[a-zA-Z0-9_]+(?:\s*\[\s*[^\]]*\])?(?:\s*,\s*[a-zA-Z0-9_]+(?:\s*\[\s*[^\]]*\])?)*)(?!\s*=\s*\{)|(?<blockname>[a-zA-Z][a-zA-Z0-9_]*)\s*\{\s*(?<blockcontent>[^}]*)\s*\}\s*(?<instance>[a-zA-Z0-9_]+(?:\s*\[\s*[^\]]*\])?)?)\s*;")

(def ATTRIBUTES-RE
  #"(?m)^\s*(?:layout\s*\(\s*(?<layout>[^)]*)\s*\)\s+)?(?:(?<interpolation>flat|smooth|noperspective|centroid|sample)\s+)?(?<storage>in|attribute)(?:\s+(?<precision>highp|mediump|lowp))?\s+(?:(?<type>[a-zA-Z][a-zA-Z0-9_]*)\s+(?<variables>[a-zA-Z][a-zA-Z0-9_]*(?:\s*\[\s*[^\]]*\s*\])?(?:\s*,\s*[a-zA-Z][a-zA-Z0-9_]*(?:\s*\[\s*[^\]]*\s*\])?)*))|(?<blockname>[a-zA-Z][a-zA-Z0-9_]*)\s*\{\s*(?<blockcontent>[^}]*)\s*\}\s*(?<instance>[a-zA-Z][a-zA-Z0-9_]*)\s*;")

(def SSBO-RE
  #"(?m)^\s*(?:layout\s*\(\s*(?<layout>[^)]*)\s*\)\s+)?(?:(?<memory>readonly|writeonly|coherent|volatile|restrict)(?:\s+(?:readonly|writeonly|coherent|volatile|restrict))*\s+)?(?<storage>buffer)\s+(?<blockname>[a-zA-Z][a-zA-Z0-9_]*)\s*\{\s*(?<blockcontent>[^}]*)\s*\}\s*(?<instance>[a-zA-Z][a-zA-Z0-9_]*(?:\s*\[\s*[^\]]*\s*\])?)?;")

(def TRANSFORM-FEEDBACK-RE
  #"(?m)^\s*layout\s*\(\s*(?<layout>(?:[^)]|xfb_buffer|xfb_offset|xfb_stride)[^)]*)\s*\)\s+(?:(?<interpolation>flat|smooth|noperspective|centroid|sample|invariant)(?:\s+(?:flat|smooth|noperspective|centroid|sample|invariant))*)?\s*(?<storage>out)\s+(?:(?<type>[a-zA-Z][a-zA-Z0-9_]*)\s+(?<variable>[a-zA-Z][a-zA-Z0-9_]*)(?:\s*\[\s*(?<arraysize>[^\]]*)\s*\])?|(?<blockname>[a-zA-Z][a-zA-Z0-9_]*)\s*\{\s*(?<blockcontent>[^}]*)\s*\}\s*(?<instance>[a-zA-Z][a-zA-Z0-9_]*(?:\s*\[\s*[^\]]*\s*\])?)?)(?:\s*=\s*(?<initializer>[^;]+))?\s*;")

(def STRUCT-RE
  #"(?m)^\s*(?<storage>struct)\s+(?<typename>[a-zA-Z][a-zA-Z0-9_]*)\s*\{\s*(?<blockcontent>[^}]*)\s*\}\s*(?:(?<variables>[a-zA-Z][a-zA-Z0-9_]*(?:\s*\[\s*[^\]]*\s*\])?(?:\s*,\s*[a-zA-Z][a-zA-Z0-9_]*(?:\s*\[\s*[^\]]*\s*\])?)*)?)?\s*;")

(def TYPEDEF-RE
  #"(?m)^\s*typedef\s+(?<originaltype>(?:struct(?:\s+[a-zA-Z][a-zA-Z0-9_]*)?\s*\{\s*[^}]*\s*\}|[a-zA-Z0-9_]+(?:\s*<[^>]*>)?(?:\s*\[\s*[^\]]*\s*\])?(?:\s+[a-zA-Z0-9_]+(?:\s*<[^>]*>)?(?:\s*\[\s*[^\]]*\s*\])?)*))(?:\s+|\s*\*\s*)(?<alias>[a-zA-Z][a-zA-Z0-9_]*)(?:\s*\[\s*[^\]]*\s*\])?\s*;")

(defn re-groupnames
  "Given a regular expression,
   returns its capture group names as keywords or nil"
  [regex]
  (let [pattern (if (instance? java.util.regex.Pattern regex)
                  regex
                  (re-pattern regex))
        pattern-str (.pattern pattern)
        named-groups (concat (re-seq #"\(\?<([^>]+)>" pattern-str)
                             (re-seq #"\(\?P<([^>]+)>" pattern-str))]
    (if (seq named-groups)
      (vec (distinct (map #(-> % second keyword) named-groups)))
      [])))

(defn re-group
  "Given a matcher and a group name,
   returns a map of the group name and the value captured by m"
  [m group]
  (if-let [value (.group m (name group))]
    {group value}))

(defn re-map
  "Given a matcher or a string and a regex,
   returns a map of the regex's capture groups
   and their matched values"
  ([m]
   (let [re (.pattern m)
         groups  (re-groupnames re)]

     (loop [result []
            match (re-find m)]
       (if-not match
         result
         (recur (conj result
                      (reduce #(merge %1 (re-group m %2))
                              {:match (first match)}
                              groups))
                (re-find m))))))
  ([re s]
   (let [m (re-matcher re s)]
     (re-map m))))




(comment

  (let [uniforms (-> "shaders/uniforms-syntax.test"
                     io/resource
                     slurp)

        attributes (-> "shaders/attributes-syntax.test"
                     io/resource
                     slurp)

        ssbo     (-> "shaders/ssbo-syntax.test"
                     io/resource
                     slurp)

        transform-feedback (-> "shaders/transform-feedback-syntax.test"
                     io/resource
                     slurp)

        custom-types (-> "shaders/custom-types-syntax.test"
                     io/resource
                     slurp)]
    (->> uniforms
         (re-map UNIFORMS-RE)
         clojure.pprint/pprint)

    (->> attributes
         (re-map ATTRIBUTES-RE)
         clojure.pprint/pprint)

    (->> ssbo
         (re-map SSBO-RE)
         clojure.pprint/pprint)

    (->> transform-feedback
         (re-map TRANSFORM-FEEDBACK-RE)
         clojure.pprint/pprint)

    (->> custom-types
         (re-map STRUCT-RE)
         clojure.pprint/pprint)

    (->> custom-types
         (re-map TYPEDEF-RE)
         clojure.pprint/pprint)
  )

  ;; CPU buffer init
  ;; - transform feedback buffer : glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER)
  ;; - vertex attributes : glVertexAttribPointer
  ;; - Image types (image1D etc..) : glBindImageTexture
  ;; - Sampler types (sampler1D, sampler2D etc...) : glBindTexture(GL_TEXTURE_1D), glBindTexture(GL_TEXTURE_2D) etc..
  ;; - UBO (uniform with custom type) : glBindBufferBase(GL_UNIFORM_BUFFER)
  ;; - SSBO : glBindBufferBase(GL_SHADER_STORAGE_BUFFER)
  ;; - Atomic counter : glBindBufferBase(GL_ATOMIC_COUNTER_BUFFER)
  ;; - uniform classiques



  )
