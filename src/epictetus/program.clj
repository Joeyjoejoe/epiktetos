(ns epictetus.program
  (:require [integrant.core :as ig]
            [clojure.java.io :as io]
            [epictetus.texture :as texture]
            [epictetus.state :as state]
            [epictetus.uniform :as u]
            [epictetus.utils.glsl-parser :as glsl]
            [epictetus.lang.opengl :as opengl])
  (:import  (org.lwjgl.glfw GLFW)
            (org.lwjgl.opengl GL11 GL15 GL20 GL32 GL40 GL45)))


(defonce gl-types
  {:vec2f {:bytes (* 2 java.lang.Float/BYTES)
           :type  GL11/GL_FLOAT
           :count 2}
   :vec3f {:bytes (* 3 java.lang.Float/BYTES)
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

(defonce DRAW-PRIMITIVES
  {:triangles                GL11/GL_TRIANGLES
   :lines                    GL11/GL_LINES
   :points                   GL11/GL_POINTS
   :line-strip               GL11/GL_LINE_STRIP
   :line-loop                GL11/GL_LINE_LOOP
   :line-strip-adjacency     GL32/GL_LINE_STRIP_ADJACENCY
   :lines-adjacency          GL32/GL_LINES_ADJACENCY
   :triangle-strip           GL11/GL_TRIANGLE_STRIP
   :triangle-fan             GL11/GL_TRIANGLE_FAN
   :triangle-strip-adjacency GL32/GL_TRIANGLE_STRIP_ADJACENCY
   :triangles-adjacency      GL32/GL_TRIANGLES_ADJACENCY
   :patches                  GL40/GL_PATCHES})

(defn attrib-offset
  "Given the index of an attrib in attribs collection,
  returns the bytes size sum of previous attribs"
  [attr-layout location]
  (let [prev-attribs (subvec (apply vector attr-layout) 0 location)]
    (->> prev-attribs
         (map #(* (get-in gl-types [(:type %) :bytes])
                  (get % :length)))
         (reduce +))))

(defn build-program-layout
  "attribs are shader attributes metadata : [0 vec3 varname]
   layout is a single shader/program layout from config file"
  [attribs layout]
  (sort-by :location
           (for [[location typ varname arr-length] attribs]
             (let [attrib-key (get layout location) ;; EX: :vec3f/coordinates
                   attrib-name (name attrib-key)
                   attrib-type (-> attrib-key
                                   namespace
                                   keyword)
                   attrib-length (or arr-length 1)
                   layout-key    (if (> attrib-length 1)
                                   (keyword (namespace attrib-key)
                                            (str attrib-name "[" attrib-length "]"))
                                   attrib-key)]

               {:key        attrib-key
                :layout-key layout-key
                :name       attrib-name
                :type       attrib-type
                :location   location
                :length     attrib-length}))))

(defn vertex-stride
  [attribs]
  (->> attribs
       (map #(* (get-in gl-types [(:type %) :bytes])
                (get % :length)))
       (reduce +)))

;; Return a queue of uniforms values :
;; [[:model :mat4 1]
;;  [:view :mat4 2]
;;  ...]
(defn compile-uniforms
  [p-id u-seq]
  (GL20/glUseProgram p-id)
  (let [unif-with-locations (map #(conj % (u/locate-u p-id (name (first %))))
                                 u-seq)]
    (println unif-with-locations)
    (GL20/glUseProgram 0)
    (apply conj clojure.lang.PersistentQueue/EMPTY unif-with-locations)))

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
        attr-layout (build-program-layout attribs layout)
        stride      (vertex-stride attr-layout)]

    (doseq [{:keys [location type length]} attr-layout]
      (let [{attrib-type  :type
             attrib-count :count
             attrib-bytes :bytes} (get gl-types type)
            base-offset  (attrib-offset attr-layout location)]

        ;; Array support :
        ;; A vertex attribute can be set as an array of basic types by appending [n] at
        ;; the end of its shader variable name (where n is the number of elements in the
        ;; array)
        ;;
        ;; Example from shader source:
        ;;
        ;;   `in vec2 vTextCoords[3];`
        ;;
        ;; This define a vertex attribute as an array of 3 vec2.
        ;;
        ;; Each array element will be considered as an independant attribute and assigned
        ;; a uniq location `arr-loc`.
        ;; Non-array attributes are transparently processed as an 1d array.
        ;;
        (dotimes [arr-index length]
          (let [arr-loc (+ location arr-index)
                offset  (+ base-offset (* arr-index attrib-bytes))]

          (GL45/glVertexArrayAttribFormat vao arr-loc attrib-count attrib-type false offset)
          (GL45/glEnableVertexArrayAttrib vao arr-loc)
          (GL45/glVertexArrayAttribBinding vao arr-loc 0)))))

    {:vao/id     vao
     :vao/layout (mapv :layout-key attr-layout)
     :vao/stride stride}))


(defn compile-shaders
  "Given a pipeline config:

  [[:vertex \"shaders/default.vert\"]
   [:fragment \"shaders/default.frag\"]]

  Returns a map of attributes and uniforms:

  {:shader/ids [1 2]
   :attribs    [[0 :vec3 \"var1\" 4] [1 :vec3 \"var2\"]]
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

(defmethod ig/init-key
  :gl/engine
  [_ config]

  (apply merge-with into (for [{:keys [name layout pipeline draw]
                                :or   {draw :triangles}} (:programs config)]

    (let [{shaders :shader/ids
           :keys [attribs uniforms]} (compile-shaders pipeline)
          vao                        (compile-vao layout attribs)
          prog-id                    (GL20/glCreateProgram)
          primitive                  (get DRAW-PRIMITIVES draw)]

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
       :program  {name {:name name
                        :program/id prog-id
                        :primitive  primitive
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

    (reset! texture/text-cache {})
    (reset! state/rendering {})
    (reset! state/system {})
    (reset! state/db {})))
