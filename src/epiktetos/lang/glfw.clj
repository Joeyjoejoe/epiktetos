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
   :cursor/captured       GLFW/GLFW_CURSOR_CAPTURED

   ;; Boolean modes
   :mode/sticky-mouse-buttons  GLFW/GLFW_STICKY_MOUSE_BUTTONS
   :mode/sticky-keys           GLFW/GLFW_STICKY_KEYS
   :mode/lock-key              GLFW/GLFW_LOCK_KEY_MODS
   :mode/raw-mouse-motion      GLFW/GLFW_RAW_MOUSE_MOTION

   ;; Window creation hints
   ;; https://www.glfw.org/docs/3.3/window_guide.html#window_hints
   :hint.type/integer              (fn [hint value]
                                     (GLFW/glfwWindowHint (int hint) (int value)))
   :hint.type/string               (fn [hint value]
                                     (GLFW/glfwWindowHintString (int hint) (str value)))
   :hint.window/samples            GLFW/GLFW_SAMPLES
   :hint.window/srgb-capable       GLFW/GLFW_SRGB_CAPABLE
   :hint.context/version.maj       GLFW/GLFW_CONTEXT_VERSION_MAJOR
   :hint.context/version.min       GLFW/GLFW_CONTEXT_VERSION_MINOR
   :hint.context/gl.profile        GLFW/GLFW_OPENGL_PROFILE
   :hint.context/gl.forward-compat GLFW/GLFW_OPENGL_FORWARD_COMPAT
   :hint.context/gl.debug          GLFW/GLFW_OPENGL_DEBUG_CONTEXT

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
        [:hint.context/gl.forward-compat :hint.context/version.maj
         :hint.context/gl.profile        :hint.context/version.min
         :hint.window/samples            :hint.context/gl.debug
         :hint.window/srgb-capable]
        :hint.type/integer)))

(def RUNTIME-SETTINGS
  "The :glfw/window settings changeable after window creation, accepted
  by the configuration and by the window effect alike, with the check
  of their value and its description for error messages."
  {:title                     {:valid? string?
                               :expected "a string"}
   :width                     {:valid? pos-int?
                               :expected "a positive integer, in points"}
   :height                    {:valid? pos-int?
                               :expected "a positive integer, in points"}
   :display                   {:valid? #{:windowed :fullscreen :fullscreen/windowed}
                               :expected ":windowed, :fullscreen or :fullscreen/windowed"}
   :mode/cursor               {:valid? #{:cursor/normal :cursor/hidden
                                         :cursor/disabled :cursor/captured}
                               :expected ":cursor/normal, :cursor/hidden, :cursor/disabled or :cursor/captured"}
   :mode/raw-mouse-motion     {:valid? boolean?
                               :expected "a boolean"}
   :mode/sticky-keys          {:valid? boolean?
                               :expected "a boolean"}
   :mode/sticky-mouse-buttons {:valid? boolean?
                               :expected "a boolean"}
   :mode/lock-key             {:valid? boolean?
                               :expected "a boolean"}
   :window/resizable          {:valid? boolean?
                               :expected "a boolean"}
   :window/visible            {:valid? boolean?
                               :expected "a boolean"}})
