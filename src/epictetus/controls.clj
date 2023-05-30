(ns epictetus.controls
  (:require [epictetus.state :as state]
            [epictetus.event :as event])
  (:import (org.lwjgl.glfw GLFW
                           GLFWKeyCallback
                           GLFWCursorPosCallback
                           GLFWMouseButtonCallback)))

(defonce keyboard-events {GLFW/GLFW_PRESS   "press"
                          GLFW/GLFW_RELEASE "release"
                          GLFW/GLFW_REPEAT  "repeat"})

;; https://www.glfw.org/docs/3.3/group__mods.html#ga6ed94871c3208eefd85713fa929d45aa
(defonce keyboard-mods {GLFW/GLFW_MOD_SHIFT     :shift
                        GLFW/GLFW_MOD_CONTROL   :control
                        GLFW/GLFW_MOD_ALT       :alt
                        GLFW/GLFW_MOD_SUPER     :super
                        GLFW/GLFW_MOD_CAPS_LOCK :caps-lock
                        GLFW/GLFW_MOD_NUM_LOCK  :num-lock})

;; https://www.glfw.org/docs/3.3/group__keys.html
(defonce keyboard-keys {GLFW/GLFW_KEY_UNKNOWN       :unknown
                        GLFW/GLFW_KEY_SPACE         :space
                        GLFW/GLFW_KEY_APOSTROPHE    :apostrophe
                        GLFW/GLFW_KEY_COMMA         :comma
                        GLFW/GLFW_KEY_MINUS         :-
                        GLFW/GLFW_KEY_PERIOD        :.
                        GLFW/GLFW_KEY_SLASH         :slash
                        GLFW/GLFW_KEY_0             :0
                        GLFW/GLFW_KEY_1             :1
                        GLFW/GLFW_KEY_2             :2
                        GLFW/GLFW_KEY_3             :3
                        GLFW/GLFW_KEY_4             :4
                        GLFW/GLFW_KEY_5             :5
                        GLFW/GLFW_KEY_6             :6
                        GLFW/GLFW_KEY_7             :7
                        GLFW/GLFW_KEY_8             :8
                        GLFW/GLFW_KEY_9             :9
                        GLFW/GLFW_KEY_SEMICOLON     :semicolon
                        GLFW/GLFW_KEY_EQUAL         :=
                        GLFW/GLFW_KEY_A             :a
                        GLFW/GLFW_KEY_B             :b
                        GLFW/GLFW_KEY_C             :c
                        GLFW/GLFW_KEY_D             :d
                        GLFW/GLFW_KEY_E             :e
                        GLFW/GLFW_KEY_F             :f
                        GLFW/GLFW_KEY_G             :g
                        GLFW/GLFW_KEY_H             :h
                        GLFW/GLFW_KEY_I             :i
                        GLFW/GLFW_KEY_J             :j
                        GLFW/GLFW_KEY_K             :k
                        GLFW/GLFW_KEY_L             :l
                        GLFW/GLFW_KEY_M             :m
                        GLFW/GLFW_KEY_N             :n
                        GLFW/GLFW_KEY_O             :o
                        GLFW/GLFW_KEY_P             :p
                        GLFW/GLFW_KEY_Q             :q
                        GLFW/GLFW_KEY_R             :r
                        GLFW/GLFW_KEY_S             :s
                        GLFW/GLFW_KEY_T             :t
                        GLFW/GLFW_KEY_U             :u
                        GLFW/GLFW_KEY_V             :v
                        GLFW/GLFW_KEY_W             :w
                        GLFW/GLFW_KEY_X             :x
                        GLFW/GLFW_KEY_Y             :y
                        GLFW/GLFW_KEY_Z             :z
                        GLFW/GLFW_KEY_LEFT_BRACKET  :left-bracket
                        GLFW/GLFW_KEY_BACKSLASH     :backslash
                        GLFW/GLFW_KEY_RIGHT_BRACKET :right-bracket
                        GLFW/GLFW_KEY_GRAVE_ACCENT  :grave-accent
                        GLFW/GLFW_KEY_WORLD_1       :world-1
                        GLFW/GLFW_KEY_WORLD_2       :world-2
                        GLFW/GLFW_KEY_ESCAPE        :escape
                        GLFW/GLFW_KEY_ENTER         :enter
                        GLFW/GLFW_KEY_TAB           :tab
                        GLFW/GLFW_KEY_BACKSPACE     :backspace
                        GLFW/GLFW_KEY_INSERT        :insert
                        GLFW/GLFW_KEY_DELETE        :delete
                        GLFW/GLFW_KEY_RIGHT         :right
                        GLFW/GLFW_KEY_LEFT          :left
                        GLFW/GLFW_KEY_DOWN          :down
                        GLFW/GLFW_KEY_UP            :up
                        GLFW/GLFW_KEY_PAGE_UP       :page-up
                        GLFW/GLFW_KEY_PAGE_DOWN     :page-down
                        GLFW/GLFW_KEY_HOME          :home
                        GLFW/GLFW_KEY_END           :end
                        GLFW/GLFW_KEY_CAPS_LOCK     :caps-lock
                        GLFW/GLFW_KEY_SCROLL_LOCK   :scroll-lock
                        GLFW/GLFW_KEY_NUM_LOCK      :num-lock
                        GLFW/GLFW_KEY_PRINT_SCREEN  :print-screen
                        GLFW/GLFW_KEY_PAUSE         :pause
                        GLFW/GLFW_KEY_F1            :f1
                        GLFW/GLFW_KEY_F2            :f2
                        GLFW/GLFW_KEY_F3            :f3
                        GLFW/GLFW_KEY_F4            :f4
                        GLFW/GLFW_KEY_F5            :f5
                        GLFW/GLFW_KEY_F6            :f6
                        GLFW/GLFW_KEY_F7            :f7
                        GLFW/GLFW_KEY_F8            :f8
                        GLFW/GLFW_KEY_F9            :f9
                        GLFW/GLFW_KEY_F10           :f10
                        GLFW/GLFW_KEY_F11           :f11
                        GLFW/GLFW_KEY_F12           :f12
                        GLFW/GLFW_KEY_F13           :f13
                        GLFW/GLFW_KEY_F14           :f14
                        GLFW/GLFW_KEY_F15           :f15
                        GLFW/GLFW_KEY_F16           :f16
                        GLFW/GLFW_KEY_F17           :f17
                        GLFW/GLFW_KEY_F18           :f18
                        GLFW/GLFW_KEY_F19           :f19
                        GLFW/GLFW_KEY_F20           :f20
                        GLFW/GLFW_KEY_F21           :f21
                        GLFW/GLFW_KEY_F22           :f22
                        GLFW/GLFW_KEY_F23           :f23
                        GLFW/GLFW_KEY_F24           :f24
                        GLFW/GLFW_KEY_F25           :f25
                        GLFW/GLFW_KEY_KP_0          :keypad-0
                        GLFW/GLFW_KEY_KP_1          :keypad-1
                        GLFW/GLFW_KEY_KP_2          :keypad-2
                        GLFW/GLFW_KEY_KP_3          :keypad-3
                        GLFW/GLFW_KEY_KP_4          :keypad-4
                        GLFW/GLFW_KEY_KP_5          :keypad-5
                        GLFW/GLFW_KEY_KP_6          :keypad-6
                        GLFW/GLFW_KEY_KP_7          :keypad-7
                        GLFW/GLFW_KEY_KP_8          :keypad-8
                        GLFW/GLFW_KEY_KP_9          :keypad-9
                        GLFW/GLFW_KEY_KP_DECIMAL    :keypad-decimal
                        GLFW/GLFW_KEY_KP_DIVIDE     :keypad-divide
                        GLFW/GLFW_KEY_KP_MULTIPLY   :keypad-multiply
                        GLFW/GLFW_KEY_KP_SUBTRACT   :keypad-subtract
                        GLFW/GLFW_KEY_KP_ADD        :keypad-add
                        GLFW/GLFW_KEY_KP_ENTER      :keypad-enter
                        GLFW/GLFW_KEY_KP_EQUAL      :keypad-equal
                        GLFW/GLFW_KEY_LEFT_SHIFT    :left-shift
                        GLFW/GLFW_KEY_LEFT_CONTROL  :left-control
                        GLFW/GLFW_KEY_LEFT_ALT      :left-alt
                        GLFW/GLFW_KEY_LEFT_SUPER    :left-super
                        GLFW/GLFW_KEY_RIGHT_SHIFT   :right-shift
                        GLFW/GLFW_KEY_RIGHT_CONTROL :right-control
                        GLFW/GLFW_KEY_RIGHT_ALT     :right-alt
                        GLFW/GLFW_KEY_RIGHT_SUPER   :right-super
                        GLFW/GLFW_KEY_MENU          :menu})

(defonce mouse-buttons {GLFW/GLFW_MOUSE_BUTTON_1 :btn-left
                        GLFW/GLFW_MOUSE_BUTTON_2 :btn-right
                        GLFW/GLFW_MOUSE_BUTTON_3 :btn-middle
                        GLFW/GLFW_MOUSE_BUTTON_4 :btn-3
                        GLFW/GLFW_MOUSE_BUTTON_5 :btn-4
                        GLFW/GLFW_MOUSE_BUTTON_6 :btn-5
                        GLFW/GLFW_MOUSE_BUTTON_7 :btn-6
                        GLFW/GLFW_MOUSE_BUTTON_8 :btn-7})

(def default-mappings
  {[:btn-left nil]  {:press [:mouse/left-click]}
   [:space nil] {:press [:game/quit "Confirm message"]}})

;; Map input combinations to events in this form
;; keys->status->event
(def mappings (atom default-mappings))

(defn set-mapping
  [[k mode status] event]
  (swap! mappings assoc-in [[k mode] (or status :press)] event))

(defn reset-mappings []
  (reset! mappings default-mappings))

;; proxy object signature: https://www.glfw.org/docs/3.3/group__input.html#ga5bd751b27b90f865d2ea613533f0453c
;; scancode Platform-specific key code and given as an alternative to k
(def keyboard-callback
  (proxy [GLFWKeyCallback] []
    (invoke [window k scancode action mods]
      (let [key-status (keyword (get keyboard-events action))
            key-name   (get keyboard-keys k)
            key-mod    (get keyboard-mods mods)]

        (if-let [event (get-in @mappings [[key-name key-mod] key-status])]
          (event/dispatch event))))))

;; https://www.glfw.org/docs/3.3/group__input.html#gad6fae41b3ac2e4209aaa87b596c57f68
(def mouse-callback
  (proxy [GLFWCursorPosCallback] []
    (invoke [window x y]
      (event/dispatch [:mouse/position [x y]]))))

;; https://www.glfw.org/docs/3.3/group__input.html#ga0184dcb59f6d85d735503dcaae809727
(def mouse-button-callback
  (proxy [GLFWMouseButtonCallback] []
    (invoke [window button action mods]
      (let [btn-status (keyword (get keyboard-events action))
            btn-name   (get mouse-buttons button)
            key-mod    (get keyboard-mods mods)]

        (if-let [event (get-in @mappings [[btn-name key-mod] btn-status])]
          (event/dispatch event))))))

(defn set-callbacks [window]
  (GLFW/glfwSetCursorPosCallback window mouse-callback)
  (GLFW/glfwSetMouseButtonCallback window mouse-button-callback)
  (GLFW/glfwSetKeyCallback window keyboard-callback))


