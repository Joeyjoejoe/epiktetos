(ns epiktetos.core
  (:require [clojure.pprint :refer [pprint]]
            [epiktetos.state :as state]
            [epiktetos.coeffect :as cofx]
            [epiktetos.effect :as fx]
            [epiktetos.startup :as startup]
            [epiktetos.registrar :as register]
            [epiktetos.event :as event]
            [epiktetos.uniform :as u]
            [epiktetos.entity :as entity]
            [epiktetos.interceptors :as interc :refer [->interceptor]]
            [epiktetos.window]
            [epiktetos.program :as prog])
  (:import (org.lwjgl.glfw GLFW)))

(def db state/db)

(defn run
  "Run the engine.
   - startup-events is a vector of events to dispatch
   - config-path is a path to an edn configuration file"
  ([startup-events]
   (run startup/DEFAULT_CONFIG_PATH startup-events))
  ([config-path startup-events]
  (let [systems (startup/init-systems config-path)
        events (if (-> startup-events first keyword?)
                 [startup-events]
                 startup-events)]
    (-> systems
        (assoc-in [:gl/engine :config-path] config-path)
        (assoc-in [:gl/engine :startup-events] startup-events)
        (startup/start-engine! events)))))

(defn reg-cofx
  "A cofx is a function that takes a coeffects map and
   an optional parameter, and return a modified version
   of the coeffects map"
  [id cofx-fn]
  (cofx/register id cofx-fn))

(defn inject-cofx
  "Add a cofx to an event registration"
  ([id]
  (cofx/inject id))
  ([id value]
   (cofx/inject id value)))

(reg-cofx :inject-system
          (fn [coeffects]
            (assoc coeffects :system @state/system)))

(reg-cofx :inject-db
          (fn [coeffects]
            (assoc coeffects :db @state/db)))

(reg-cofx :error-logger
          (fn [coeffects]
            (when-let [errors (:errors coeffects)]
              (doseq [err errors] (pprint err)))
            coeffects))

(reg-cofx :entity/get entity/get-entity)
(reg-cofx :entity/get-all
          (fn get-all-entities
            [coeffects]
            (assoc coeffects :entity @state/entities)))

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
         interceptors [fx/do-fx
                       (inject-cofx :inject-db)
                       (inject-cofx :inject-system)
                       coeffects
                       (inject-cofx :error-logger)
                       handler]
         chain        (->> interceptors flatten (remove nil?))]
     (event/register :event id chain))))

(reg-event ::event/loop.iter
  (fn loop-infos [cofx fx]
    (let [{[_ loop-iter] :event} cofx]
      (-> fx
          (assoc-in [:db :core/loop] loop-iter)))))

(defn reg-fx
  "An effect, aka fx, is a function that takes a coeffects map and
   an optional parameter, and return a modified version
   of the coeffects map"
  [id fx-fn]
  (fx/register id fx-fn))

(reg-fx :db
        (fn update-db! [new-db]
          (reset! state/db new-db)))

(reg-fx :event/dispatch
        (fn dispatch-event! [events]
          (doseq [e events]
            (event/dispatch e))))

(reg-fx :entity/render       entity/render!)
(reg-fx :entity/update       entity/update!)
(reg-fx :entity/batch-update entity/batch-update!) ;
(reg-fx :entity/delete       entity/delete!)
(reg-fx :entity/delete-all   entity/delete-all!)
(reg-fx :entity/reset-all    entity/reset-all!)


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
  ([u-name handler]
   (event/dispatch [::event/reg-u [u-name handler]]))
  ([fx u-name handler]
   (assoc-in fx [::fx/reg-u u-name] handler)))

(reg-event ::event/reg-u
           (fn [cofx fx]
             (let [[u-name handler] (get-in cofx [:event 1])]
               (reg-u fx u-name handler))))

(reg-fx ::fx/reg-u
        (fn register-uniform-fx [u-map]
          (doseq [[u-name handler] u-map]
            (if (= clojure.lang.Keyword (type u-name))
              (u/register-global-uniform u-name handler)
              (u/register-uniform u-name handler)))))

(defn reg-eu
  "Same as reg-u, but register a uniform whose value will be
  computed for each entity rendered with uniform's program.
  An entity uniform handler function take 2 parameters:
  "
  ([u-name handler]
   (event/dispatch [::event/reg-eu [u-name handler]]))
  ([fx u-name handler]
   (assoc-in fx [::fx/reg-eu u-name] handler)))

(reg-event ::event/reg-eu
           (fn [cofx fx]
             (let [[u-name handler] (get-in cofx [:event 1])]
               (reg-eu fx u-name handler))))


(reg-fx ::fx/reg-eu
        (fn register-entity-uniform-fx [u-map]
          (doseq [[u-name handler] u-map]
            (u/register-entity-uniform u-name handler))))


(defn send-event!
  "Dispatch an event"
  [& events]
  (doseq [e events]
    (event/dispatch e)))


;; REFLECT
;; It's still side effectfull, but the actual registration
;; is done through an event and a fx.
;; it's ok for programs, because they need post registration
;; computation. Other registrable items like uniforms cofx
;; and fx do not need extra
;; computation
(defn reg-p
  ([id p]
   (event/dispatch [::event/reg-p [id p]]))
  ([fx id p]
   (assoc-in fx [::fx/reg-p id] p)))

(reg-fx ::fx/reg-p
        (fn [prog-map]
          (doseq [[id p] prog-map]
            (-> p
                (assoc :name id)
                prog/set-shaders! ;; assoc-in [:shaders]
                prog/set-vao!     ;; assoc-in [:shaders]
                prog/create!
                register/add-program!))))

(reg-event ::event/reg-p
           (fn [cofx fx]
             (let [[id prog] (get-in cofx [:event 1])]
               (reg-p fx id prog))))
;; (reg-p :foo/bar
;;        {:layout   [:vec3f/coordinates :vec3f/color :vec2f/texture]
;;         :pipeline [[:vertex "shaders/default.vert"]
;;                    [:fragment "shaders/default.frag"]]})
;;
;; (reg-p fx
;;        :foo/bar
;;        {:layout   [:vec3f/coordinates :vec3f/color :vec2f/texture]
;;         :pipeline [[:vertex "shaders/default.vert"]
;;                    [:fragment "shaders/default.frag"]]})
;;
;;
;; (reg-p :my-prog p)
;; (reg-p fx :my-prog p)
;;
