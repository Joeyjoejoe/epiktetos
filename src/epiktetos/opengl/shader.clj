(ns epiktetos.opengl.shader
  (:require [clojure.java.io :as io])
  (:import  (org.lwjgl.opengl GL20 GL32 GL40 GL43)))

(defonce STAGES
  {:vertex          GL20/GL_VERTEX_SHADER
   :fragment        GL20/GL_FRAGMENT_SHADER
   :geometry        GL32/GL_GEOMETRY_SHADER
   :tess_control    GL40/GL_TESS_CONTROL_SHADER
   :tess_evaluation GL40/GL_TESS_EVALUATION_SHADER
   :compute         GL43/GL_COMPUTE_SHADER})

(defn interpret
  "Compile a single shader file for the specified stage.
  Return shader id. shader-vec [stage-key shader-path]"
  [shader-vec]
  (let [[stagek path] shader-vec

        _   (when-not (io/resource path)
              (-> (str "Shader file not found at " path)
                  java.io.FileNotFoundException.
                  throw))

        id  (-> stagek STAGES GL20/glCreateShader)
        src (-> path io/resource slurp)]

    (when (= 0 id)
      (-> (str "Error creating shader object for " shader-vec)
          Exception.
          throw))

    (GL20/glShaderSource id src)
    (GL20/glCompileShader id)

    (when (= 0 (GL20/glGetShaderi id GL20/GL_COMPILE_STATUS))
      (-> (str "shader compilation error: " shader-vec (GL20/glGetShaderInfoLog id 1024))
          Exception.
          throw))

    id))
