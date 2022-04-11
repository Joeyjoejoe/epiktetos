(ns epictetus.vocabulary.glfw
  (:require [epictetus.utils.keyword :as k])
  (:import (org.lwjgl.glfw GLFW)))

(defonce grammar (make-hierarchy))

(defonce dictionary
  {true                   GLFW/GLFW_TRUE
   false                  GLFW/GLFW_FALSE
   :callback/key          'GLFW/glfwSetKeyCallback
   :callback/char         'GLFW/glfwSetCharCallback
   :callback/cursor-pos   'GLFW/glfwSetCursorPosCallback
   :callback/cursor-enter 'GLFW/glfwSetCursorEnterCallback
   :callback/mouse-button 'GLFW/glfwSetMouseButtonCallback
   :callback/scroll       'GLFW/glfwSetScrollCallback
   :callback/joystick     'GLFW/glfwSetJoystickCallback
   :callback/drop         'GLFW/glfwSetDropCallback
   :cursor/normal         GLFW/GLFW_CURSOR_NORMAL
   :cursor/hidden         GLFW/GLFW_CURSOR_HIDDEN
   :cursor/disabled       GLFW/GLFW_CURSOR_DISABLED
   :cursor/raw-motion     [GLFW/GLFW_CURSOR_DISABLED GLFW/GLFW_RAW_MOUSE_MOTION]
   :mode/cursor           GLFW/GLFW_CURSOR
   :mode/lock-key         GLFW/GLFW_LOCK_KEY_MODS})

(k/derivev grammar
           [:callback/key      :callback/joystick
            :callback/char     :callback/cursor-pos
            :callback/drop     :callback/mouse-button
            :callback/scroll   :callback/cursor-enter]
            :input/callback)

(k/derivev grammar
           [:mode/cursor :mode/lock-key]
           :input/mode)
