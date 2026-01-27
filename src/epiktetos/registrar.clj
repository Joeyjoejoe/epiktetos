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
  [hash-k program]
  (swap! register assoc-in [::opengl ::program hash-k] program))

(defn get-program
  [program-k]
  (get-in @register [::opengl ::program program-k]))

(defn lookup-resource
  ([resource]
   (get-in @register [::opengl resource]))
  ([resource varname]
   (get-in @register [::opengl resource varname])))

(defn register-ubo!
  [ubo]
  (let [{:keys [varname program buffer-binding alloc members buffer-data-size]} ubo
        ubo-map (or (lookup-resource ::ubo varname)
                    {:varname varname
                     :resource :ubo
                     :buffer-data-size buffer-data-size
                     :members members
                     :alloc alloc
                     :binding-point buffer-binding
                     :programs #{}})]

    (->> (update ubo-map :programs conj program)
         (swap! register assoc-in [::opengl ::ubo varname]))))

(defn register-ssbo!
  [ssbo]
  (let [{:keys [varname program buffer-binding alloc members buffer-data-size]} ssbo
        ssbo-map (or (lookup-resource ::ssbo varname)
                    {:varname varname
                     :resource :ssbo
                     :buffer-data-size buffer-data-size
                     :members members
                     :alloc alloc
                     :binding-point buffer-binding
                     :programs #{}})]

    (->> (update ssbo-map :programs conj program)
         (swap! register assoc-in [::opengl ::ssbo varname]))))
