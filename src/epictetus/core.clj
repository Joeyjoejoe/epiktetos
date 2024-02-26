(ns epictetus.core
  (:require [integrant.core :as ig]
            [clojure.java.io :as io]
            [epictetus.state :as state]
            [epictetus.coeffect :as cofx]
            [epictetus.effect :as fx]
            [epictetus.loop :as game-loop]
            [epictetus.event :as event]
            [epictetus.uniform :as u]
            [epictetus.interceptors :as interc :refer [->interceptor]]
            [epictetus.utils.buffer :as util]
            [epictetus.window]
            [epictetus.program])
  (:import (org.lwjgl.glfw GLFW)
           (org.joml Matrix4f)
           (org.lwjgl BufferUtils)
           (org.lwjgl.opengl GL45)))

(def system state/system)
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
     (reset! system sys)
     (game-loop/start @system)))

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
                              (let [{:keys [_ db] :as coeffects} (:coeffects context)
                                    effect     {:db db}]

                                (->> (handler-fn coeffects effect)
                                     (assoc context :effects))))})
         interceptors [fx/do-fx cofx/inject-db cofx/inject-system coeffects handler]
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

;; CORE events
(reg-event
  :mouse/position
  (fn [{[_ position] :event} fx]
    (assoc-in fx [:db :mouse/position] position)))


(reg-event
  [:press :escape]
  (fn quit-flag [cofx fx]
    ;; TODO extract system part to proper cofx and fx
    (GLFW/glfwSetWindowShouldClose (:glfw/window @system) true)))

;; EXPERIMENTATIONS
(reg-event
  [:press :m]
  [(cofx/inject :edn/load "triangle-no.edn")]
  (fn render-level [{model :edn/load} fx]
    (-> fx
        (update :render conj [:bar model]))))

(reg-event
  [:press :c]
  [(cofx/inject :edn/load "triangle-se.edn")]
  (fn render-level [{model :edn/load} fx]
    (-> fx
        (update :render conj [:foo model]))))

(reg-event
  [:press :v]
  [(cofx/inject :edn/load "cube.edn")]
  (fn render-level [{model :edn/load} fx]
    (-> fx
        (update :render conj [:cube model]))))

(reg-event
  [:press :delete]
  (fn remove-cube [_ fx]
    (assoc fx :delete-all true)))

(reg-event
  [:repeat :space]
  [(cofx/inject :edn/load "cube.edn")]
  (fn render-random-cube [{model :edn/load} fx]
    (let [[min max] [-10 10]
          position  [(+ (rand min) (rand max))
                     (+ (rand min) (rand max))
                     (+ (rand min) (rand max))]
          [min max] [0 1]
          color     [(+ (rand min) (rand max))
                     (+ (rand min) (rand max))
                     (+ (rand min) (rand max))]
          colored-vertices (mapv #(assoc % :color color)
                                 (get-in model [:assets :vertices]))
          entity-id (-> "cube-"
                        (str (. (new java.util.Date) (getTime))))

          texture (-> "textures" io/resource io/file .list rand-nth)

          random-cube [entity-id (-> model
                                     (assoc :position position)
                                     (assoc :speed (* (rand-nth [-1 1])
                                                      (rand 5)))
                                     (assoc-in [:assets :textures] [(str "textures/" texture)])
                                     (assoc-in [:assets :vertices] colored-vertices))]]
      (-> fx
          (update :render conj random-cube)))))

;; (reg-eu [:default :model]
;;         (fn [db entities entity] ...))

(reg-eu :model
        (fn model-matrix [db entities entity]
          (let [[x y z] (:position entity)
                buffer (BufferUtils/createFloatBuffer 16)]
            (-> (Matrix4f.)
                (.translate x y z)
                (.get buffer)))))

(reg-u :view
       (fn [db]
         (let [t (GLFW/glfwGetTime)
               eye-x (* 10.0 (Math/sin t))
               eye-y (* -10.0 (Math/cos t))
               eye-z (* 10.0 (Math/cos t))
               center-x 0.0
               center-y 0.0
               center-z 0.0
               up-x     0.0
               up-y     1.0
               up-z     0.0
               buffer (BufferUtils/createFloatBuffer 16)]
           (-> (Matrix4f.)
               (.lookAt eye-x eye-y eye-z
                        center-x center-y center-z
                        up-x up-y up-z)
               (.get buffer)))))

(reg-u [:default :projection]
       (fn [db entities]
         (let [window (:glfw/window @state/system)
               width  (util/int-buffer [0])
               height (util/int-buffer [0])
               _      (GLFW/glfwGetWindowSize window width height)
               fovy   (. Math toRadians 45.0)
               aspect (/ (.get width 0) (.get height 0))
               zmin   0.01
               zmax   100.0
               buffer (BufferUtils/createFloatBuffer 16)]
           (-> (Matrix4f.)
               (.perspective fovy aspect zmin zmax)
               (.get buffer)))))

(reg-eu [:default :t]
        (fn [db entities entity]
          (GLFW/glfwGetTime)))

(reg-eu [:default :speed]
        (fn [db entities entity]
          (or (:speed entity)
              5.0)))

(reg-eu :textIndex0
        (fn [db entities entity]
          (if-let [textures (get-in entity [:assets :textures])]
            (do
              (GL45/glBindTextureUnit 0 (first textures))
              0))))
