(ns epiktetos.utils.buffer
  (:import (org.lwjgl BufferUtils)))

(defn float-buffer
  "Create an float array buffer from data"
  [data]
  (let [data (float-array data)]
    (-> (BufferUtils/createFloatBuffer (count data))
      (.put data)
      (.flip))))

(defn int-buffer
  "Create an integer array buffer from data"
  [data]
  (let [data (int-array data)]
    (-> (BufferUtils/createIntBuffer (count data))
      (.put data)
      (.flip))))
