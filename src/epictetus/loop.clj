(ns epictetus.loop
  (:require [integrant.core :as ig]
            [clojure.java.io :as io]
            [epictetus.state :as state]
            [epictetus.window :as window])

  (:import (org.lwjgl.glfw GLFW))
  (:gen-class))

(defn run [config-path]
  (let [engine-state state/engine-state
        system (-> config-path
                   io/resource
                   slurp
                   ig/read-string
                   ig/init)]

    (loop []

      (clojure.pprint/pprint (:event/queue @engine-state))

      ;; GAME STATE UPDATE
      ;; Handle use inputs in :event/queue
      ;; side effects full

      ;; ;;Consume event/queue
      ;; (-> @engine-state
      ;;     :event/queue
      ;;     controls/dispatch)

      ;; ;; reset event/queue
      (state/reset-events!)
      (Thread/sleep 5000)



      ;; render

      (GLFW/glfwSwapBuffers (:glfw/window system))
      (GLFW/glfwPollEvents)

      (if (not (GLFW/glfwWindowShouldClose (:glfw/window system)))
        (recur)))

      (GLFW/glfwDestroyWindow (:glfw/window system))
      (GLFW/glfwTerminate)))




    ;;    (loop [to-render-functions (state/get-data :render)
    ;;           curr (GLFW/glfwGetTime)
    ;;           prev (GLFW/glfwGetTime)
    ;;           lag (atom 0.0)]
    ;;
    ;;      (swap! lag #(+ % (- curr prev)))
    ;;      (swap! (state/get-atom) assoc :deltatime (- curr prev))
    ;;
    ;;      (clojure.pprint/pprint (state/get-data))
    ;;      (clojure.pprint/pprint curr)
    ;;
    ;;      ;; Trigger events based on time
    ;;      ;; (time-triggers curr)
    ;;
    ;;      ;;  (handle-inputs)
    ;;;;;;      (state/update-camera)
    ;;      ;; Handle game logic and update
    ;;      (while (>= @lag 0.1)
    ;;        ;;  (update)
    ;;        (swap! lag #(- % 0.1)))
    ;;
    ;;      ;; (render (/ lag 0.1))
    ;;;;;;      (window/render (:glfw/window system) to-render-functions)
    ;;
    ;;      ;; Calculate FPS
    ;;      (record-fps (state/get-atom))
    ;;
    ;;      ;; Recur loop
    ;;      (if (not (GLFW/glfwWindowShouldClose (:glfw/window system)))
    ;;        (recur (state/get-data :render) (GLFW/glfwGetTime) curr lag)))
