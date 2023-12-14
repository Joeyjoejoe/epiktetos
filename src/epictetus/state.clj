(ns epictetus.state)

;; User managed state
(def db (atom {}))

;; system is the result of processing a config file through
;; integrant (https://github.com/weavejester/integrant).
;;
;; Have a look to the engine default config at :
;; https://github.com/Joeyjoejoe/epictetus/blob/master/resources/engine-default.edn.
;;
;; These are the core systems used in the engine :
;;
;;   :glfw/window  OpenGL context (the window).
;;   :gl/shaders   Shaders creation.
;;
(def system (atom {}))

;; Internal state containing rendered entities
;; It can only be modified through :render effects
(def rendering (atom {}))
