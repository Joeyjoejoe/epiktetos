(ns epiktetos.opengl.shader
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import  (org.lwjgl.opengl GL20 GL32 GL40 GL43)))

(defonce STAGES
  {:vertex          GL20/GL_VERTEX_SHADER
   :fragment        GL20/GL_FRAGMENT_SHADER
   :geometry        GL32/GL_GEOMETRY_SHADER
   :tess_control    GL40/GL_TESS_CONTROL_SHADER
   :tess_evaluation GL40/GL_TESS_EVALUATION_SHADER
   :compute         GL43/GL_COMPUTE_SHADER})

(def ^:private re-binding-zero
  "Matches layout declarations with explicit binding = 0 (decimal or hex)."
  #"(?m)^.*\blayout\s*\([^)]*\bbinding\s*=\s*(?:0+|0x0+)\b[^)]*\).*$")

(def ^:private re-comment-line
  "Matches lines starting with single or multi-line comment."
  #"^\s*(?://|/\*)")

(def ^:private re-inline-comment
  "Matches inline block comments /* ... */."
  #"/\*[^*]*\*+(?:[^/*][^*]*\*+)*/")

(def ^:private re-excluded-types
  "Matches uniform types that are not UBOs (samplers, images, textures, atomic counters)."
  #"\buniform\s+(?:[iu]?(?:sampler|image|texture)\w*|atomic_uint)\b")

(def ^:private re-ubo-or-ssbo
  "Matches UBO (uniform block) or SSBO (buffer block) declarations.
   Supports optional memory qualifiers (coherent, volatile, restrict, readonly, writeonly)."
  #"\)\s*(?:(?:coherent|volatile|restrict|readonly|writeonly)\s+)*(?:buffer|uniform)\s+\w+")

(defn- strip-inline-comments
  "Removes inline block comments /* ... */ from a string."
  [s]
  (str/replace s re-inline-comment " "))

(defn find-explicit-binding-zero
  "Finds explicit binding = 0 declarations for UBOs and SSBOs in shader source.
   Only matches uniform blocks (UBO) and buffer blocks (SSBO), not samplers, images or atomic counters.
   Ignores commented lines and inline comments.
   Returns a sequence of matching lines, or nil if none found."
  [shader-source]
  (->> (re-seq re-binding-zero shader-source)
       (remove #(re-find re-comment-line %))
       (map strip-inline-comments)
       (remove #(re-find re-excluded-types %))
       (filter #(re-find re-ubo-or-ssbo %))
       seq))

(defn interpret
  "Compile a single shader file for the specified stage.
  Return shader id. shader-vec [stage-key shader-path]"
  [shader-vec]
  (let [[stagek path] shader-vec

        _   (when-not (io/resource path)
              (throw (ex-info (str "Shader file not found at " path)
                              {:type :path-error :in path})))

        src (-> path io/resource slurp)

            ;; This garantees preservation of explicit binding in shaders
        _   (when-let [violations (find-explicit-binding-zero src)]
              (throw (ex-info (str "Explicit binding = 0 is reserved for implicit bindings in " path)
                              {:type :binding-error :in path :errors violations})))

        id  (-> stagek STAGES GL20/glCreateShader)]

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
