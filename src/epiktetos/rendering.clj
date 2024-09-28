(ns epiktetos.rendering
  (:require [epiktetos.state :as state]
            [epiktetos.uniform :as u]
            [epiktetos.event :as event])
  (:import (org.lwjgl.opengl GL11 GL20 GL30 GL45)))

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
      (when (nil? vao-layout)
        (throw (Exception. (str "Shader programs not found " (keys programs)))))

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
