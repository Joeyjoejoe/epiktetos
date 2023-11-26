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

(defn pipeline
  []
  (GL11/glClearColor 0.0 1.0 1.0 1.0)
  (GL11/glClear (bit-or GL11/GL_COLOR_BUFFER_BIT GL11/GL_DEPTH_BUFFER_BIT))

  (let [view        (view-matrix (* 10.0 (Math/sin (GLFW/glfwGetTime))) (* -10.0 (Math/cos (GLFW/glfwGetTime))) (* 10.0 (Math/cos (GLFW/glfwGetTime))) 0.0 0.0 0.0 0.0 1.0 0.0)
        projection  (projection-matrix (. Math toRadians 45.0) (/ 800.0 600.0) 0.01 100.0)]


  (doseq [[vao programs] @state/rendering]
    (let [{:keys [id stride]} (get-in @state/system [:gl/vaos vao])]
    ;; (println "Bind VAO" id)
    (GL30/glBindVertexArray id)

    (doseq [[prog entities] programs]
      ;; (println "Use program" prog)
      (-> @state/system
          (get-in [:gl/programs prog :id])
          GL20/glUseProgram)

      ;; Set program wide uniforms
      (GL20/glUniformMatrix4fv 2 false view);
      (GL20/glUniformMatrix4fv 1 false projection);

      (doseq [[entity-id {:as entity :keys [position vbo assets]}] entities]
        ;; (println "Render" entity-id)

        (GL20/glUniformMatrix4fv 0 false (model-matrix position));

        (GL45/glVertexArrayVertexBuffer id 0 vbo 0 stride)
        (GL11/glDrawArrays GL11/GL_TRIANGLES 0 (count (:vertices assets)))))))))
