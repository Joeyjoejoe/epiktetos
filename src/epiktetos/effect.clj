(ns epiktetos.effect
  (:require [epiktetos.event :as event]
            [epiktetos.state :as state]
            [epiktetos.texture :as textures]
            [epiktetos.vertices :as vertices]
            [epiktetos.interceptors :refer [->interceptor]]
            [clojure.pprint :refer [pprint]])
  (:import (org.lwjgl.glfw GLFW)))

(defn register
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


