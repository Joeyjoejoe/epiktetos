(ns epiktetos.vao.buffer
  (:require [epiktetos.utils.buffer :as buffer-utils]
            [epiktetos.vao.attrib :as vao-attrib]
            [epiktetos.registrar :as register]
            [clojure.string :refer [replace]])
  (:import  (org.lwjgl.opengl GL15 GL30 GL44 GL45)))


(defonce BUFFER-KEYS #{:layout :source :storage :iterate :stride})

(defonce BUFFER-STORAGE-TYPES
  {:dynamic    GL44/GL_DYNAMIC_STORAGE_BIT
   :read       GL30/GL_MAP_READ_BIT
   :write      GL30/GL_MAP_WRITE_BIT
   :persistent GL44/GL_MAP_PERSISTENT_BIT
   :coherent   GL44/GL_MAP_COHERENT_BIT
   :client     GL44/GL_CLIENT_STORAGE_BIT})

(defn read-key
  [data-src k]
  (if-let [data (get data-src k)]
    data
    (throw (Exception. (str k " is empty in entity")))))

(defn read-keys
  [data-src src-ks]
  (->> src-ks
       (map #(read-key data-src %))
       flatten))

;; TODO Update to new buffer management
(defn extract-data
  [entity buffer]
  (let [{:keys [source layout]} buffer
        data-src (get-in entity source)
        src-ks (mapv #(-> % name keyword) layout)]

    (cond
      (map? data-src) (read-keys data-src src-ks)
      (sequential? data-src) (flatten (mapcat #(read-keys % src-ks) data-src)))))

;; TODO Update to new buffer management
 (defn pack-vertices
   [entity schema]

 ;;  (let [vertices (get-in entity [:assets :vertices])]
 ;;    (flatten (mapcat #(pack-vertex % schema) vertices)))

   )

;; TODO Do not create duplicates buffers
(defn load-entity
  [entity buffer]
  (let [{:keys [storage]} buffer
        buffer-id (GL45/glCreateBuffers)
        data (-> entity
                 (extract-data buffer)
                 (buffer-utils/float-buffer))]

    (GL45/glNamedBufferStorage buffer-id data storage)
    (assoc buffer :id buffer-id)))

(defn load-buffers
  [entity buffers]
  (let [bs (mapv #(load-entity entity %) buffers)]
    (assoc entity :buffers bs)))

(defn attach-vao
  [vao buffer]
  (let [{:keys [id stride binding-index]} buffer]
    (GL45/glVertexArrayVertexBuffer vao binding-index id 0 stride)))

(defn create-ibo
  [entity]
  (if-let [indices (get-in entity [:assets :indices])]
    (let [ibo-id   (GL45/glCreateBuffers)
          ibo-data (buffer-utils/int-buffer indices)
          ibo-length (count indices)]

      (GL45/glNamedBufferStorage ibo-id ibo-data GL44/GL_DYNAMIC_STORAGE_BIT)

      (-> entity
          (assoc :ibo ibo-id)
          (assoc :ibo-length ibo-length)))
    entity))

(defn delete
  [buffer]
  (GL15/glDeleteBuffers (:id buffer)))

(defn gpu-load!
  [entity vao]
  (let [{:keys [program]} entity
        {id :vao/id layout :vao/layout} vao
        buffers (-> program
                    register/get-prog
                    :buffers)]
    (-> entity
        (load-buffers buffers)
        (create-ibo)
        (assoc :vao id))))

(defn parse-layout
  [layout binding-index]
  (let [attribs (->> layout
                     (map vao-attrib/parse-key)
                     (mapv #(assoc % ::vao-attrib/binding-index binding-index)))]

    (map-indexed (fn [i attr]
                   (let [offset (vao-attrib/get-offset attribs i)]
                     (assoc attr ::vao-attrib/byte-offset offset)))
                 attribs)))

(defn parse
  [buffer-map]
  (let [{:keys [divisor storage layout source shader-attribs binding-index]
         :or {storage :dynamic divisor 0 binding-index 0}}
        buffer-map
        attribs (parse-layout layout binding-index)
        stride  (reduce #(+ %1 (::vao-attrib/byte-size %2)) 0 attribs)]

    (assoc buffer-map
           :attribs attribs
           :stride  stride
           :storage (get BUFFER-STORAGE-TYPES storage)
           :divisor divisor)))

;; Alternative to glVertexAttribPointer :
;; https://www.khronos.org/opengl/wiki/Vertex_Specification#Separate_attribute_format
;;
;; Examples & explanations :
;;  - https://gamedev.stackexchange.com/questions/46184/opengl-is-it-possible-to-use-vaos-without-specifying-a-vbo
;;  - https://docs.google.com/presentation/d/13t-x_HWZOip8GWLAdlZu6_jV-VnIb0-FQBTVnLIsRSw/edit#slide=id.g75eed9a1c_0_183
;;  - https://stackoverflow.com/questions/37972229/glvertexattribpointer-and-glvertexattribformat-whats-the-difference
