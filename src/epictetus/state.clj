(ns epictetus.state)

;; User managed state
(def db (atom {}))

;; Once epictetus config is processed, it will
;; become a system.
;; It servces as a storage for user made opengl resources
;; like shaders, programs, window.
;; Since epictetus config use integrant, users can add their
;; own systems.
(def system (atom {}))

;; Internal state containing rendered entities
;; It can only be modified through :render effects
(def rendering (atom {}))

;; An entity map (data not loaded in gpu)
;; [entity/id {:data []
;;             :program :foo
;;             :position [...]}]

;; An entity map with vertices loaded in gpu (ready to render)
;; [entity/id {:data []
;;             :vbo-id 0
;;             :program/id 0
;;             :program :foo
;;             :position [...]}]

