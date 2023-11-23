(ns epictetus.rendering
  (:require [epictetus.state :as state])
  (:import (org.lwjgl.opengl GL11 GL20 GL30 GL45)))

(defn pipeline
  []
  (GL11/glClearColor 0.0 1.0 1.0 1.0)
  (GL11/glClear (bit-or GL11/GL_COLOR_BUFFER_BIT GL11/GL_DEPTH_BUFFER_BIT))

  ;; What about rendering methods (single, instance, with(out) indices) ?
  (doseq [[vao-name entities] @state/rendering]
    (let [{:keys [id stride]} (get-in @state/system [:gl/vaos vao-name])]

      (GL30/glBindVertexArray id)
      (doseq [[_ {:keys [program position vbo assets]}] entities]

        (GL20/glUseProgram program)
        (GL45/glVertexArrayVertexBuffer id 0 vbo 0 stride)
        (GL11/glDrawArrays GL11/GL_TRIANGLES 0 (count (:vertices assets)))))))
