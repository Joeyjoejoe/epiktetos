(ns epiktetos.loop
  (:require [epiktetos.event :as event]
            [epiktetos.db :as app-db]
            [epiktetos.render.pipeline :as render])

  (:import (org.lwjgl.glfw GLFW))
  (:gen-class))

(def FIXED_TIMESTEP (/ 1.0 120.0))

(defn- count-fps
  "Advance the fps counter by one frame: the frame joins the running
  one-second window, and when the window closes its frame count
  becomes the fps value, carried until the next window closes.
  fps-count     - map, {:fps int :frames int :tick double}
  iter-duration - double, duration of the elapsed frame in seconds
  Returns the advanced fps-count map."
  [{:keys [fps frames tick]} iter-duration]
  (let [frames (inc frames)
        tick   (+ tick iter-duration)]
    (if (> tick 1.0)
      {:fps frames :frames 0 :tick (- tick 1.0)}
      {:fps fps :frames frames :tick tick})))

(defn start
  [{{window-id :id} :glfw/window}]

   (println :engine/start)
   (GLFW/glfwSetWindowShouldClose window-id false)

   (loop [{{:keys [curr delta]} :time
           :as window-frame}
          {:iter 1
           :time {:curr (GLFW/glfwGetTime) :prev 0 :delta 0}
           :fps  0}

          fps-count {:fps 0 :frames 0 :tick 0.0}
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
             fps-count     (count-fps fps-count iter-duration)]

         (-> window-frame
             (assoc-in [:time :curr]  iter-end)
             (assoc-in [:time :prev]  curr)
             (assoc-in [:time :delta] iter-duration)
             (assoc :fps (:fps fps-count))
             (update :iter inc)
             (recur fps-count lag)))))

   :engine/stop)
