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
(def register
  (atom {}))

(defn get-vao
  [layout]
  (get-in @register [:vao layout]))

(defn add-vao!
  [vao]
  (let [layout (:vao/layout vao)]
    (swap! register assoc-in [:vao layout] vao)))

(defn get-prog [k]
  (get-in @register [:program k]))

(defn add-program!
  [prog]
  (let [k (:name prog)]
    (swap! register assoc-in [:program k] prog)))


