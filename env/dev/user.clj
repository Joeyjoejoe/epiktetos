(ns user
  (:require [epiktetos.core :refer [reg-event reg-cofx inject-cofx reg-fx reg-p reg-steps! dispatch render delete]]
            [epiktetos.dev :as dev :refer [start inspector]])


  (:import (org.lwjgl.glfw GLFW)
           (org.joml Matrix4f Vector3f)
           (org.lwjgl BufferUtils)
           (org.lwjgl.opengl GL45)))

(defn colored-vertices-handler
  "Extract vertices and colors from an entity"
  [entity]
  (->> entity
       :vertices
       (mapcat #(vector (:coordinates %) (:color %)))
       flatten))

(defn instance-positions-handler
  [entity]
  (->> entity
       :instances-positions
       flatten))

(def no-camera
  {:pipeline [[:vertex   "shaders/flat.vert"]
              [:fragment "shaders/default.frag"]]
   :vertex-layout [{:layout  ["vLocal" "vColor"]
                    :handler colored-vertices-handler
                    :storage :dynamic}]})

(def no-camera-instanced
  {:pipeline [[:vertex   "shaders/flat-instances.vert"]
              [:fragment "shaders/default.frag"]]
   :vertex-layout [{:layout    ["vLocal" "vColor"]
                    :handler   colored-vertices-handler
                    :storage   :dynamic}
                   {:layout  ["instancePosition"]
                    :handler instance-positions-handler
                    :divisor 1}]})

(def triangle-vertices
  [{:coordinates [-0.5 -0.5 0.1] :color [1.0 0.0 0.0]}
   {:coordinates [ 0.5 -0.5 0.1] :color [0.0 1.0 0.0]}
   {:coordinates [ 0.0  0.5 0.1] :color [0.0 0.0 1.0]}])

(def square-vertices
  [{:coordinates [-0.5 -0.5 0.1] :color [1.0 0.0 0.0]}
   {:coordinates [ 0.5 -0.5 0.1] :color [0.0 1.0 0.0]}
   {:coordinates [ 0.5  0.5 0.1] :color [0.0 0.0 1.0]}
   {:coordinates [-0.5  0.5 0.1] :color [1.0 1.0 0.0]}])


(comment

  (reg-steps! [:per-material (fn [entity]
                               (:material entity))])

  (reg-p :no-camera no-camera)
  (reg-p :no-camera-instanced no-camera-instanced)
  (render :squares {:program    :no-camera-instanced
                    :vertices   square-vertices
                    :indices    [0 1 2 0 2 3]
                    :primitives :points
                    :instances  6
                    :instances-positions [[-0.5  0.5  0.0]
                                          [ 0.5  0.5  0.0]
                                          [ 0.75 0.0  0.0]
                                          [-0.75 0.0  0.0]
                                          [-0.5 -0.5  0.0]
                                          [ 0.5 -0.5  0.0]]})

  (render :triangles {:program    :no-camera-instanced
                      :vertices   triangle-vertices
                      :instances  4
                      :instances-positions [[-0.5  0.0  0.0]
                                            [ 0.5  0.0  0.0]
                                            [ 0.0  0.5  0.0]
                                            [ 0.0 -0.5  0.0]]})

  (render :square {:program    :no-camera
                   :vertices   square-vertices
                   :indices    [0 1 2 0 2 3]
                   :primitives :points})

  (render :triangle {:program  :no-camera
                     :vertices triangle-vertices})


  (reg-event [:press :delete]
             (fn [cofx fx]
               (delete fx :squares)))

  (reg-event [:press :shift :delete]
             (fn [cofx fx]
               (render fx :squares {:program    :no-camera-instanced
                                    :vertices   square-vertices
                                    :indices    [0 1 2 0 2 3]
                                    :primitives :points
                                    :instances  6
                                    :instances-positions [[-0.5  0.5  0.0]
                                                          [ 0.5  0.5  0.0]
                                                          [ 0.75 0.0  0.0]
                                                          [-0.75 0.0  0.0]
                                                          [-0.5 -0.5  0.0]
                                                          [ 0.5 -0.5  0.0]]})))
  )
