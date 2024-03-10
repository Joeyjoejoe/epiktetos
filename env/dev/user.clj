(ns user
  (:require [epictetus.core :refer [reg-event reg-u reg-eu]]
            [epictetus.coeffect :as cofx]
            [epictetus.utils.buffer :as util]
            [clojure.java.io :as io]
            [epictetus.state :as state]
            [epictetus.dev :refer [start stop resume reset]])


  (:import (org.lwjgl.glfw GLFW)
           (org.joml Matrix4f)
           (org.lwjgl BufferUtils)
           (org.lwjgl.opengl GL45))
  )


;; Camera uniforms
(reg-eu :model
        (fn model-matrix [db entities entity]
          (let [{:keys [position scale], :or {scale 1.0}} entity
                [x y z] position
                buffer (BufferUtils/createFloatBuffer 16)]
            (-> (Matrix4f.)
                (.translate x y z)
                (.scale scale scale scale)
                (.get buffer)))))

(reg-u :view
       (fn [db]
         (let [t (GLFW/glfwGetTime)
               eye-x 0.0 ;; (* 10.0 (Math/sin t))
               eye-y 0.0 ;; (* -10.0 (Math/cos t))
               eye-z 25.0 ;; (* 10.0 (Math/cos t))
               center-x 0.0
               center-y 0.0
               center-z 0.0
               up-x     0.0
               up-y     1.0
               up-z     0.0
               buffer (BufferUtils/createFloatBuffer 16)]
           (-> (Matrix4f.)
               (.lookAt eye-x eye-y eye-z
                        center-x center-y center-z
                        up-x up-y up-z)
               (.get buffer)))))

(reg-u :projection
       (fn [db]
         (let [window (:glfw/window @state/system)
               width  (util/int-buffer [0])
               height (util/int-buffer [0])
               _      (GLFW/glfwGetWindowSize window width height)
               fovy   (. Math toRadians 45.0)
               aspect (/ (.get width 0) (.get height 0))
               zmin   0.01
               zmax   100.0
               buffer (BufferUtils/createFloatBuffer 16)]
           (-> (Matrix4f.)
               (.perspective fovy aspect zmin zmax)
               (.get buffer)))))

(reg-eu :t
        (fn [db entities entity]
          (GLFW/glfwGetTime)))

(reg-eu :speed
        (fn [db entities entity]
          (or (:speed entity)
              0.0)))

(reg-eu :textIndex0
        (fn [db entities entity]
          (if-let [textures (get-in entity [:assets :textures])]
            (do
              (GL45/glBindTextureUnit 0 (first textures))
              0))))

(reg-eu [:sprite :animIndex]
        (fn [db entities entity]

          (let [now (get-in db [:loop/iteration :time/current])
                {:keys [:anim/duration :anim/frames :anim/start]} entity
                 frame-duration (/ duration frames)
                 elapsed        (- now (or start 0.0))
                 frame-nth   (-> (/ elapsed frame-duration)
                                 (Math/floor))]

              ;; TODO find algo for "backward cycle" animations
              ;; 0 1 2
              (-> frame-nth
                  (mod frames)
                  int))))

(reg-event
  :mouse/position
  (fn [{[_ position] :event} fx]
    (assoc-in fx [:db :mouse/position] position)))

(reg-event
  [:press :delete]
  (fn remove-cube [_ fx]
    (assoc fx :delete-all true)))

(reg-event
  [:press :space]
  [(cofx/inject :edn/load "hero.edn")]
  (fn render-level [{model :edn/load} fx]
    (-> fx
        (update :render conj [:hero model]))))

(reg-event
  [:repeat :space]
  [(cofx/inject :edn/load "cube.edn")]
  (fn render-random-cube [{model :edn/load} fx]
    (let [[min max] [-10 10]
          position  [(+ (rand min) (rand max))
                     (+ (rand min) (rand max))
                     (+ (rand min) (rand max))]
          [min max] [0 1]
          color     [(+ (rand min) (rand max))
                     (+ (rand min) (rand max))
                     (+ (rand min) (rand max))]
          colored-vertices (mapv #(assoc % :color color)
                                 (get-in model [:assets :vertices]))
          entity-id (-> "cube-"
                        (str (. (new java.util.Date) (getTime))))

          texture (-> "textures" io/resource io/file .list rand-nth)

          random-cube [entity-id (-> model
                                     (assoc :position position)
                                     (assoc :scale (rand 1.0))
                                     (assoc :speed (* (rand 100) (rand-nth [-1 1])))
                                     (assoc-in [:assets :textures] [(str "textures/" texture)])
                                     (assoc-in [:assets :vertices] colored-vertices))]]
      (-> fx
          (update :render conj random-cube)))))


