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

(defmethod ig/prep-key :gl/shaders [_ config]
  {:window (ig/ref :glfw/window) :shaders config})

(defmethod ig/init-key :gl/shaders [_ config]
  (into {} (for [[label {:keys [path stage]}] (:shaders config)]
             (compile-shader label stage path))))

(defn create-program
  [prog-conf]
  (assoc prog-conf :id (GL20/glCreateProgram)))


(defn build-pipeline
  [{:keys [pipeline] :as prog-conf} shaders]
  (let [shaders-pipeline (map #(% shaders) pipeline)]
    (assoc prog-conf :pipeline shaders-pipeline)))

(defn attach-shaders!
  [{:keys [id pipeline] :as prog-conf}]
  (doseq [{shader-id :id} pipeline]
    (GL20/glAttachShader id shader-id))
  prog-conf)

(defn link-program!
  [{:keys [id key] :as prog-conf}]
  (GL20/glLinkProgram id)
  (when (= 0 (GL20/glGetProgrami id GL20/GL_LINK_STATUS))
    (throw (Exception. (str
                         "Error linking shader to program " key ": "
                         (GL20/glGetProgramInfoLog id 1024)))))
  prog-conf)

(defn delete-shaders!
  [{:keys [pipeline] :as prog-conf}]
  (doseq [{shader-id :id} pipeline]
    (GL20/glDeleteShader shader-id))
  prog-conf)

(defn compile-program!
  [prog-conf]
  (-> prog-conf
      create-program
      attach-shaders!
      link-program!
      delete-shaders!))

(defn collect-uniforms
  [{:keys [pipeline] :as prog-conf}]
  (assoc prog-conf :uniforms
         (into {} (map :uniforms pipeline))))

(defmethod ig/prep-key :gl/programs [_ config]
  {:shaders  (ig/ref :gl/shaders)
   :programs config})

;; TODO replace pipeline by just the path to files
(defmethod ig/init-key
  :gl/programs
  [_ {:keys [programs shaders]}]
  (into {} (for [[prog-name prog-conf] programs]
              {prog-name (-> prog-conf
                            (assoc :key prog-name)
                            (build-pipeline shaders)
                            compile-program!
                            collect-uniforms)})))

;;  ;; Program config
;;  {:prog/default {:layout   [:coordinates :color]
;;                  :pipeline [:vert/default :frag/default]}}
;;
;;  ;; Prep program
;;  {:shaders  #ig/ref :gl/shaders
;;   :programs {:prog/default {:layout   [:coordinates :color]
;;                             :pipeline [:vert/default :frag/default]}}}

;;  ;; Program system
;;  {:prog/default {:id       1
;;                  :key      :prog/default
;;                  :layout   [:coordinates :color]
;;                  :pipeline ["shader/default.vert" "shaders/default.frag"]
;;                  :uniforms {"projection" :mat4
;;                             "worldPos"   :vec4}}}
