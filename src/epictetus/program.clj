(ns epictetus.program
  (:require [integrant.core :as ig]
            [clojure.java.io :as io]
            [epictetus.utils.glsl-parser :as glsl]
            [epictetus.lang.opengl :as opengl])
  (:import  (org.lwjgl.opengl GL11 GL20 GL45)))

(defn compile-pipeline
  "Given a pipeline config:

  [[:vertex \"shaders/default.vert\"]
   [:fragment \"shaders/default.frag\"]]

  Returns a map of attributes and uniforms:

  {:shader/ids [1 2]
   :attribs    [[0 :vec3] [1 :vec3]]
   :uniforms   [[\"view\" :mat4]
                [\"projection\" :mat4]
                [\"model\" :mat4]]}

  These are the mandatory parameters for rendering
  with the program pipeline"
  [pipeline]

  (apply merge-with
         into
         (for [[stage path] pipeline]
           (let [id       (-> stage opengl/dictionary GL20/glCreateShader)
                 source   (-> path (io/resource) (slurp))
                 metadata (glsl/analyze-shader source)]

             (when (= 0 id)
               (throw (Exception. (str "Error creating shader of type: " stage))))

             (GL20/glShaderSource id source)
             (GL20/glCompileShader id)

             (when (= 0 (GL20/glGetShaderi id GL20/GL_COMPILE_STATUS))
               (throw (Exception. (str "shader compilation error: " [stage path] (GL20/glGetShaderInfoLog id 1024)))))
             (assoc metadata :shader/ids [id])))))


(defonce gl-types
  {:float GL11/GL_FLOAT
   :int   GL11/GL_INT
   :byte  GL11/GL_BYTE})

(defonce struc-length
  {:vec3 3
   :vec4 4
   :mat3 9
   :mat4 16})

(defonce type-bytes-sizes
  {:float java.lang.Float/BYTES
   :int   java.lang.Integer/BYTES
   :byte  java.lang.Byte/BYTES})

(defn attrib-bytes
  "Returns the bytes size of the given attrib"
  [struc typ]
  (let [type-bytes   ((keyword typ) type-bytes-sizes)
        struc-length ((keyword struc) struc-length)]
    (* struc-length type-bytes)))

(defn attrib-offset
  "Given the index of an attrib in attribs collection,
  returns the bytes size sum of previous attribs"
  [attr-layout location]
  (let [prev-attribs (subvec (apply vector attr-layout) 0 location)]
    (->> prev-attribs
         (map :size)
         (reduce +))))

(defn attribs-compile
  [attribs layout]
  (sort-by :location
           (for [[location struc] attribs]
             (let [attrib (get layout location)
                   field  (name attrib)
                   typ    (namespace attrib)
                   k      (keyword (str (name struc) "." typ) field)]
               {:location location
                :typ      (keyword typ)
                :size     (attrib-bytes struc typ)
                :length   ((keyword struc) struc-length)
                :key      k}))))



(defn compile-vao
  "Create or get a VAO (vertex array object)

  layout  [:float/coordinates :float/color]
  attribs [[0 :vec3] [1 :vec3]]

  Returns a vao map

  {:vao/id 1
   :vao/layout [:vec3.float/coordinates :vec3.float/color]
   :vao/stride 64}"
  [layout attribs]

  (let [vao         (GL45/glCreateVertexArrays)
        attr-layout (attribs-compile attribs layout)
        stride      (->> attr-layout
                         (map :size)
                         (reduce +))]

    (doseq [{:keys [length location typ]} attr-layout]
      (let [offset (attrib-offset attr-layout location)]
        (GL45/glVertexArrayAttribFormat vao location length (typ gl-types) false offset)
        (GL45/glEnableVertexArrayAttrib vao location)
        (GL45/glVertexArrayAttribBinding vao location 0)))

    {:vao/id     vao
     :vao/layout (vec (map :key attr-layout))
     :vao/stride stride}))

(defn compile-uniforms
  [prog-id uniforms]
  (GL20/glUseProgram prog-id)
  (let [unif-with-locations (for [[name _ :as unif] uniforms]
                              (conj unif (GL20/glGetUniformLocation ^Long prog-id ^String name)))]
    (GL20/glUseProgram 0)
    (vec unif-with-locations)))


(defmethod ig/init-key
  :shader/programs
  [_ programs]

  (apply merge-with into (for [{:keys [name layout pipeline]} programs]

    (let [{shaders :shader/ids
           :keys [attribs uniforms]} (compile-pipeline pipeline)
          vao                        (compile-vao layout attribs)
          prog-id                    (GL20/glCreateProgram)]

      (doseq [shader-id shaders]
        (GL20/glAttachShader prog-id shader-id))

      (GL20/glLinkProgram prog-id)
      (when (= 0 (GL20/glGetProgrami prog-id GL20/GL_LINK_STATUS))
        (throw (Exception. (str
                             "Error linking shader to program " name ": "
                             (GL20/glGetProgramInfoLog prog-id 1024)))))

      (doseq [shader-id shaders]
        (GL20/glDeleteShader shader-id))

      {:vao      {(:vao/layout vao) vao}
       :program  {name {:program/id prog-id
                        :layout     (-> vao :vao/layout vec)
                        :uniforms   (compile-uniforms prog-id uniforms)}}}))))


;;
;;
;;  :shaders {:vao {[:float/coordinates :float/color] {:vao/id 1
;;                                                     :vao/layout [:float/coordinates :float/color]
;;                                                     :vao/stride 64}}
;;            :program {:default {:program/id 1
;;                                :layout     [:float/coordinates :float/color]
;;                                :uniforms   [["model" 1 :mat4]
;;                                             ["view" 2 :mat4]
;;                                             ["projection" 3 :mat4]]}}}
