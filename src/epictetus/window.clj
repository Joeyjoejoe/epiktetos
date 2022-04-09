(ns epictetus.window
  (:require [integrant.core :as ig]
            [epictetus.utils.buffer :as b]
            ;;[clopengl.engine.glfw.controls.keyboard :as keyboard]
	          ;;[clopengl.engine.glfw.controls.mouse :as mouse]
            )
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

(defn configure [window]
  (GLFW/glfwMakeContextCurrent window)
  (GLFW/glfwSwapInterval 1)
  ;;  Init keyboard controls
  ;; --- (GLFW/glfwSetKeyCallback window keyboard/key-callback)
  (GLFW/glfwSetInputMode window GLFW/GLFW_STICKY_KEYS 1)
  ;; Hide mouse cursor and capture its position.
  ;; --- (mouse/center window (get-center window))
  (GLFW/glfwSetInputMode window GLFW/GLFW_CURSOR GLFW/GLFW_CURSOR_DISABLED)
  ;; --- (GLFW/glfwSetCursorPosCallback window mouse/fps-camera)
  (GL/createCapabilities)
  (GL11/glEnable GL11/GL_DEPTH_TEST)
  window)

(defmethod ig/init-key :window [_ opts]
  (let [window (create opts)]
    (-> window
        configure
        GLFW/glfwShowWindow)
    (println "OpenGL version:" (GL11/glGetString GL11/GL_VERSION))
    window))

(defmethod ig/halt-key! :window [_ window]
  (GLFW/glfwDestroyWindow window)
  (GLFW/glfwTerminate))
