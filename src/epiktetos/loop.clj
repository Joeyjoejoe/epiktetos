(ns epiktetos.loop
  (:require [epiktetos.event :as event]
            [epiktetos.db :as app-db]
            [epiktetos.render.pipeline :as render])

  (:import (org.lwjgl.glfw GLFW))
  (:gen-class))

(def FIXED_TIMESTEP (/ 1.0 120.0))

(defn start
  [{{window-id :id} :glfw/window}]

   (println :engine/start)
   (GLFW/glfwSetWindowShouldClose window-id false)

   (loop [{{:keys [curr delta]} :time
           fps :fps
           :as window-frame}
          {:iter 1
           :time {:curr (GLFW/glfwGetTime) :prev 0 :delta 0}
           :fps  0}

          {:keys [frames tick]} {:frames 0 :tick 0.0}
          lag (atom 0.0)]

     (swap! lag #(+ % delta))
     (swap! app-db/db update :core/window merge window-frame)

     (while (>= @lag FIXED_TIMESTEP)

       (event/execute [::event/physics.update])
       (event/consume!)

       ;; TODO Improve paused loop commands :
       ;; - Manual event loop consumption
       ;; - Events redo/undo
       ;; - Inspector controls
       (while (get-in @app-db/db [:core/window :paused?])
         (GLFW/glfwWaitEvents)
         (event/consume!))

       (swap! lag #(- % FIXED_TIMESTEP)))

     (when-not (GLFW/glfwWindowShouldClose window-id)
       (render/pipeline @app-db/db)

       (GLFW/glfwSwapBuffers window-id)
       (GLFW/glfwPollEvents)

       (let [iter-end      (GLFW/glfwGetTime)
             iter-duration (- iter-end curr)
             fps-tick      (+ tick iter-duration)
             fps-second?   (> fps-tick 1.0)]

         (-> window-frame
             (assoc-in [:time :curr]  iter-end)
             (assoc-in [:time :prev]  curr)
             (assoc-in [:time :delta] iter-duration)
             (assoc :fps (if fps-second? frames fps))
             (update :iter inc)
             (recur (if fps-second?
                      {:frames 0 :tick (- fps-tick 1.0)}
                      {:frames (inc frames) :tick fps-tick})
                    lag)))))

   :engine/stop)
