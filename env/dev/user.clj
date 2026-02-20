(ns user
  (:require [epiktetos.core :refer [reg-event reg-cofx inject-cofx reg-fx reg-p dispatch render delete]]
            [epiktetos.render.step :refer [save-render-steps!]]
            [epiktetos.dev :as dev :refer [start inspector]])


  (:import (org.lwjgl.glfw GLFW)
           (org.joml Matrix4f Vector3f)
           (org.lwjgl BufferUtils)
           (org.lwjgl.opengl GL45)))

(save-render-steps!
  [[:per-material
    (fn [entity]
      (:material entity))]])

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
  {:pipeline [[:vertex "shaders/flat.vert"]
              [:fragment "shaders/blank.frag"]]
   :vertex-layout [{:layout  ["vLocal" "vColor"]
                    :handler colored-vertices-handler
                    :storage :dynamic}]})

(def no-camera-instanced
  {:pipeline [[:vertex "shaders/flat-instances.vert"]
              [:fragment "shaders/blank.frag"]]
   :vertex-layout [{:layout    ["vLocal" "vColor"]
                    :handler   colored-vertices-handler
                    :storage   :dynamic}
                   {:layout  ["instancePosition"]
                    :handler instance-positions-handler
                    :divisor 1}]})

(reg-p :no-camera no-camera)
(reg-p :no-camera-instanced no-camera-instanced)

(def triangle-vertices
  [{:coordinates [-0.5 -0.5 0.1] :color [1.0 0.0 0.0]}
   {:coordinates [ 0.5 -0.5 0.1] :color [0.0 1.0 0.0]}
   {:coordinates [ 0.0  0.5 0.1] :color [0.0 0.0 1.0]}])

(def square-vertices
  [{:coordinates [-0.5 -0.5 0.1] :color [1.0 0.0 0.0]}
   {:coordinates [ 0.5 -0.5 0.1] :color [0.0 1.0 0.0]}
   {:coordinates [ 0.5  0.5 0.1] :color [0.0 0.0 1.0]}
   {:coordinates [-0.5  0.5 0.1] :color [1.0 1.0 0.0]}])

(render :squares {:program    :no-camera-instanced
                  :vertices   square-vertices
                  :indices    [0 1 2 0 2 3]
                  :instances  4
                  :instances-positions [[-0.5  0.5  0.0]
                                        [ 0.5  0.5  0.0]
                                        [-0.5 -0.5  0.0]
                                        [ 0.5 -0.5  0.0]]})

(render :triangles {:program    :no-camera-instanced
                    :vertices   triangle-vertices
                    :instances  8
                    :instances-positions [[-0.5  0.5  0.0]
                                          [ 0.5  0.5  0.0]
                                          [-0.5 -0.5  0.0]
                                          [ 0.5 -0.5  0.0]
                                          [-0.5  0.0  0.0]
                                          [ 0.5  0.0  0.0]
                                          [ 0.0  0.5  0.0]
                                          [ 0.0 -0.5  0.0]]})

(render :square {:program    :no-camera
                 :vertices   square-vertices
                 :indices    [0 1 2 0 2 3]
                 :primitives :triangles})

(render :triangle {:program  :no-camera
                   :vertices triangle-vertices})

(comment

(delete :triangle)


)
