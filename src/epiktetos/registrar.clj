(ns epiktetos.registrar
  (:import  (org.lwjgl.opengl GL20)))

(def register
  (atom {}))

(defn get-vao
  [layout]
  (get-in @register [:vao layout]))

(defn add-vao!
  [vao]
  (let [layout (:vao/layout vao)]
    (swap! register assoc-in [:vao layout] vao)))

(defn get-prog [k]
  (get-in @register [:program k]))

(defn add-program!
  [prog]
  (let [k (:name prog)]

    ;; Flag previous program for deletion to free up some memory
    (when-let [old-prog (get-prog k)]
      (GL20/glDeleteProgram (:id old-prog)))

    (swap! register assoc-in [:program k] prog)))
