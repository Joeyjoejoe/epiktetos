(ns epictetus.lang.glfw
  (:require [epictetus.utils.keyword :as k])
  (:import (org.lwjgl.glfw GLFW)))

(defonce dictionary
  {true                   GLFW/GLFW_TRUE
   false                  GLFW/GLFW_FALSE

   ;; Inputs callbacks
   :callback/key          'GLFW/glfwSetKeyCallback
   :callback/char         'GLFW/glfwSetCharCallback
   :callback/cursor-pos   'GLFW/glfwSetCursorPosCallback
   :callback/cursor-enter 'GLFW/glfwSetCursorEnterCallback
   :callback/mouse-button 'GLFW/glfwSetMouseButtonCallback
   :callback/scroll       'GLFW/glfwSetScrollCallback
   :callback/joystick     'GLFW/glfwSetJoystickCallback
   :callback/drop         'GLFW/glfwSetDropCallback

   ;; Inputs
   :cursor/normal         GLFW/GLFW_CURSOR_NORMAL
   :cursor/hidden         GLFW/GLFW_CURSOR_HIDDEN
   :cursor/disabled       GLFW/GLFW_CURSOR_DISABLED
   :cursor/raw-motion     GLFW/GLFW_RAW_MOUSE_MOTION
   :mode/cursor           GLFW/GLFW_CURSOR
   :mode/lock-key         GLFW/GLFW_LOCK_KEY_MODS

   ;; Window creation hints
   ;; https://www.glfw.org/docs/3.3/window_guide.html#window_hints
   :hint.type/integer              'GLFW/glfwWindowHint
   :hint.type/string               'GLFW/glfwWindowHintString
   :hint.window/resizable          GLFW/GLFW_RESIZABLE
   :hint.window/visible            GLFW/GLFW_VISIBLE
   :hint.context/version.maj       GLFW/GLFW_CONTEXT_VERSION_MAJOR
   :hint.context/version.min       GLFW/GLFW_CONTEXT_VERSION_MINOR
   :hint.context/gl.profile        GLFW/GLFW_OPENGL_PROFILE
   :hint.context/gl.forward-compat GLFW/GLFW_OPENGL_FORWARD_COMPAT

   ;; Values
   :gl.profile/core   GLFW/GLFW_OPENGL_CORE_PROFILE
   :gl.profile/compat GLFW/GLFW_OPENGL_COMPAT_PROFILE
   :gl.profile/any    GLFW/GLFW_OPENGL_ANY_PROFILE})

(defonce grammar
  (-> (make-hierarchy)
      (derive :fullscreen/windowed :fullscreen)
      (k/derivev
        [:callback/key      :callback/joystick
         :callback/char     :callback/cursor-pos
         :callback/drop     :callback/mouse-button
         :callback/scroll   :callback/cursor-enter]
        :input/callback)
      (k/derivev
        [:mode/cursor :mode/lock-key]
        :input/mode)
      (k/derivev
        [:hint.type/integer :hint.type/string]
        :window/hint)
      (k/derivev
        [:hint.window/resizable   :hint.context/gl.forward-compat
         :hint.window/visible     :hint.context/version.maj
         :hint.context/gl.profile :hint.context/version.min]
        :hint.type/integer)))
