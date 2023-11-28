(ns epictetus.rendering
  (:require [epictetus.state :as state])
  (:import (org.joml Matrix4f)
           (org.lwjgl BufferUtils)
           (org.lwjgl.glfw GLFW)
           (org.lwjgl.opengl GL11 GL20 GL30 GL45)))



;; FloatBuffer fb = BufferUtils.createFloatBuffer(16);

;; new Matrix4f().perspective((float) Math.toRadians(45.0f), 1.0f, 0.01f, 100.0f)
;;               .lookAt(0.0f, 0.0f, 10.0f,
;;                       0.0f, 0.0f, 0.0f,
;;                       0.0f, 1.0f, 0.0f).get(fb);



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


    (doseq [[vao programs] @state/rendering]
      (let [{:keys [id stride]} (get-in @state/system [:gl/vaos vao])]
        ;; (println "Bind VAO" id)
        (GL30/glBindVertexArray id)

        (doseq [[prog entities] programs]
          ;; (println "Use program" prog)
          (let [{pid :id unis :uniforms} (get-in @state/system [:gl/programs prog])]
            (GL20/glUseProgram pid)

            ;; Set program wide uniforms
            (GL20/glUniformMatrix4fv (get-in unis ["view" :location]) false (rotate-around 0.0 0.0 0.0));
            (GL20/glUniformMatrix4fv (get-in unis ["projection" :location]) false projectionMX);

            (doseq [[entity-id {:as entity :keys [position vbo assets]}] entities]
              ;; (println "Render" entity-id)
              (GL20/glUniformMatrix4fv (get-in unis ["model" :location]) false (model-matrix position));

              (GL45/glVertexArrayVertexBuffer id 0 vbo 0 stride)
              (GL11/glDrawArrays GL11/GL_TRIANGLES 0 (count (:vertices assets))))))))))
