(ns epictetus.rendering
  (:require [epictetus.state :as state])
  (:import (org.joml Matrix4f)
           (org.lwjgl BufferUtils)
           (org.lwjgl.glfw GLFW)
           (org.lwjgl.opengl GL11 GL20 GL30 GL45)))

(defn model-matrix
  ([[x y z]] (model-matrix x y z))
  ([x y z]
   (let [buffer (BufferUtils/createFloatBuffer 16)]
     (-> (Matrix4f.)
         (.translate x y z)
         (.get buffer)))))

(defn view-matrix
  [eye-x    eye-y    eye-z
   center-x center-y center-z
   up-x     up-y         up-z]
  (let [buffer (BufferUtils/createFloatBuffer 16)]
    (-> (Matrix4f.)
        (.lookAt eye-x eye-y eye-z
                 center-x center-y center-z
                 up-x up-y up-z)
        (.get buffer))))

(defn projection-matrix
  [fovy aspect zmin zmax]
  (let [buffer (BufferUtils/createFloatBuffer 16)]
    (-> (Matrix4f.)
        (.perspective fovy aspect zmin zmax)
        (.get buffer))))


(defn int-buffer [data]
  (let [arr (int-array data)
        arr-nth (count data)]
    (-> (BufferUtils/createIntBuffer arr-nth)
        (.put arr)
        (.flip))))

(defn get-size
  "Return the window dimensions"
  []
  (let [window (:glfw/window @state/system)
        width (int-buffer [0])
        height (int-buffer [0])]

    (GLFW/glfwGetWindowSize window width height)
    {:width (.get width 0) :height (.get height 0)}))

(defn rotate-around [centerX centerY centerZ]
  (let [t (GLFW/glfwGetTime)
        x (* 10.0 (Math/sin t))
        y (* -10.0 (Math/cos t))
        z (* 10.0 (Math/cos t))]
    (view-matrix x y z centerX centerY centerZ 0.0 1.0 0.0)))

(defn pipeline
  []
  (GL11/glClearColor 0.0 1.0 1.0 1.0)
  (GL11/glClear (bit-or GL11/GL_COLOR_BUFFER_BIT GL11/GL_DEPTH_BUFFER_BIT))

  (let [{:keys [width height]} (get-size)
        projectionMX  (projection-matrix (. Math toRadians 45.0) (/ width height) 0.01 100.0)]

  (doseq [[vao-layout programs] @state/rendering]
    (let [{:keys [:vao/id :vao/stride]} (get-in @state/system [:shader/programs :vao vao-layout])]
    ;; (println "Bind VAO" id)
    (GL30/glBindVertexArray id)

    (doseq [[prog entities] programs]
      ;; (println "Use program" prog)
      (let [{pid :program/id unis :uniforms} (get-in @state/system [:shader/programs :program prog])
            ;; TODO Refactor uniforms managment
            uniforms (group-by first unis)]

        (GL20/glUseProgram pid)

      ;; Set program wide uniforms
      (GL20/glUniformMatrix4fv (get-in uniforms ["view" 0 2]) false (rotate-around 0.0 0.0 0.0))
      (GL20/glUniformMatrix4fv (get-in uniforms ["projection" 0 2]) false projectionMX);

      (doseq [[entity-id {:as entity :keys [position vbo assets]}] entities]

        ;; set eneity uniforms
        (GL20/glUniformMatrix4fv (get-in uniforms ["model" 0 2]) false (model-matrix position));

        ;; TODO Implement other rendering methods (indice, instance)

        (GL45/glVertexArrayVertexBuffer id 0 vbo 0 stride)
        (GL11/glDrawArrays GL11/GL_TRIANGLES 0 (count (:vertices assets))))))))))


;;    ;; Render context
;;
;;    - VAO : id, stride
;;    - program : id, uniforms
;;    - entity : position, vbo, vertices count
;;
;;    systems/
;;      - programs
;;      - vaos
;;      - shaders
;;
;;
;;    ;; Program config
;;    {:name     :default
;;     :layout   [:coordinates :color]
;;     :pipeline [[:vert "shaders/default.vert"]
;;                [:frag "shaders/default.frag"]]}
;;
;;    ;; Program system
;;    {:program/id 1
;;     :uniforms   [{:name "model" :location 0}
;;                  {:name "view" :location 1}
;;                  {:name "projection" :location 2}]}
;;
;;
;;    ;; Program config->system
;;    ;; Parse pipeline shaders sources
;;    [{:attr/location 0 :type "vec3"}
;;     {:attr/location 1 :type "vec3"}
;;     {:unif/location 0 :name "model"}
;;     {:unif/location 1 :name "view"}
;;     {:unif/location 2 :name "projection"}]
;;
;;
;;
;;    ;; :pipeline config used to:
;;    ;;   - Check if vao layout already exists
;;    ;;   - Create a new vao (match vec indexes with attr/location
;;    ;;     parsed from shaders sources.
;;    [:coordinates :color]
;;    [{:attr/location 0 :type "vec3"}
;;     {:attr/location 1 :type "vec3"}]
;;
;;    ;; Match :pipeline indexes <=> :attr/location, then merge
;;    [{:key :coordinates :attr/location 0 :type "vec3"}
;;     {:key :color :attr/location 1 :type "vec3"}]
;;
;;    ;; Resolve
;;    [{:key :coordinates :size 3 :type :float}
;;     {:key :color :size 3 :type :float}]
;;
;;    ;; system
;;    {:vao/id 1
;;     :vao/name :default
;;     :vao/layout [:coordinates :color] ;; layout must be unique
;;     :vao/stride 64}
;;
;;    ;; store in a global registry
;;    :vao/layouts {[:coordinates :color] {:vao/id 1
;;                                         :vao/name :default
;;                                         :vao/layout [:coordinates :color]
;;                                         :vao/stride 64}}
;;
;;
;;
;;
;;
;;    ;; Mandatory parameters for rendering a model
;;    {:model/id 24
;;     :model/position [0.0 0.0 0.0]
;;     :model/vert-count 26
;;     :program/id 1
;;     :program/uniforms ["view" "model" "projection"]
;;     :vao/id 1
;;     :vao/stride 9
;;     :vbo/id 1}
;;
;;
;;    (reg-uniform [:program/default :view]
;;                 (fn [entity]
;;                   (rotate-around 0.0 0.0 0.0)))
