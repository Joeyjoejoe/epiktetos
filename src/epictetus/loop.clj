(ns epictetus.loop
  (:require
    [epictetus.event :as event]
    [epictetus.state :as state]
    [clojure.pprint :refer [pprint]])

  (:import (org.lwjgl.glfw GLFW)
           (org.lwjgl.opengl GL11 GL20 GL30 GL45))
  (:gen-class))

(def lag (atom 0.0))

(defn start [{window :glfw/window}]

  (loop [curr-time (GLFW/glfwGetTime)
         prev-time 0]

    (swap! lag #(+ % (- curr-time prev-time)))

    (while (>= @lag 0.1)

      ;; Consume events queue
      (while (seq @event/queue)
        (let [e (peek @event/queue)]
          (pprint e)
          (event/execute e)
          (swap! event/queue pop)))

      (swap! lag #(- % 0.1)))

    ;;render
    (GL11/glClearColor 0.0 0.0 0.8 0.5)
    (GL11/glClear (bit-or GL11/GL_COLOR_BUFFER_BIT GL11/GL_DEPTH_BUFFER_BIT))

    ;; TODO We should iterate on vaos instead of entities which induces
    ;; to maintain vao vertices count.
    ;; What about rendering methods (single, instance, with(out) indices) ?
    (doseq [[vao-name entities] @state/rendering]
      (let [vao-id     (get-in @state/system [:gl/vaos vao-name :id])
            vao-stride (get-in @state/system [:gl/vaos vao-name :stride])]
        (GL30/glBindVertexArray vao-id)

        (doseq [[id {:keys [program position vbo assets]}] entities]
          (println "render: " id program)
          (GL20/glUseProgram (get-in @state/system [:gl/programs program]))

          (GL45/glVertexArrayVertexBuffer vao-id 0 vbo 0 vao-stride)

          (GL11/glDrawArrays GL11/GL_TRIANGLES 0 (count (:vertices assets))))))

    (GLFW/glfwSwapBuffers window)
    (GLFW/glfwPollEvents)

    (when-not (GLFW/glfwWindowShouldClose window)
      (recur (GLFW/glfwGetTime) curr-time))))
