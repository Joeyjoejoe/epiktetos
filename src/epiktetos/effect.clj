(ns epiktetos.effect
  (:require [epiktetos.event :as event]
            [epiktetos.interceptors :refer [->interceptor]]))

(defn register
  [id fx-fn]
  (event/register :effects id fx-fn))

(def CORE-FX-ORDER
  "Execution order of the engine's own effects, after :db and before
  user effects: resources a same-event effect may rely on (steps,
  programs, inputs, textures) are set up before entities are rendered,
  deletions come last."
  [::dispatch ::reg-steps ::reg-p ::reg-input ::reg-texture ::render ::delete])

(defn ordered-effects
  "Order the entries of an effects map for execution: :db first, so
  every other effect of the event sees the new state, then the engine
  effects in CORE-FX-ORDER, then user effects, whose relative order is
  unspecified.
  effects - map, effect id -> effect value
  Returns a vector of [id value] entries"
  [effects]
  (let [core?    (set CORE-FX-ORDER)
        user-ids (remove #(or (= :db %) (core? %)) (keys effects))
        ordered  (concat (when (contains? effects :db) [:db])
                         (filter #(contains? effects %) CORE-FX-ORDER)
                         user-ids)]
    (mapv (fn [id] [id (get effects id)]) ordered)))

(def do-fx
  "The engine interceptor executing on its :after pass the effects
  described by the event handler, in ordered-effects order. An effect
  without registered handler logs a warning and is skipped. A throwing
  effect aborts the walk: the error is rethrown tagged with the
  effects bookkeeping — :fx/executed, :fx/failed, :fx/remaining — and
  captured as data by the chain (see epiktetos.interceptors)."
  (->interceptor
    :id    :effects
    :after (fn do-all-fx
             [context]
             (loop [remaining (ordered-effects (:effects context))
                    executed  []]
               (if-let [[id value] (first remaining)]
                 (if-let [effect-fn (event/get-handler :effects id)]
                   (do (try
                         (effect-fn value)
                         (catch Throwable t
                           (throw (ex-info "Effect error"
                                           {:fx/executed  executed
                                            :fx/failed    [id value]
                                            :fx/remaining (mapv first (rest remaining))}
                                           t))))
                       (recur (rest remaining) (conj executed id)))
                   (do (println "no handler registered for effect:" id ". Ignoring.")
                       (recur (rest remaining) executed)))
                 context)))))
