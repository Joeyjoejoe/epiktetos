(ns epictetus.entity
  (:require [epictetus.effect :refer [reg-fx]]
            [epictetus.coeffect :refer [reg-cofx]]
            [epictetus.state  :as state]))

(defn do-mut
  [entity mut]
  (let [[k value] mut]
    (if (vector? k)
      (assoc-in entity k value)
      (assoc    entity k value))))

(reg-fx :entity/set
        (fn mutate-entity! [[id muts]]
          ;; (assoc fx :entity/set [:hero {:motion [:walk :right]}])
          (let [entity (get @state/entities id)]
            (->> muts
                 (reduce do-mut entity)
                 (swap! state/entities assoc id)))))

(reg-cofx :entity/get
          (fn get-entity
            [coeffects & ids]
                (assoc coeffects :entity
                                 (select-keys @state/entities ids))))

