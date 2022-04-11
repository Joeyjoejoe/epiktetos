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



(defn window-hint! [property value]
  (let [hint  (property glfw/dictionary)
        value (or (get glfw/dictionary value) value)
        f     (cond
                (isa? glfw/grammar property :hint.type/integer) (:hint.type/integer glfw/dictionary)
                (isa? glfw/grammar property :hint.type/string)  (:hint.type/string glfw/dictionary))]
    (f hint value)))


(defn input-callback! [w property value]
  (let [f        (property glfw/dictionary)
        cb-parts (s/split value #"/")
        cb-ns    (symbol (first cb-parts))
        cb-fn    (symbol (last  cb-parts))
        cb       (ns-resolve cb-ns cb-fn)]
    (if (nil? cb)
        (throw (Exception. (str "Callback function " value " do not exists.")))
        (f w cb))))


(defn input-mode! [w property value]
    (let [mode  (property glfw/dictionary)
          value (get glfw/dictionary value)]
      (GLFW/glfwSetInputMode w mode value)))


(defn create-window
  "Create the game window and set the OpenGl context where everything will be draw"
  [params]
  (GLFW/glfwSetErrorCallback (GLFWErrorCallback/createPrint System/err))
  (when-not (GLFW/glfwInit)
    (throw (IllegalStateException. "Unable to initialize GLFW")))

  (GLFW/glfwDefaultWindowHints)

  (let [{:keys [title width height display]} params
        monitor (GLFW/glfwGetPrimaryMonitor)
        video   (GLFW/glfwGetVideoMode monitor)]

    (doseq [[k v] params]
      (cond
        (isa? glfw/grammar k :window/hint) (window-hint! k v)))

    (cond
      (= :fullscreen/windowed display)
      (do (GLFW/glfwWindowHint GLFW/GLFW_RED_BITS (.redBits video))
          (GLFW/glfwWindowHint GLFW/GLFW_GREEN_BITS (.greenBits video))
          (GLFW/glfwWindowHint GLFW/GLFW_BLUE_BITS (.blueBits video))
          (GLFW/glfwWindowHint GLFW/GLFW_REFRESH_RATE (.refreshRate video))
          (GLFW/glfwCreateWindow (.width video) (.height video) title monitor (MemoryUtil/NULL)))

      (= :fullscreen display)
      (GLFW/glfwCreateWindow (.width video) (.height video) title monitor (MemoryUtil/NULL))

      (= :windowed display)
      (GLFW/glfwCreateWindow width height title (MemoryUtil/NULL) (MemoryUtil/NULL)))))


(defn configure [w opts]
  (GLFW/glfwMakeContextCurrent w)
  (GLFW/glfwSwapInterval 1)
  (doseq [[k v] opts]
    (cond
      (isa? glfw/grammar k :input/callback) (input-callback! w k v)
      (isa? glfw/grammar k :input/mode)     (input-mode! w k v)))
  ;; --- (mouse/center window (get-center window))
  (GL/createCapabilities)
  (GL11/glEnable GL11/GL_DEPTH_TEST)
  w)


(defmethod ig/init-key :glfw/window [_ opts]
  (let [window (-> opts create-window (configure opts))]
    (println "OpenGL version:" (GL11/glGetString GL11/GL_VERSION))
    (GLFW/glfwShowWindow window)
    window))


(defmethod ig/halt-key! :glfw/window [_ window]
  (GLFW/glfwDestroyWindow window)
  (GLFW/glfwTerminate))


(defn mouse-callback [])
(defn keyboard-callback [])
