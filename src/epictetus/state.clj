;; TODO Re-think state structure.
;;      - What are the pros of a global index for
;;        programs, vao, vbo etc...
(ns epictetus.state)

;; User managed state map
;; keys in :core namespace denote data automaticaly merged
;; on each loop iteration. They provides important about
;; the state of the engine:

;; - :core/loop contains informations about the current
;;              loop iteration.
;;
;; {:iter <iteration-number> :time {:curr  <current-time>
;;                                  :prev  <previous-time>
;;                                  :delta <delta-time>}}
;;
;; user/cognitive-load
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
;;
;; vao/layout->program/name->entity/name->entity/data
;;
;; example : {[:vec3f/coordinates :vec3f/color]
;;            {:default {:hero
;;                       {{:program :default,
;;                         :position [0.0 0.0 0.0],
;;                         :vao 1
;;                         :vbo 1
;;                         :assets
;;                         {:indices [],
;;                          :vertices ...}}}}}}
;;
(def rendering (atom {}))

;; internal state of entities data by id
(def entities (atom {}))
