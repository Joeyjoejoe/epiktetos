(ns epictetus.program
  (:require [integrant.core :as ig]
            [clojure.java.io :as io]
            [epictetus.state :as state]
            [epictetus.utils.glsl-parser :as glsl]
            [epictetus.lang.opengl :as opengl])
  (:import  (org.lwjgl.glfw GLFW)
            (org.lwjgl.opengl GL11 GL15 GL20 GL45)))


(defonce gl-types
  {:vec3f {:bytes (* 3 java.lang.Float/BYTES)
           :type  GL11/GL_FLOAT
           :count 3}

   :vec3i {:bytes (* 3 java.lang.Float/BYTES)
           :type  GL11/GL_INT
           :count 3}

   :vec3b {:bytes (* 3 java.lang.Float/BYTES)
           :type  GL11/GL_BYTE
           :count 3}

   :mat4f {:bytes (* 16 java.lang.Float/BYTES)
           :type  GL11/GL_FLOAT
           :count 16}})

(defn attrib-offset
  "Given the index of an attrib in attribs collection,
  returns the bytes size sum of previous attribs"
  [attr-layout location]
  (let [prev-attribs (subvec (apply vector attr-layout) 0 location)]
    (->> prev-attribs
         (map #(get-in gl-types [(:type %) :bytes]))
         (reduce +))))

(defn attribs-compile
  "attribs are shader attributes metadata : [0 vec3 varname]
   layout is a single shader/program layout from config file"
  [attribs layout]
  (sort-by :location
           (for [[location ds] attribs]
             (let [attrib-key (get layout location) ;; EX: :vec3f/coordinates
                   attrib-name (name attrib-key)
                   attrib-type (-> attrib-key
                                   namespace
                                   keyword)]
               {:key       attrib-key
                :name      attrib-name
                :type      attrib-type
                :location  location}))))

(defn compile-uniforms
  [prog-id uniforms]
  (GL20/glUseProgram prog-id)
  (let [unif-with-locations (for [[name _ :as unif] uniforms]
                              (conj unif (GL20/glGetUniformLocation ^Long prog-id ^String name)))]
    (GL20/glUseProgram 0)
    (vec unif-with-locations)))

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
                         (map #(get-in gl-types [(:type %) :bytes]))
                         (reduce +))]

    (doseq [{:keys [location type]} attr-layout]
      (let [offset       (attrib-offset attr-layout location)
            attrib-type  (get-in gl-types [type :type])
            attrib-count (get-in gl-types [type :count])]
        (GL45/glVertexArrayAttribFormat vao location attrib-count attrib-type false offset)
        (GL45/glEnableVertexArrayAttrib vao location)
        (GL45/glVertexArrayAttribBinding vao location 0)))

    {:vao/id     vao
     :vao/layout layout
     :vao/stride stride}))

(defn compile-shaders
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
                 ;; TODO parse attribute varname and store
                 metadata (glsl/analyze-shader source)]

             (when (= 0 id)
               (throw (Exception. (str "Error creating shader of type: " stage))))

             (GL20/glShaderSource id source)
             (GL20/glCompileShader id)

             (when (= 0 (GL20/glGetShaderi id GL20/GL_COMPILE_STATUS))
               (throw (Exception. (str "shader compilation error: " [stage path] (GL20/glGetShaderInfoLog id 1024)))))
             (assoc metadata :shader/ids [id])))))

(defmethod ig/init-key
  :gl/engine
  [_ config]

  (apply merge-with into (for [{:keys [name layout pipeline]} (:programs config)]

    (let [{shaders :shader/ids
           :keys [attribs uniforms]} (compile-shaders pipeline)
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

(defmethod ig/halt-key!
  :gl/engine
  [_ system]

  ;; reset state
  (doseq [[layout programs] @state/rendering]
    (doseq [[program-k entities] programs]
      (for [[entity-id {:keys [vbo]}] entities]
      (GL15/glDeleteBuffers vbo)))

    (reset! state/rendering {})
    (reset! state/system {})
    (reset! state/db {})))
