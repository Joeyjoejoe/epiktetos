(ns epiktetos.program
  (:require [clojure.java.io :as io]
            [epiktetos.registrar :as registrar]
            [epiktetos.vao.buffer :as vao-buffer]
            [epiktetos.vao.attrib :as vao-attrib]
            [epiktetos.uniform :as u]
            [epiktetos.utils.glsl-parser :as glsl]
            [epiktetos.lang.opengl :as opengl])
  (:import  (org.lwjgl.opengl GL11 GL20 GL45)))


;; Return a queue of uniforms values :
;; [[:model :mat4 1]
;;  [:view :mat4 2]
;;  ...]
(defn compile-uniforms
  [p-id u-seq]

  (GL20/glUseProgram p-id)
  (let [unif-with-locations (map #(conj % (u/locate-u p-id (name (first %))))
                                 u-seq)]
    (GL20/glUseProgram 0)
    (apply conj clojure.lang.PersistentQueue/EMPTY unif-with-locations)))

(def PROG-MAP-KEYS #{:buffers :pipeline})

(defn parse-shader
  "parse a single shader source and return a map of meta data"
  [[stage path]]
  (when-not (io/resource path)
    (throw (java.io.FileNotFoundException.
             (str "Shader file not found at " path))))

  (let [shader-src (-> path (io/resource) (slurp))
        shader-map (glsl/analyze-shader shader-src)]
    (assoc shader-map :path path :stage stage :src shader-src)))

(defn compile-shader
  [shader-map]
  (let [{:keys [src path stage]} shader-map
        shader-id (-> stage opengl/DICTIONARY GL20/glCreateShader)]

    (when (= 0 shader-id)
      (throw (Exception. (str "Error creating shader "
                              [stage path]))))

    (GL20/glShaderSource shader-id src)
    (GL20/glCompileShader shader-id)

    (when (= 0 (GL20/glGetShaderi shader-id GL20/GL_COMPILE_STATUS))
      (throw (Exception. (str "shader compilation error: "
                              [stage path]
                              (GL20/glGetShaderInfoLog shader-id 1024)))))

    (assoc shader-map :id shader-id)))

(defn link-program
  [prog-map]
  (let [{:keys [id name shaders]} prog-map
        shader-ids (mapv :id shaders)]

    (doseq [shader-id shader-ids]
      (GL20/glAttachShader id shader-id))

    (GL20/glLinkProgram id)

    (when (= 0 (GL20/glGetProgrami id GL20/GL_LINK_STATUS))
      (throw (Exception. (str "Error linking shader to program " name ": "
                              (GL20/glGetProgramInfoLog id 1024)))))

    (doseq [shader-id shader-ids]
      (GL20/glDeleteShader shader-id))
    prog-map))

(defn parse-shaders
  [prog-map]
  (let [pipeline (:pipeline prog-map)
        shaders  (mapv #(-> % parse-shader compile-shader)
                       pipeline)]

    (assoc prog-map :shaders shaders)))

(defn parse-buffers
  [prog-map]
  (let [{:keys [buffers shaders]} prog-map
        shader-attribs (mapcat #(get-in % [:attribs]) shaders)
        preped-buffers (vec (map-indexed #(-> %2
                                              (assoc :binding-index %1)
                                              (assoc :shader-attribs shader-attribs)
                                              vao-buffer/parse)
                                         buffers))]

    (assoc prog-map :buffers preped-buffers)))


(defn locate-uniforms
  [prog-map]
  (let [{:keys [id shaders]} prog-map
        uniforms (compile-uniforms id (mapcat :uniforms shaders))]
    (assoc prog-map :uniforms uniforms)))

(defn create-program
  [prog-map]
  (let [prog-id (GL20/glCreateProgram)]

    (-> prog-map
        (assoc :id prog-id)
        link-program
        locate-uniforms)))

(defn create-attributes
  [prog-map]
  (let [{:keys [buffers]} prog-map
        attribs (mapcat #(:attribs %) buffers)

        {vao-id :vao/id :as vao}
        (or (registrar/get-vao buffers)
             #:vao{:id (GL45/glCreateVertexArrays) :layout buffers})]


    (doseq [[location attrib] (map-indexed vector attribs)]
      (vao-attrib/add-attrib vao-id (assoc attrib ::vao-attrib/location location)))

    (registrar/add-vao! vao)
    (assoc prog-map :vao vao-id)))

(defn create
  [prog-map]
  (-> prog-map
      parse-shaders   ;; add shaders infos
      parse-buffers
      create-attributes
      create-program))
