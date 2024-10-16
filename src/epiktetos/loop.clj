(ns epiktetos.loop
  (:require [epiktetos.event :as event]
            [epiktetos.state :as state]
            [epiktetos.rendering :as rendering])

  (:import (org.lwjgl.glfw GLFW))
  (:gen-class))


(defn start
  [{window :glfw/window}]

   (GLFW/glfwSetWindowShouldClose window false)

   (loop [{{:keys [curr delta]} :time
           {:keys [value frames tick]} :fps
           :as loop-iter}
          {:iter 1
           :runing? true
           :time {:curr (GLFW/glfwGetTime) :prev 0 :delta 0}
           :fps {:value 0 :frames 0 :tick 0.0}}

          lag (atom 0.0)]

     (swap! lag #(+ % delta))
     (swap! state/db assoc :core/loop loop-iter)

     ;; TODO apply entities transformations that can be multi threaded:
     ;; like motions, animations ?

     (event/execute [::event/loop.iter loop-iter])

     (while (>= @lag 0.1)

       (event/consume!)

       ;; On pause loop keep dispatching events
       (while (not (get-in @state/db [:core/loop :runing?]))
         (GLFW/glfwWaitEvents)
         (event/consume!))

       (swap! lag #(- % 0.1)))

     (rendering/pipeline)

     (GLFW/glfwSwapBuffers window)
     (GLFW/glfwPollEvents)

     (event/execute [::after-render])

     (when-not (GLFW/glfwWindowShouldClose window)
       (let [iter-end      (GLFW/glfwGetTime)
             iter-duration (- iter-end curr)
             fps-tick (+ tick iter-duration)
             fps-map  (if (> fps-tick 1.0)
                           {:value frames :frames 0 :tick (- fps-tick 1.0)}
                           {:value value :frames (inc frames) :tick fps-tick})]

         (-> loop-iter
             (assoc-in [:time :curr]  iter-end)
             (assoc-in [:time :prev]  curr)
             (assoc-in [:time :delta] iter-duration)
             (assoc :fps fps-map)
             (update :iter inc)
             (recur lag))))))
