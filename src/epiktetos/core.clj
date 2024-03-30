(ns epiktetos.core
  (:require [integrant.core :as ig]
            [clojure.java.io :as io]
            [epiktetos.state :as state]
            [epiktetos.coeffect :as cofx]
            [epiktetos.effect :as fx]
            [epiktetos.loop :as game-loop]
            [epiktetos.event :as event]
            [epiktetos.uniform :as u]
            [epiktetos.entity :as entity]
            [epiktetos.interceptors :as interc :refer [->interceptor]]
            [epiktetos.window]
            [epiktetos.program])
  (:import (org.lwjgl.glfw GLFW)))

(def db state/db)

(defn start
  ([]
   (start "engine-default.edn"))

  ([conf-path]
   (let [system (-> conf-path
                    io/resource
                    slurp
                    ig/read-string
                    ig/prep
                    ig/init)]
     (start conf-path system)))

  ([_ sys]

     (GLFW/glfwSetWindowShouldClose (:glfw/window sys) false)
     (reset! state/system sys)
     (game-loop/start @state/system)))

(defn reg-event
  "Set the handler to an event id, with the option to add additional coeffects.

  Handler are pure functions that takes two arguments:
  - a map of coeffects containing input data for the handler function.
  - a map of effects that the handler function must return (modified or not).

  Coeffects and effects can be registered with reg-cofx and reg-fx functions"
  ([id handler-fn]
   (reg-event id [] handler-fn))
  ([id coeffects handler-fn]
   (let [handler (->interceptor
                   {:id     :event-fn
                    :before (fn handler [context]
                              (let [{:keys [db] :as cofx} (:coeffects context)
                                    fx {:db db}]

                                (->> (handler-fn cofx fx)
                                     (assoc context :effects))))})
         interceptors [fx/do-fx cofx/inject-db cofx/inject-system coeffects cofx/error-logger handler]
         chain        (->> interceptors flatten (remove nil?))]
     (event/register :event id chain))))

(defn reg-u
  "Register a uniform handler function ran at rendering time and returning
  uniform's value.

  u-name is a uniform name keyword (varname in shader source). In order to
         specify a handler for a specific uniform in a specific program,
         you can provide a vector of 2 keywords:
         [:program-name :uniform-name]

  handler is a pure function

  Examples:

  ;; Register a global uniform whose value will be computed only once per Loop
  ;; iteration.
  ;; handler function takes one parameter: state/db

  (reg-u :foo (fn [db] ...))

  ;; Register a program uniform whose value is computed once at progam start.
  ;; handler function takes 2 parameters: state/db and a a map of entities
  ;; being rendered with uniform's program.

  (reg-u [:program-name :foo] (fn [db entities] ...))"
  [u-name handler]
  (if (= clojure.lang.Keyword (type u-name))
    (u/register-global-uniform u-name handler)
    (u/register-uniform u-name handler)))

(defn reg-eu
  "Same as reg-u, but register a uniform whose value will be
  computed for each entity rendered with uniform's program.
  An entity uniform handler function take 2 parameters:
  "
  [upath f]
    (u/register-entity-uniform upath f))

;; CORE EVENTS

(reg-event
  [:press :escape]
  (fn quit-flag [_ fx]
    (assoc fx :loop/pause true)))

(reg-event ::event/loop.iter
  (fn loop-infos [cofx fx]
    (let [{[_ loop-iter] :event} cofx]
      (assoc-in fx [:db :core/loop] loop-iter))))

