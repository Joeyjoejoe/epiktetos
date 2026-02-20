(ns epiktetos.window
  (:require [clojure.string :as s]
            [integrant.core :as ig]
            [epiktetos.controls :as controls]
            [epiktetos.utils.buffer :as b]
            [epiktetos.lang.glfw :as glfw])
  (:import (org.lwjgl.glfw GLFW GLFWKeyCallback GLFWErrorCallback GLFWCursorPosCallback)
           (org.lwjgl.system MemoryUtil)
           (org.lwjgl.opengl GL GL11 GL20 GL32 GL43 GLDebugMessageCallback)))


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
    [x y]))

(defn window-hint! [property value]
  (let [hint  (property glfw/DICTIONARY)
        value (or (get glfw/DICTIONARY value) value)
        f     (cond
                (isa? glfw/GRAMMAR property :hint.type/integer) (:hint.type/integer glfw/DICTIONARY)
                (isa? glfw/GRAMMAR property :hint.type/string)  (:hint.type/string glfw/DICTIONARY))]
    (f hint value)))

(defn input-mode! [w property value]
    (let [mode  (property glfw/DICTIONARY)
          value (get glfw/DICTIONARY value)]
      (GLFW/glfwSetInputMode w mode value)))






(def ignored-messages #{131169 131185 131218 131204})

(def debug-sources
  {GL43/GL_DEBUG_SOURCE_API             "Source: API"
   GL43/GL_DEBUG_SOURCE_WINDOW_SYSTEM   "Source: Window System"
   GL43/GL_DEBUG_SOURCE_SHADER_COMPILER "Source: Shader Compiler"
   GL43/GL_DEBUG_SOURCE_THIRD_PARTY     "Source: Third Party"
   GL43/GL_DEBUG_SOURCE_APPLICATION     "Source: Application"
   GL43/GL_DEBUG_SOURCE_OTHER           "Source: Other"})

(def debug-types
  {GL43/GL_DEBUG_TYPE_ERROR               "Type: Error"
   GL43/GL_DEBUG_TYPE_DEPRECATED_BEHAVIOR "Type: Deprecated Behaviour"
   GL43/GL_DEBUG_TYPE_UNDEFINED_BEHAVIOR  "Type: Undefined Behaviour"
   GL43/GL_DEBUG_TYPE_PORTABILITY        "Type: Portability"
   GL43/GL_DEBUG_TYPE_PERFORMANCE        "Type: Performance"
   GL43/GL_DEBUG_TYPE_MARKER             "Type: Marker"
   GL43/GL_DEBUG_TYPE_PUSH_GROUP         "Type: Push Group"
   GL43/GL_DEBUG_TYPE_POP_GROUP          "Type: Pop Group"
   GL43/GL_DEBUG_TYPE_OTHER              "Type: Other"})

(def debug-severities
  {GL43/GL_DEBUG_SEVERITY_HIGH         "Severity: high"
   GL43/GL_DEBUG_SEVERITY_MEDIUM       "Severity: medium"
   GL43/GL_DEBUG_SEVERITY_LOW          "Severity: low"
   GL43/GL_DEBUG_SEVERITY_NOTIFICATION "Severity: notification"})

(def debug-callback
  (proxy [GLDebugMessageCallback] []
    (invoke [source type id severity length message user-param]
      (when-not (contains? ignored-messages id)
        (println "---------------")
        (println "Debug message (" id "): " message)
        (println (get debug-sources source "Unknown source"))
        (println (get debug-types type "Unknown type"))
        (println (get debug-severities severity "Unknown severity"))
        (println user-param)
        (println length)
        (println)))))

(defn setup-debug-callback!
  "Configure le callback de debug OpenGL. À appeler après la création du contexte OpenGL."
  []
  (GL43/glDebugMessageCallback debug-callback 0)  ;; 0 pour user-param
  (GL11/glEnable GL43/GL_DEBUG_OUTPUT)
  (GL11/glEnable GL43/GL_DEBUG_OUTPUT_SYNCHRONOUS))









(defn create-window
  "Create the game window and set the OpenGl context where everything will be draw"
  [params]
  (GLFW/glfwSetErrorCallback (GLFWErrorCallback/createPrint System/err))

  ;; WORKAROUND after LWJGL update to 3.3.6 :
  ;; https://github.com/glfw/glfw/issues/2793
  ;; (May not be needed anymore)
  (GLFW/glfwInitHint GLFW/GLFW_PLATFORM GLFW/GLFW_PLATFORM_X11)

  (when-not (GLFW/glfwInit)
    (throw (IllegalStateException. "Unable to initialize GLFW")))

  (GLFW/glfwDefaultWindowHints)

  (let [{:keys [title width height display]} params
        monitor (GLFW/glfwGetPrimaryMonitor)
        video   (GLFW/glfwGetVideoMode monitor)]

    (doseq [[k v] params]
      (cond
        (isa? glfw/GRAMMAR k :window/hint) (window-hint! k v)))

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

  ;; Enable (1) vsync
  (GLFW/glfwSwapInterval 1)

  (controls/set-callbacks w)

  (doseq [[k v] opts]
    (cond
      (isa? glfw/GRAMMAR k :input/mode) (input-mode! w k v)))

  (GLFW/glfwSetCursorPos w (double (first (get-center w))) (double (last (get-center w))))

  (GL/createCapabilities)

  (setup-debug-callback!)

  ;; TODO server-side GL capabilities should be activable/disablable
  ;; at render time on a per program/entity basis.

  ;; Enable Depth test
  (GL11/glEnable GL11/GL_DEPTH_TEST)
  (GL11/glDepthFunc GL11/GL_LEQUAL)

  ;; Enable points primitive size
  (GL11/glEnable GL20/GL_VERTEX_PROGRAM_POINT_SIZE) ;; gl_PointSize
  (GL11/glEnable GL20/GL_POINT_SPRITE) ;; gl_PointCoord

  ;; Enable lines primitive size & type
  (GL11/glEnable GL11/GL_LINE_SMOOTH)
  (GL11/glLineWidth 1.0)

  ;; Enable transparancy support
  (GL11/glEnable GL11/GL_BLEND)
  (GL11/glBlendFunc GL11/GL_SRC_ALPHA GL11/GL_ONE_MINUS_SRC_ALPHA);

  w)


(defmethod ig/init-key :glfw/window [_ opts]
  (let [window (-> opts
                   create-window
                   (configure opts))]

    (println "OpenGL version:" (GL11/glGetString GL11/GL_VERSION))
    (GLFW/glfwShowWindow window)
    window))


(defmethod ig/halt-key! :glfw/window [_ window]
  (GLFW/glfwDestroyWindow window)
  (GLFW/glfwTerminate))
