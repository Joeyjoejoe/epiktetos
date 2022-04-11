(ns epictetus.window
  (:require [clojure.string :as s]
            [integrant.core :as ig]
            [epictetus.utils.buffer :as b]
            [epictetus.vocabulary.glfw :as glfw])
  (:import (org.lwjgl.glfw GLFW GLFWKeyCallback GLFWErrorCallback)
           (org.lwjgl.system MemoryUtil)
           (org.lwjgl.opengl GL11 GL)))

(defn get-size
  "Return the window dimensions"
  [window]
  (let [width (b/int-buffer [0])
        height (b/int-buffer [0])]
    (GLFW/glfwGetWindowSize window width height)
    {:width (.get width 0) :height (.get height 0)}))

(defn get-center
  "Return the coordinates of window center"
  [window]
  (let [size (get-size window)
        x (/ (:width size) 2.0)
        y (/ (:height size) 2.0)]
    {:x x :y y}))

(defn create
  "Create the game window and set the OpenGl context where everything will be draw"
  [params]
  (let [{:keys [title width height]} params]
    (GLFW/glfwSetErrorCallback (GLFWErrorCallback/createPrint System/err))

    (when-not (GLFW/glfwInit)
      (throw (IllegalStateException. "Unable to initialize GLFW")))
    (GLFW/glfwDefaultWindowHints)
    (GLFW/glfwWindowHint GLFW/GLFW_VISIBLE GLFW/GLFW_FALSE)
    (GLFW/glfwWindowHint GLFW/GLFW_RESIZABLE GL11/GL_TRUE)

    ;; Cross plateform compatiblity for OpenGL and GLSL (declaration order matters)
    (GLFW/glfwWindowHint GLFW/GLFW_CONTEXT_VERSION_MAJOR 3)
    (GLFW/glfwWindowHint GLFW/GLFW_CONTEXT_VERSION_MINOR 2)
    (GLFW/glfwWindowHint GLFW/GLFW_OPENGL_PROFILE GLFW/GLFW_OPENGL_CORE_PROFILE)
    (GLFW/glfwWindowHint GLFW/GLFW_OPENGL_FORWARD_COMPAT GL11/GL_TRUE)

    (GLFW/glfwCreateWindow ^Long width ^Long height ^String title (MemoryUtil/NULL) (MemoryUtil/NULL))))

(defn input-callback! [w property value]
  (let [set-fn   (property glfw/dictionary)
        cb-parts (s/split value #"/")
        cb-ns    (symbol (first cb-parts))
        cb-fn    (symbol (last  cb-parts))
        cb       (ns-resolve cb-ns cb-fn)]
    (if (nil? cb)
      (throw (Exception. (str "Callback function " value " do not exists."))))
      (set-fn w cb)))

(defn input-mode! [w property value]
    (let [mode  (property glfw/dictionary)
          value (get glfw/dictionary value)]
      (GLFW/glfwSetInputMode w mode value)))

(defn configure [w opts]
  (GLFW/glfwMakeContextCurrent w)
  (GLFW/glfwSwapInterval 1)
  (doseq [[k v] opts]
    (cond
      (isa? k :input/callback) (input-callback! w k v)
      (isa? k :input/mode)     (input-mode! w k v)))
  ;; --- (mouse/center window (get-center window))
  (GL/createCapabilities)
  (GL11/glEnable GL11/GL_DEPTH_TEST)
  w)

(defmethod ig/init-key :glfw/window [_ opts]
  (let [window (-> opts create (configure opts))]
    (println "OpenGL version:" (GL11/glGetString GL11/GL_VERSION))
    (GLFW/glfwShowWindow window)
    window))

(defmethod ig/halt-key! :glfw/window [_ window]
  (GLFW/glfwDestroyWindow window)
  (GLFW/glfwTerminate))

(defn mouse-callback [])
(defn keyboard-callback [])
