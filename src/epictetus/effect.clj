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
           (let [vao (get-in @state/system [:gl/vaos :vao/static])]
             (->> entity
                  (vertices/gpu-load! vao)
                  (swap! state/rendering assoc-in [:vao/static program id]))))))

