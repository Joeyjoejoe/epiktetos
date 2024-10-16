(ns user
  (:require [epiktetos.core :refer [reg-event reg-cofx inject-cofx reg-fx reg-u reg-eu reg-p]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [epiktetos.utils.buffer :as util]
            [epiktetos.state :as state]
            [epiktetos.dev :as dev :refer [start stop resume]])


  (:import (org.lwjgl.glfw GLFW)
           (org.joml Matrix4f Vector3f)
           (org.lwjgl BufferUtils)
           (org.lwjgl.opengl GL45)))

(reg-p :perspective
       {:layout   [:vec3f/coordinates :vec3f/color :vec2f/texture]
        :pipeline [[:vertex "shaders/default.vert"]
                   [:fragment "shaders/default.frag"]]})

;; (defn time-back-and-forth
;;   [t duration]
;;   (let [cnt    (/ t duration)
;;         elapse (mod t duration)
;;         percent (/ (* elapse 100.0)
;;                    duration)]
;;     (/ (if (even? (int cnt))
;;          percent
;;          (- 100 percent))
;;        100)))
;;
;;
;;
;; (defn rotate-around
;;   [mat center angle]
;;     (-> mat
;;         (.translate center)
;;         (.rotateY (. Math toRadians angle))
;;         (.translate (.negate center))))

(reg-eu :model
        (fn model-matrix [db entities entity]
          (let [{:keys [position scale]
                 :or   {scale 1.0}} entity
                [x y z] position
                buffer (BufferUtils/createFloatBuffer 16)]

              (-> (Matrix4f.)
                  (.translate x y z)
                  (.scale scale scale scale)
                  (.get buffer)))))

(reg-u :view
       (fn [db]
         (let [;; camera position
               eye-x 0.0 ;; (* 10.0 (Math/sin t))
               eye-y 2.0 ;; (* -10.0 (Math/cos t))
               eye-z 5.8 ;; (* 10.0 (Math/cos t))

               ;; look point
               center-x 0.0
               center-y 0.0
               center-z 0.0

               ;; Which camera axis is up
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

(reg-cofx :edn/load
          (fn parse-at
            [coeffects path]
            (let [data (-> path
                           io/resource
                           slurp
                           edn/read-string)]
              (-> coeffects
                  (assoc :edn/load data)))))

(reg-event
  [:press :delete]
  (fn remove-cube [_ fx]
    (assoc fx :entity/delete-all true)))

(reg-event
  [:press :space :shift]
  (fn remove-cube [_ fx]
    (assoc fx :entity/delete :cube)))

(reg-event
  [:press :space]
  [(inject-cofx :edn/load "cube.edn")]
  (fn [{model :edn/load} fx]
    (assoc fx :entity/render (assoc model :id :cube))))

(reg-event
  [:repeat :space]
  [(inject-cofx :edn/load "cube.edn")]
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

          random-cube (-> model
                          (assoc :id entity-id)
                          (assoc :position position)
                          (assoc :scale (rand 1.0))
                          (assoc :speed (* (rand 100) (rand-nth [-1 1])))
                          (assoc-in [:assets :textures] [(str "textures/" texture)])
                          (assoc-in [:assets :vertices] colored-vertices))]
      (assoc fx :entity/render random-cube))))
