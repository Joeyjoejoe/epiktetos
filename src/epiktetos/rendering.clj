(ns epiktetos.rendering
  (:require [epiktetos.state :as state]
            [epiktetos.uniform :as u]
            [epiktetos.event :as event])
  (:import (org.joml Matrix4f)
           (org.lwjgl BufferUtils)
           (org.lwjgl.glfw GLFW)
           (org.lwjgl.opengl GL11 GL20 GL30 GL45)))

;; TODO Potencial performance opti :
;; - Uniform handler functions are pure and could all be computed in parallel,
;;   before executing the pipeline
(defn pipeline
  []
  (GL11/glClearColor 0.0 1.0 1.0 1.0)
  (GL11/glClear (bit-or GL11/GL_COLOR_BUFFER_BIT GL11/GL_DEPTH_BUFFER_BIT))

  (let [r-context {:db        @state/db
                   :global-u  (u/compute-global-u @state/db)}]

    (doseq [[vao-layout programs] @state/rendering]
      (let [{:keys [:vao/id :vao/stride]} (state/vao vao-layout)]

        (GL30/glBindVertexArray id)

        (doseq [[program entities] programs]
          (let [{:as p
                 pid :program/id
                 u-queue :uniforms
                 primitive :primitive} (state/program program)
                p-context (-> r-context
                              (assoc :pid (GL20/glUseProgram pid))
                              (assoc :program  program)
                              (assoc :entities entities))
                eu-queue (u/purge-u! u-queue ::u/program p-context)]

            (doseq [[entity-id draw?] entities]
              (let [{:as entity :keys [position vbo assets]} (state/entity entity-id)
                    e-context (assoc p-context :entity entity)]
                (u/purge-u! eu-queue ::u/entity e-context)

                ;; TODO Implement other rendering methods
                ;;      - Instance rendering
                ;;      - Indice drawing
                (GL45/glVertexArrayVertexBuffer id 0 vbo 0 stride)
                (GL11/glDrawArrays primitive 0 (count (:vertices assets)))))))))))


;;    ;; Render context
;;
;;    - VAO : id, stride
;;    - program : id, uniforms
;;    - entity : position, vbo, vertices count
;;
;;    systems/
;;      - programs
;;      - vaos
;;      - shaders
;;
;;
;;    ;; Program config
;;    {:name     :default
;;     :layout   [:coordinates :color]
;;     :pipeline [[:vert "shaders/default.vert"]
;;                [:frag "shaders/default.frag"]]}
;;
;;    ;; Program system
;;    {:program/id 1
;;     :uniforms   [{:name "model" :location 0}
;;                  {:name "view" :location 1}
;;                  {:name "projection" :location 2}]}
;;
;;
;;    ;; Program config->system
;;    ;; Parse pipeline shaders sources
;;    [{:attr/location 0 :type "vec3"}
;;     {:attr/location 1 :type "vec3"}
;;     {:unif/location 0 :name "model"}
;;     {:unif/location 1 :name "view"}
;;     {:unif/location 2 :name "projection"}]
;;
;;
;;
;;    ;; :pipeline config used to:
;;    ;;   - Check if vao layout already exists
;;    ;;   - Create a new vao (match vec indexes with attr/location
;;    ;;     parsed from shaders sources.
;;    [:coordinates :color]
;;    [{:attr/location 0 :type "vec3"}
;;     {:attr/location 1 :type "vec3"}]
;;
;;    ;; Match :pipeline indexes <=> :attr/location, then merge
;;    [{:key :coordinates :attr/location 0 :type "vec3"}
;;     {:key :color :attr/location 1 :type "vec3"}]
;;
;;    ;; Resolve
;;    [{:key :coordinates :size 3 :type :float}
;;     {:key :color :size 3 :type :float}]
;;
;;    ;; system
;;    {:vao/id 1
;;     :vao/name :default
;;     :vao/layout [:coordinates :color] ;; layout must be unique
;;     :vao/stride 64}
;;
;;    ;; store in a global registry
;;    :vao/layouts {[:coordinates :color] {:vao/id 1
;;                                         :vao/name :default
;;                                         :vao/layout [:coordinates :color]
;;                                         :vao/stride 64}}
;;
;;
;;
;;
;;
;;    ;; Mandatory parameters for rendering a model
;;    {:model/id 24
;;     :model/position [0.0 0.0 0.0]
;;     :model/vert-count 26
;;     :program/id 1
;;     :program/uniforms ["view" "model" "projection"]
;;     :vao/id 1
;;     :vao/stride 9
;;     :vbo/id 1}
;;
;;
;;    (reg-uniform [:program/default :view]
;;                 (fn [entity]
;;                   (rotate-around 0.0 0.0 0.0)))
