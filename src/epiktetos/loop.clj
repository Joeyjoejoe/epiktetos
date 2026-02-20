(ns epiktetos.loop
  (:require [epiktetos.event :as event]
            [epiktetos.state :as state]
            [integrant.core :as ig]
            [epiktetos.render.pipeline :as render])

  (:import (org.lwjgl.glfw GLFW))
  (:gen-class))

(def FIXED_TIMESTEP (/ 1.0 120.0))

(defn start
  [{window :glfw/window}]

   (println :engine/start)
   (GLFW/glfwSetWindowShouldClose window false)

   (loop [{{:keys [curr delta]} :time
           {:keys [value frames tick]} :fps
           :as loop-iter}
          {:iter 1
           :paused? false
           :time {:curr (GLFW/glfwGetTime) :prev 0 :delta 0}
           :fps {:value 0 :frames 0 :tick 0.0}}

          lag (atom 0.0)]

     (swap! lag #(+ % delta))
     (swap! state/db assoc :core/loop loop-iter)

     ;; TODO apply entities transformations that can be multi threaded:
     ;; like motions, animations ?

     (event/execute [::event/loop.iter loop-iter])

     (while (>= @lag FIXED_TIMESTEP)

       (event/execute [::event/physics.update])
       (event/consume!)

       ;; TODO Improve paused loop commands :
       ;; - Manual event loop consumption
       ;; - Events redo/undo
       ;; - Inspector controls
       (while (get-in @state/db [:core/loop :paused?])
         (GLFW/glfwWaitEvents)
         (event/consume!))

       (swap! lag #(- % FIXED_TIMESTEP)))

     (when-not (GLFW/glfwWindowShouldClose window)
       (render/pipeline)

       (GLFW/glfwSwapBuffers window)
       (GLFW/glfwPollEvents)

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
             (recur lag)))))

   ;; Stop window system
   (-> @state/system
       (select-keys [:glfw/window])
       ig/halt!)
   :engine/stop)
