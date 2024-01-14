(ns epictetus.rendering
  (:require [epictetus.state :as state]
            [epictetus.uniform :as u]
            [epictetus.event :as event])
  (:import (org.joml Matrix4f)
           (org.lwjgl BufferUtils)
           (org.lwjgl.glfw GLFW)
           (org.lwjgl.opengl GL11 GL20 GL30 GL45)))

(defn pipeline
  []
  (GL11/glClearColor 0.0 1.0 1.0 1.0)
  (GL11/glClear (bit-or GL11/GL_COLOR_BUFFER_BIT GL11/GL_DEPTH_BUFFER_BIT))

  (let [db @state/db
        system @state/system
        global-u (u/compute-global-u db)]

    (doseq [[vao-layout programs] @state/rendering]
      (let [{:keys [:vao/id :vao/stride]} (get-in system [:gl/engine :vao vao-layout])]
        ;; (println "Bind VAO" id)
        (GL30/glBindVertexArray id)

        (doseq [[prog entities] programs]
          ;; (println "Use program" prog)
          (let [{pid :program/id unis :uniforms} (get-in system [:gl/engine :program prog])
                ;; TODO Refactor uniforms managment
                uniforms (group-by first unis)]

            (GL20/glUseProgram pid)

            (GL20/glUniformMatrix4fv (get-in uniforms ["view" 0 2]) false (:view global-u))
            (GL20/glUniformMatrix4fv (get-in uniforms ["projection" 0 2]) false (:projection global-u))

            (doseq [[entity-id {:as entity :keys [position vbo assets]}] entities]

              (GL20/glUniformMatrix4fv (get-in uniforms ["model" 0 2]) false ((event/get-handler ::u/entity :model) db entity));

              ;; TODO Implement other rendering methods (indice, instance)

              (GL45/glVertexArrayVertexBuffer id 0 vbo 0 stride)
              (GL11/glDrawArrays GL11/GL_TRIANGLES 0 (count (:vertices assets))))))))))


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
