(ns epictetus.loop
  (:require
    [epictetus.scene :as scene]
    [epictetus.event :as event]
    [clojure.pprint :refer [pprint]])

  (:import (org.lwjgl.glfw GLFW))
  (:gen-class))

(def lag (atom 0.0))

(defn start [window]

  (loop [curr-time (GLFW/glfwGetTime)
         prev-time 0]

    (swap! lag #(+ % (- curr-time prev-time)))

    (while (>= @lag 0.1)

      ;; Consume events queue
      (while (seq @event/queue)
        (let [e (peek @event/queue)]
          (pprint e)
          (event/execute e)
          (pprint @scene/state)
          (swap! event/queue pop)))

      (swap! lag #(- % 0.1)))

    ;; render

    (GLFW/glfwSwapBuffers window)
    (GLFW/glfwPollEvents)

    ;; (if (GLFW/glfwWindowShouldClose window)
    (if (:should-quit? @scene/state)
      (do (GLFW/glfwDestroyWindow window)
          (GLFW/glfwTerminate))
      (recur (GLFW/glfwGetTime) curr-time))))
