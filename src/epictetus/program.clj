(ns epictetus.program
  (:require [integrant.core :as ig]
            [clojure.java.io :as io]
            [epictetus.utils.glsl-parser :as glsl]
            [epictetus.lang.opengl :as opengl])
  (:import  (org.lwjgl.opengl GL20)))


(defn compile-shader [label stage-key path]
  (let [stage    (stage-key opengl/dictionary)
        id       (GL20/glCreateShader stage)
        code     (-> path (io/resource) (slurp))
        metadata (glsl/analyze-shader code)]

    (when (= 0 id)
      (throw (Exception. (str "Error creating shader of type: " stage-key))))
    (GL20/glShaderSource id code)
    (GL20/glCompileShader id)
    (when (= 0 (GL20/glGetShaderi id GL20/GL_COMPILE_STATUS))
      (throw (Exception. (str "Error compiling shader: " (GL20/glGetShaderInfoLog id 1024) " in " path))))

    {label (merge {:id id :path path} metadata)}))

(defmethod ig/prep-key :opengl/shaders [_ config]
  {:window (ig/ref :glfw/window) :shaders config})

(defmethod ig/init-key :opengl/shaders [_ config]
  (into {} (for [[label {:keys [path stage]}] (:shaders config)]
             (compile-shader label stage path))))




(defn compile-program [label shader-ids]
  (let [program-id (GL20/glCreateProgram)
                   ;; Attach compiled shaders code to program
        _          (doseq [sid shader-ids] (GL20/glAttachShader program-id sid))
                   ;; Link program
        _          (do
                    (GL20/glLinkProgram program-id)
                    (when (= 0 (GL20/glGetProgrami program-id GL20/GL_LINK_STATUS))
                      (throw (Exception. (str
                                            "Error linking shader to program " label ": "
                                              (GL20/glGetProgramInfoLog program-id 1024))))))
      ;;             ;; Get uniforms locations
      ;;uniforms     (into {} (map
      ;;                        (fn [[k v]] [k (interface/data->opengl! :glsl/uniform v program-id)])
      ;;                        (:program/uniforms h)))
      ]

    ;; Delete shaders
    (doseq [sid shader-ids]
      (GL20/glDeleteShader sid))

    {label program-id}))

(defmethod ig/prep-key :opengl/programs [_ config]
  {:shaders (ig/ref :opengl/shaders) :programs config})

(defmethod ig/init-key :opengl/programs [_ config]
  (into {} (for [[label shaders] (:programs config)]
             (let [shader-ids (map :id
                                   (vals
                                    (select-keys (:shaders config) shaders)))]
              (compile-program label shader-ids)))))
