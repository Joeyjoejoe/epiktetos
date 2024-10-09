(ns epiktetos.registrar)

;; User must be able to manage shader programs at runtime.
;;   - compile a new program
;;   - recompile an existing program
;;   - remove programs
;;
;; Use cases :
;;   - Hot reload (re-compile programs)
;;   - (un)Loading a full scene

;; We use program names in entities :
;; {:program :my-prog ...}

;; Hot-reload will output a path to shader file
;; should reload all dependent programs
(def state
  (atom #::{:shaders {"shaders/default.vert" #{:id :attribs :structs :uniforms}
                      "shaders/default.frag" #{:id :attribs :structs :uniforms}}
            :vao {}
            :programs {}

            {:name :perspective,
             :program/id 3,
             :shader/ids [1 2],
             :shader/path ["shaders/default.vert" "shaders/default.frag"],
             :primitive 4,
             :layout [:vec3f/coordinates :vec3f/color :vec2f/texture],
             :uniforms
             <-([:t :float -1]
                [:speed :float -1]
                [:model :mat4 0]
                [:view :mat4 3]
                [:projection :mat4 1]
                [:t :float -1]
                [:speed :float -1]
                [:textIndex0 :sampler2D 2])-<}

            }))

(defn get-vao
  ""
  [layout]
  layout)

(defn get-prog
  ""
  [k]
  k)

(:shaders (get-prog :perspective))
(get-in reg/state [:perspective :shaders])

