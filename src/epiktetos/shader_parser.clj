(ns epiktetos.shader-parser
  (:require [clojure.java.io :as io]))

(def UNIFORMS-RE
  #"(?m)^\s*(?:layout\s*\(\s*(?<layout>[^)]*)\s*\)\s+)?(?<storage>uniform)(?:\s+(?<memory>readonly|writeonly|coherent|volatile|restrict))?(?:\s+(?<precision>highp|mediump|lowp))?\s+(?:(?<type>[a-zA-Z][a-zA-Z0-9_]*(?:\s*<[^>]*>)?)\s+(?<variables>[a-zA-Z0-9_]+(?:\s*\[\s*[^\]]*\])?(?:\s*,\s*[a-zA-Z0-9_]+(?:\s*\[\s*[^\]]*\])?)*)(?!\s*=\s*\{)|(?<blockname>[a-zA-Z][a-zA-Z0-9_]*)\s*\{\s*(?<blockcontent>[^}]*)\s*\}\s*(?<instance>[a-zA-Z0-9_]+(?:\s*\[\s*[^\]]*\])?)?)\s*;")

(def ATTRIBUTES-RE
  #"(?m)^\s*(?:layout\s*\(\s*(?<layout>[^)]*)\s*\)\s+)?(?:(?<interpolation>flat|smooth|noperspective|centroid|sample)\s+)?(?<storage>in|attribute)(?:\s+(?<precision>highp|mediump|lowp))?\s+(?:(?<type>[a-zA-Z][a-zA-Z0-9_]*)\s+(?<variables>[a-zA-Z][a-zA-Z0-9_]*(?:\s*\[\s*[^\]]*\s*\])?(?:\s*,\s*[a-zA-Z][a-zA-Z0-9_]*(?:\s*\[\s*[^\]]*\s*\])?)*)|\s*(?<blockname>[a-zA-Z][a-zA-Z0-9_]*)\s*\{\s*(?<blockcontent>[^}]*)\s*\}\s*(?<instance>[a-zA-Z][a-zA-Z0-9_]*))\s*;")

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
         (if-not (empty? result)
           result)
         (recur (conj result
                      (reduce #(merge %1 (re-group m %2))
                              {:match (first match)}
                              groups))
                (re-find m))))))
  ([re s]

   (if (nil? s)
     (throw (Exception. "s argument is nil, must be a String")))

   (let [m (re-matcher re s)]
     (re-map m))))


(defn parse-shader
  [shader-path]

  (if-not (io/resource shader-path)
    (throw (Exception. (str "resource path not found \"" shader-path "\""))))

  (let [src                (-> shader-path io/resource slurp)
        attrs              (re-map ATTRIBUTES-RE src)
        ssbos              (re-map SSBO-RE src)
        uniforms           (re-map UNIFORMS-RE src)
        transform-feedback (re-map TRANSFORM-FEEDBACK-RE src)
        custom-types       (concat (re-map STRUCT-RE src)
                                   (re-map TYPEDEF-RE src))]
      (cond-> {}
        attrs                    (assoc :attributes attrs)
        ssbos                    (assoc :ssbos ssbos)
        uniforms                 (assoc :uniforms uniforms)
        transform-feedback       (assoc :transform-feedback transform-feedback)
        (not-empty custom-types) (assoc :custom-types custom-types))))

(comment


  ;; TODO parse that !

  ;;:attributes
  {:variables "transforms[8]"
   :layout "location = 21, component = 1"
   :variables "aTexCoord1[], aTexCoord2[]"
   }

  ;; uniforms
  {:variables "varName1, varName2, varName3"
   :layout "binding = 57, rgba8i",
   :blockcontent "vec3 globalAmbient;\n    int lightCount;\n    struct Light {\n        vec3 position;\n        vec3 color;\n        float intensity;\n
   float range;\n    "
   :blockcontent  "vec3 position;\n    vec3 direction;\n    float near;\n    float far;\n    mat4 viewProjection;\n"}






  (do (clojure.pprint/pprint (->> (parse-shader "shaders/uniforms-syntax.test")
                                  :uniforms))
                                  ;; :uniforms
                                  ;; :custom-types
                                  (mapcat keys)
                                  set))
      nil)

  #{:interpolation
  :instance
  :layout
  :precision
  :type
  :variables
  :blockname
  :blockcontent
  :storage
  :match}

  :attributes
  #{:layout :type :variables :storage :match}

  :uniforms
  #{:instance :layout :precision :type :variables :blockname :blockcontent :storage :match}

  :custom-types
  #{:typename :variables :blockcontent :storage :match}




  (let [
        uniforms (-> "shaders/uniforms-syntax.test"
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
   ;; (->> uniforms
   ;;      (re-map UNIFORMS-RE)
   ;;      clojure.pprint/pprint)

   ;; (->> attributes
   ;;      (re-map ATTRIBUTES-RE)
   ;;      clojure.pprint/pprint)

    (->> ssbo
         (re-map SSBO-RE)
         clojure.pprint/pprint)

   ;; (->> transform-feedback
   ;;      (re-map TRANSFORM-FEEDBACK-RE)
   ;;      clojure.pprint/pprint)

   ;; (->> custom-types
   ;;      (re-map STRUCT-RE)
   ;;      clojure.pprint/pprint)

   ;; (->> custom-types
   ;;      (re-map TYPEDEF-RE)
   ;;      clojure.pprint/pprint)
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

 ;;
  ;;  - Enforce binding and location indexes to be declared in the shader.
  ;;    Because automatic allocation is not consitent between devices, and it
  ;;    would be tedious to implement. It's also a good practice.
  ;;
  ;;    layout(location = 0) ...
  ;;    layout(binding = 0) ...
  ;;

  (defn get-entity-instance-data
    [entity]
    (get-in entity [:instance :data]))

  (reg-p :default
         {:pipeline [[:vertex   "shaders/default.vert"]
                     [:fragment "shaders/default.frag"]]
          :vertices {:layout  ["vLocal" "vColor" "vertexTexCoords"]
                     :setter  (fn [entity]
                                (get-in entity [:assets :posColTex]))}})

  (reg-p :instanced
         {:pipeline [[:vertex   "shaders/default.vert"]
                     [:fragment "shaders/default.frag"]]

          :vertices [{:layout  ["vLocal" "vColor" "vertexTexCoords"]
                      :setter  (fn [entity]
                                 (get-in entity [:assets :posColTex]))}
                     {:layout  ["instancePosition" "instanceColor" "instanceSpeed"]
                      :setter  get-entity-instance-data
                      :divisor 1}]})



  ;; Structure of a rendered entity

  {:entity/id     24
   :program/id    1
   :instances     [{:instance :data} ...]
   :entity/source "user defined"}

   ;; Comment extraire les donnee de l'entité pour les compacter dans le bon buffer
   ;; du program ?
   ;; Le format de l'entity est user defined

  ;; -> Compile shader
  ;; -> Create vao (vertex attributes)
  ;; ->



  ;; struct Particle {
  ;;   vec3 position;
  ;;   bool alive;
  ;; };
  ;;
  ;; uniform Particle particule;

  (reg-eu :foo
    (fn [db entities entity]
      {:position (get-in entity [:location :coordinates])
       :alive    (get-in entity [:state :alive?])}))

  ;; as we know the uniform type from shader_parser,
  ;; we can automatically validate the output of uniform
  ;; handlers. We can generate specs for evey types and throw
  ;; useful error messages to user.





  )
