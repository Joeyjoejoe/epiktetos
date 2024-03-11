(ns epictetus.entity
  (:require [epictetus.effect :refer [reg-fx]]
            [epictetus.state  :as state]))

(defn do-mut
  [entity mut]
  (let [[k value] mut]
    (if (vector? k)
      (assoc-in entity k value)
      (assoc    entity k value))))

(reg-fx :entity/set
        (fn mutate-entity! [[id muts]]
          ;; m is a map of entity ids and a map of data to set
          ;; (assoc fx :entity/set [:hero {:motion [:walk :right]}])
          (let [entity (get @state/entities id)]
            (->> muts
                 (reduce do-mut entity)
                 (swap! state/entities assoc id)))))
