(ns epictetus.loop
  (:require
    [epictetus.event :as event]
    [epictetus.rendering :as rendering]
    [clojure.pprint :refer [pprint]])

  (:import (org.lwjgl.glfw GLFW)
           (org.lwjgl.opengl GL11 GL20 GL30 GL45))
  (:gen-class))

(def lag (atom 0.0))

(defn start [{window :glfw/window}]

  (loop [curr-time  (GLFW/glfwGetTime)
         prev-time  0
         delta-time 0]

    (event/execute [:loop/iteration {:curr-time curr-time :delta delta-time}])

    (swap! lag #(+ % delta-time))

    (while (>= @lag 0.1)

      ;; Consume events queue
      (while (seq @event/queue)
        (let [e (peek @event/queue)]
          (when-not (= :mouse/position (first e))
            (pprint e))
          (event/execute e)
          (swap! event/queue pop)))

      (swap! lag #(- % 0.1)))

    (rendering/pipeline)

    (GLFW/glfwSwapBuffers window)
    (GLFW/glfwPollEvents)

    (when-not (GLFW/glfwWindowShouldClose window)
      (recur (GLFW/glfwGetTime) curr-time (- (GLFW/glfwGetTime) curr-time)))))
