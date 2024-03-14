(ns epictetus.loop
  (:require
    [epictetus.event :as event]
    [epictetus.state :as state]
    [epictetus.rendering :as rendering]
    [clojure.pprint :refer [pprint]])

  (:import (org.lwjgl.glfw GLFW)
           (org.lwjgl.opengl GL11 GL20 GL30 GL45))
  (:gen-class))

(def lag (atom 0.0))

(defn start [{window :glfw/window}]

  (loop [{:as    loop-iter
          {:keys [curr delta]} :time} {:iter 1
                                        :time {:curr (GLFW/glfwGetTime)
                                               :prev 0
                                               :delta 0}}]

      (swap! lag #(+ % delta))
      (swap! state/db assoc-in [:db :core/loop] loop-iter)

      ;; Bind :epictetus.event/loop.iter, a user definable event.
      ;; It is guaranteed to run once per loop iterations
      ;; user/cognitive-load
      (event/execute [:epictetus.event/loop.iter loop-iter])


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
      (-> loop-iter
          (assoc-in [:time :curr]  (GLFW/glfwGetTime))
          (assoc-in [:time :prev]  curr)
          (assoc-in [:time :delta] (- (GLFW/glfwGetTime) curr))
          (update :iter inc)
          recur))))
