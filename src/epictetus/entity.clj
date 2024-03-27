(ns epictetus.entity
  (:require [epictetus.effect :refer [reg-fx]]
            [epictetus.coeffect :refer [reg-cofx cofx-error]]
            [epictetus.state  :as state]
            [epictetus.texture :as textures]
            [epictetus.vertices :as vertices]))

(defn delete-all!
  "Remove all entities."
  []
  (reset! state/entities {}))

(reg-cofx :entity/get-all
          (fn get-all-entities
            [coeffects]
            (assoc coeffects :entity @state/entities)))

(defonce get-all-ids #{:all :* "*"})

(defn get-entity
  [cofx id]
   (if-let [entity (get @state/entities id)]
     (assoc cofx id entity)
     (if (get get-all-ids id)
       (assoc cofx :all (mapv val @state/entities))
       (cofx-error cofx :entity/get id "Entity not found"))))

(defn delete-entity!
  "Remove an entity from rendering entities"
  [id]
  (swap! state/entities dissoc id))

(defn update-entity!
  "Update an entity map by providing a new one"
  [entity]
  (let [id (:id entity)]
    (swap! state/entities assoc id entity)))

(defn reset-all!
  "Update an entity map by providing a new one"
  [entities]
    (reset! state/entities entities))

(defn render-entity!
  "Register an new entity in state/entities and load assets for rendering
   on next loop iteration."
  ([entity]
   ;; TODO Assets cache (VBO duplication prevention & instance rendering)
   (let [{:keys [id program]} entity
         {layout :layout}     (get-in @state/system [:gl/engine :program program])
         vao                  (get-in @state/system [:gl/engine :vao layout])]

     (->> entity
          (vertices/gpu-load! vao)
          (textures/load-entity)
          (swap! state/entities assoc id))

     (swap! state/rendering assoc-in [layout program id] true))))

(reg-fx :entity/render render-entity!)
(reg-fx :entity/update update-entity!)
(reg-fx :entity/delete delete-entity!)
(reg-fx :entity/delete-all delete-all!)
(reg-fx :entity/reset-all reset-all!)


(reg-cofx :entity/get get-entity)








