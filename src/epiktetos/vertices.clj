(ns epiktetos.vertices
  (:require [epiktetos.utils.buffer :as buffer]
            [clojure.string :refer [replace]])
  (:import  (org.lwjgl.opengl GL15 GL44 GL45)))

(defn pack-vertex
  [vertex schema]
  (mapcat #((keyword %) vertex) schema))

(defn pack-vertices
  [entity schema]
  (let [vertices (get-in entity [:assets :vertices])]
    (flatten (mapcat #(pack-vertex % schema) vertices))))

;; TODO Do not create duplicates vbo if a model witgh same assets
;; alredy exists
(defn create-vbo
  [entity schema]
  (let [vbo-id   (GL45/glCreateBuffers)
        vertices (-> entity
                     (pack-vertices schema)
                     (buffer/float-buffer))]
    (GL45/glNamedBufferStorage vbo-id vertices GL44/GL_DYNAMIC_STORAGE_BIT)
    (assoc entity :vbo vbo-id)))

(defn create-ibo
  [entity]
  (if-let [indices (get-in entity [:assets :indices])]
    (let [ibo-id   (GL45/glCreateBuffers)
          ibo-data (buffer/int-buffer indices)
          ibo-length (count indices)]

      (GL45/glNamedBufferStorage ibo-id ibo-data GL44/GL_DYNAMIC_STORAGE_BIT)

      (-> entity
          (assoc :ibo ibo-id)
          (assoc :ibo-length ibo-length)))
    entity))

(defn gpu-load!
  [{:keys [program]    :as entity}
   {id     :vao/id
    layout :vao/layout :as vao}]
  (let [schema  (map #(replace (name %)
                               #"\[.*\]" "")
                     layout)]
    (-> entity
        (create-vbo schema)
        (create-ibo)
        (assoc :vao id))))


;; Alternative to glVertexAttribPointer :
;; https://www.khronos.org/opengl/wiki/Vertex_Specification#Separate_attribute_format
;;
;; Examples & explanations :
;;  - https://gamedev.stackexchange.com/questions/46184/opengl-is-it-possible-to-use-vaos-without-specifying-a-vbo
;;  - https://docs.google.com/presentation/d/13t-x_HWZOip8GWLAdlZu6_jV-VnIb0-FQBTVnLIsRSw/edit#slide=id.g75eed9a1c_0_183
;;  - https://stackoverflow.com/questions/37972229/glvertexattribpointer-and-glvertexattribformat-whats-the-difference
