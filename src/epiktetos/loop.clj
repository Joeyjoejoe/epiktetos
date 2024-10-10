(ns epiktetos.loop
  (:require
    [epiktetos.event :as event]
    [epiktetos.state :as state]
    [epiktetos.rendering :as rendering]
    [clojure.pprint :refer [pprint]])

  (:import (org.lwjgl.glfw GLFW)
           (org.lwjgl.opengl GL11 GL20 GL30 GL45))
  (:gen-class))


(defn start
  [{window :glfw/window}]

  (let [lag (atom 0.0)]
    (GLFW/glfwSetWindowShouldClose window false)

    (loop [{:as    loop-iter
            {:keys [curr delta]} :time} {:iter 1
                                         :time {:curr (GLFW/glfwGetTime)
                                                :prev 0
                                                :delta 0}}]

      (swap! lag #(+ % delta))
      (swap! state/db assoc :core/loop loop-iter)

      ;; Bind :epiktetos.event/loop.iter, a user definable event.
      ;; It is guaranteed to run once per loop iterations
      ;; user/cognitive-load
      ;; (event/execute [:epiktetos.event/loop.iter loop-iter])

      ;; TODO apply entities transformations that can be multi threaded:
      ;; like motions, animations ?

      (while (>= @lag 0.1)
        ;; Consume events queue
        (while (seq @event/queue)
          (println @event/queue)
          (let [e (peek @event/queue)]
            (event/execute e)
            (swap! event/queue pop)))

        (swap! lag #(- % 0.1)))

      (rendering/pipeline)

      (GLFW/glfwSwapBuffers window)
      (GLFW/glfwPollEvents)

      (when-not (GLFW/glfwWindowShouldClose window)
        (-> loop-iter
            (assoc-in [:time :curr]  (GLFW/glfwGetTime))
            (assoc-in [:time :prev]  curr)
            (assoc-in [:time :delta] (- (GLFW/glfwGetTime) curr))
            (update :iter inc)
            ;; TODO Explore utility of replacing empty vec by a
            ;; user accessible location in state ?
            recur)))))
