(ns epictetus.effect
  (:require [epictetus.event :as event]
            [epictetus.state :as state]
            [epictetus.vertices :as vertices]
            [epictetus.interceptors :refer [->interceptor]]
            [clojure.pprint :refer [pprint]]))

(defn reg-fx
  "A fx is a function that takes the coeffects map and
  an optional parameter, and return a modified version
  of the coeffects map"
  [id fx-fn]
  (event/register :effect id fx-fn))

(def do-fx
  (->interceptor
    :id    :effects
    :after (fn do-all-fx
             [context]
             (let [effects               (:effects context)
                   effects-without-db (dissoc effects :db)]
               ;; :db effect is guaranteed to be handled before all other effects.
               (when-let [new-db (:db effects)]
                 ((event/get-handler :effect :db) new-db))

               (doseq [[effect-key effect-value] effects-without-db]
                 (if-let [effect-fn (event/get-handler :effect effect-key)]
                   (effect-fn effect-value)
                   (println "no handler registered for effect:" effect-key ". Ignoring.")))))))

;; CORE EFFECTS

(reg-fx :db
        (fn update-db! [new-db]
          (reset! state/db new-db)))

(reg-fx :dispatch
        (fn dispatch-event! [events]
          (doseq [e events]
            (event/dispatch e))))

(reg-fx :render
        (fn render!
          ([entities]
           (doseq [[id entity] entities]
             (render! id entity)))

          ([id {:as entity :keys [program]}]
           ;; - TODO Implement VAO selection based on program. Vao should be
           ;; generated from program layout, which is determined by shaders
           ;; attributes location (parsed from source shader files)
           ;;
           ;; - TODO Implement VBO duplication prevention. We must detect
           ;; entities with same assets and render them using the same VBO.
           ;; Which should be rendered using instance rendering ?
           (let [{layout :layout} (get-in @state/system [:shader/programs :program program])
                 vao              (get-in @state/system [:shader/programs :vao layout])]
             (->> entity
                  (vertices/gpu-load! vao)
                  (swap! state/rendering assoc-in [layout program id]))))))


(reg-fx :delete
       (fn delete-entity!
         [entity-keys]
         ;; TODO update path must be obtainable from entity-key
         ;;  - Refactor state/rendering to vaoID->programID->entityIDS
         ;;  - Add a global registery for vaos, programs and entities data (integrant system)
         (apply swap! state/rendering update-in [:vao/static :default] dissoc entity-keys)))

(reg-fx :delete-all
       (fn delete-all [_]
         (reset! state/rendering {})))
