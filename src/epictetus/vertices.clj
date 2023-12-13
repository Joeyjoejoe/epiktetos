(ns epictetus.vertices
  (:require [integrant.core :as ig]
            [epictetus.state :as state]
            [epictetus.utils.buffer :as buffer])
  (:import (org.lwjgl BufferUtils)
           (org.lwjgl.opengl GL11 GL15 GL20 GL30 GL44 GL45)))

;; TODO Add all possible types in thoses constants
(defonce gl-types
  {:float GL11/GL_FLOAT
   :byte  GL11/GL_BYTE})

(defonce type-bytes-sizes
  {:float java.lang.Float/BYTES
   :byte  java.lang.Byte/BYTES})

(defn attrib-bytes
  "Returns the bytes size of the given attrib"
  [{:keys [size type] :as attrib}]
  (let [type-bytes (type type-bytes-sizes)]
    (* size type-bytes)))

(defn attrib-offset
  "Given the index of an attrib in attribs collection,
  returns the bytes size sum of previous attribs"
  [attribs index]
  (let [prev-attribs      (subvec attribs 0 index)
        sum-attribs-bytes #(+ %1 (attrib-bytes %2))]
    (reduce sum-attribs-bytes 0 prev-attribs)))

(defn create-vao
  "Initialize a new vao with given attributes configurations. Returns
  a map "
  [vao-name attribs]
  (let [vao       (GL45/glCreateVertexArrays)
        stride   (reduce #(+ %1 (attrib-bytes %2)) 0 attribs)]

    (doseq [[index {:keys [key size type]}] (map-indexed vector attribs)]
      (let [offset (attrib-offset attribs index)]
        (GL45/glVertexArrayAttribFormat vao index size (type gl-types) false offset)
        (GL45/glEnableVertexArrayAttrib vao index)
        (GL45/glVertexArrayAttribBinding vao index 0)))

    {vao-name {:id vao
               :attribs attribs
               :stride stride}}))

(defmethod ig/prep-key :gl/vaos [_ config]
  {:window (ig/ref :glfw/window) :vaos config})

(defmethod ig/init-key :gl/vaos [_ config]
  (into {} (map
             (fn [[vao-name attribs]]
               (create-vao vao-name attribs))
             (:vaos config))))

;; Delete buffers and reset state/rendering
(defmethod ig/halt-key! :gl/vaos [_ system]
  (doseq [[vao entities] system]
    (for [[_ {:keys [vbo]}] entities]
      (GL15/glDeleteBuffers vbo))
    (reset! state/rendering {})))

(defn pack-vertex
  [vertex schema]
  (mapcat #((keyword %) vertex) schema))

(defn pack-vertices
  [entity schema]
  (let [vertices (get-in entity [:assets :vertices])]
    (mapcat #(pack-vertex % schema) vertices)))

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

(defn gpu-load!
  [{id     :vao/id
    layout :vao/layout :as vao}
   {:keys [program]    :as entity}]
  (let [schema  (map name layout)]
    (-> entity
        (create-vbo schema)
        (assoc :vao id))))


;; Alternative to glVertexAttribPointer :
;; https://www.khronos.org/opengl/wiki/Vertex_Specification#Separate_attribute_format
;;
;; Examples & explanations :
;;  - https://gamedev.stackexchange.com/questions/46184/opengl-is-it-possible-to-use-vaos-without-specifying-a-vbo
;;  - https://docs.google.com/presentation/d/13t-x_HWZOip8GWLAdlZu6_jV-VnIb0-FQBTVnLIsRSw/edit#slide=id.g75eed9a1c_0_183
;;  - https://stackoverflow.com/questions/37972229/glvertexattribpointer-and-glvertexattribformat-whats-the-difference
