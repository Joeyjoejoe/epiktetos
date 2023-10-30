(ns epictetus.vertices
  (:require [integrant.core :as ig])
  (:import (org.lwjgl.opengl GL11 GL20 GL30)))

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
  (let [id       (GL30/glGenVertexArrays)
        stride   (reduce #(+ %1 (attrib-bytes %2)) 0 attribs)
        vao-info {:id      id
                  :stride  stride
                  :attribs (map-indexed #(assoc %2 :offset (attrib-offset attribs %1))
                                        attribs)}]
   {vao-name vao-info}))

(defmethod ig/prep-key :gl/vaos [_ config]
  {:window (ig/ref :glfw/window) :vaos config})

(defmethod ig/init-key :gl/vaos [_ config]
  (into {} (map
             (fn [[vao-name attribs]]
               (create-vao vao-name attribs))
             (:vaos config))))

(defn gpu-load
  [{:keys [id stride attribs] :as vao} entity]
  (GL30/glBindVertexArray id)
  (let [schema (map :key attribs)]


  (doseq [[index {:keys [key size type offset]}] (map-indexed vector attribs)]
    (GL20/glVertexAttribPointer index size (type gl-types) false stride offset)
    (GL20/glEnableVertexAttribArray index))

  (GL30/glBindVertexArray 0)
  (assoc entity :vao/id id)))


