(ns epiktetos.entity
  (:require [epiktetos.coeffect :refer [cofx-error]]
            [epiktetos.state  :as state]
            [epiktetos.lang.opengl :as opengl]
            [epiktetos.registrar :as register]
            [epiktetos.texture :as textures]
            [epiktetos.vao.buffer :as vao-buffer]))


(defonce GET-ALL-IDS #{:all :* "*"})

(defn get-entity
  [cofx id]
   (if-let [entity (state/entity id)]
     (assoc cofx id entity)
     (if (get GET-ALL-IDS id)
       (assoc cofx :all (mapv val @state/entities))
       (cofx-error cofx :entity/get id "Entity not found"))))

(defn delete!
  "Remove an entity from rendering entities"
  ([id]
  (if-let [entity (state/entity id)]
    (let [{:keys [program]} entity
          {buffers :buffers}  (register/get-prog program)]
      (swap! state/rendering update-in [buffers program] dissoc id)
      (swap! state/entities dissoc id)))))

(defn update!
  "Update an entity map by providing a new one"
  [entity]
  (let [id (:id entity)]
    (swap! state/entities assoc id entity)))

(defn batch-update!
  "Update an entity map by providing a new one"
  [entities]
  (->> entities
      (merge @state/entities)
      (reset! state/entities)))

(defn reset-all!
  "Update an entity map by providing a new one"
  [entities]
    (reset! state/entities entities))

(defn delete-all!
  "Remove all entities."
  [_]
  (reset! state/entities {})
  (reset! state/rendering {}))

(defn render!
  "Register an new entity in state/entities and load assets for rendering
  on next loop iteration."
  ([entity]
   ;; TODO Assets cache (VBO duplication prevention & instance rendering)
   ;; TODO Handle nil id or program
   (let [{:keys [id program primitive]
          :or {primitive :triangles}}
         entity

         {buffers :buffers}     (register/get-prog program)
         vao                  (register/get-vao buffers)
         loaded-entity (-> entity
                           (vao-buffer/gpu-load! vao)
                           (textures/load-entity)
                           (assoc :primitive (get opengl/DRAW-PRIMITIVES primitive)))]

     (swap! state/entities assoc id loaded-entity)
     (swap! state/rendering assoc-in [buffers program id] true))))

(defn batch-render!
  "Render a list of entities"
  [entities]
  (doseq [entity entities]
    (render! entity)))
