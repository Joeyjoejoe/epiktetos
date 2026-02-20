(ns epiktetos.registrar
  (:import  (org.lwjgl.opengl GL20)))

(defonce register
  (atom {}))

(defonce render-state
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
  (get-in @register [::opengl :vaos hash-k]))

(defn register-vao
  [hash-k vao]
  (swap! register assoc-in [::opengl :vaos hash-k] vao))

(defn register-program
  [hash-k program]
  (swap! register assoc-in [::opengl :programs hash-k] program))

(defn get-program
  [program-k]
  (get-in @register [::opengl :programs program-k]))

(defn find-vao-by-layout
  "Finds a registered VAO by its layout hash"
  [layout-hash]
  (->> (get-in @register [::opengl :vaos])
       (filter (fn [[_ vao]] (= layout-hash (:layout-hash vao))))
       first
       second))

(defn lookup-resource
  ([resource]
   (get-in @register [::opengl resource]))
  ([resource varname]
   (get-in @register [::opengl resource varname])))

(defn register-ubo!
  [ubo]
  (let [{:keys [varname buffer-binding alloc members buffer-data-size]} ubo]
    (swap! register assoc-in [::opengl :ubos varname]
           {:varname varname
            :resource :ubo
            :buffer-data-size buffer-data-size
            :members members
            :alloc alloc
            :binding-point buffer-binding})))

(defn register-ssbo!
  [ssbo]
  (let [{:keys [varname buffer-binding alloc members buffer-data-size]} ssbo]
    (swap! register assoc-in [::opengl :ssbos varname]
           {:varname varname
            :resource :ssbo
            :buffer-data-size buffer-data-size
            :members members
            :alloc alloc
            :binding-point buffer-binding})))
