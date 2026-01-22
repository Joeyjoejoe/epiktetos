(ns epiktetos.registrar
  (:import  (org.lwjgl.opengl GL20)))

(defonce register
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


(defn get-vao-v2
  [hash-k]
  (get-in @register [::opengl ::vao hash-k]))

(defn register-vao
  [hash-k vao]
  (swap! register assoc-in [::opengl ::vao hash-k] vao))

(defn register-program
  [hash-k vao]
  (swap! register assoc-in [::opengl ::program hash-k] vao))
