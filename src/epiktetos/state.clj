(ns epiktetos.state)

;; User managed state map
(def db (atom {}))

;; system is the result of processing a config file through
;; integrant (https://github.com/weavejester/integrant).
;;
;; Have a look to the engine default config at :
;; resources/epiktetos/default-config.edn
;;
;; These are the core systems used in the engine :
;;
;;   :glfw/window  OpenGL context (the window).
;;   :gl/engine   Shaders creation.
;;
(def system (atom {}))

(defn window
  []
  (get @system :glfw/window))
