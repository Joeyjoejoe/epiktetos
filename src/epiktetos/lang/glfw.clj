(ns epiktetos.lang.glfw
  (:require [epiktetos.utils.keyword :as k])
  (:import (org.lwjgl.glfw GLFW)))

(defonce DICTIONARY
  {true                   GLFW/GLFW_TRUE
   false                  GLFW/GLFW_FALSE

   ;; Cursor mode
   :mode/cursor           GLFW/GLFW_CURSOR
   :cursor/normal         GLFW/GLFW_CURSOR_NORMAL
   :cursor/hidden         GLFW/GLFW_CURSOR_HIDDEN
   :cursor/disabled       GLFW/GLFW_CURSOR_DISABLED
   :cursor/raw-motion     GLFW/GLFW_RAW_MOUSE_MOTION

   ;; Boolean modes
   :mode/sticky-mouse-buttons  GLFW/GLFW_STICKY_MOUSE_BUTTONS
   :mode/sticky-keys           GLFW/GLFW_STICKY_KEYS
   :mode/lock-key              GLFW/GLFW_LOCK_KEY_MODS
   :mode/raw-mouse-motion      GLFW/GLFW_RAW_MOUSE_MOTION

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
   :hint.window/gl.debug-context   GLFW/GLFW_OPENGL_DEBUG_CONTEXT

   ;; Values
   :gl.profile/core   GLFW/GLFW_OPENGL_CORE_PROFILE
   :gl.profile/compat GLFW/GLFW_OPENGL_COMPAT_PROFILE
   :gl.profile/any    GLFW/GLFW_OPENGL_ANY_PROFILE})

(defonce GRAMMAR
  (-> (make-hierarchy)
      (derive :fullscreen/windowed :fullscreen)
      (k/derivev
        [:mode/cursor :mode/lock-key :mode/sticky-keys
         :mode/sticky-mouse-buttons :mode/raw-mouse-motion]
        :input/mode)
      (k/derivev
        [:hint.type/integer :hint.type/string]
        :window/hint)
      (k/derivev
        [:hint.window/resizable   :hint.context/gl.forward-compat
         :hint.window/visible     :hint.context/version.maj
         :hint.context/gl.profile :hint.context/version.min]
        :hint.type/integer)))
