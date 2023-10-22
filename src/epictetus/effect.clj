(ns epictetus.effect
  (:require [epictetus.event :as event]
            [epictetus.scene :as scene]
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
                   effects-without-scene (dissoc effects :scene)]
               ;; :scene effect is guaranteed to be handled before all other effects.
               (when-let [new-scene (:scene effects)]
                 ((event/get-handler :effect :scene) new-scene))

               (doseq [[effect-key effect-value] effects-without-scene]
                 (if-let [effect-fn (event/get-handler :effect effect-key)]
                   (effect-fn effect-value)
                   (println "no handler registered for effect:" effect-key ". Ignoring.")))))))

;; CORE EFFECTS

(reg-fx :scene
        (fn update-scene! [new-scene]
          (reset! scene/state new-scene)))

(reg-fx :dispatch
        (fn dispatch-event! [events]
          (doseq [e events]
            (event/dispatch e))))

(reg-fx :render
        (fn render!
          ([id entity]
           (println "Render model " id)
           (pprint entity))
          ([entities]
           (doseq [[id entity] entities]
             (render! id entity)))))
