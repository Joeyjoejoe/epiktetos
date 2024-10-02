(ns epiktetos.entity
  (:require [epiktetos.coeffect :refer [reg-cofx cofx-error]]
            [epiktetos.state  :as state]
            [epiktetos.texture :as textures]
            [epiktetos.vertices :as vertices]))


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
          {layout :layout}  (state/program program)]
      (swap! state/rendering update-in [layout program] dissoc id)
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
   (let [{:keys [id program]} entity
         {layout :layout}     (state/program program)
         vao                  (state/vao layout)]

     (->> entity
          (vertices/gpu-load! vao)
          (textures/load-entity)
          (swap! state/entities assoc id))

     (swap! state/rendering assoc-in [layout program id] true))))

(reg-cofx :entity/get get-entity)
(reg-cofx :entity/get-all
          (fn get-all-entities
            [coeffects]
            (assoc coeffects :entity @state/entities)))









