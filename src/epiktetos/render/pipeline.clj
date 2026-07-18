(ns epiktetos.render.pipeline
  (:require [epiktetos.registrar :as registrar]
            [epiktetos.shader-input.buffer :as input-buffer]
            [epiktetos.render.entity :as render-entity]
            [epiktetos.render.step :as rs])
  (:import (org.lwjgl.opengl GL11 GL20 GL30 GL31 GL45)))

(defn pipeline
  ""
  ([db] (pipeline db @registrar/registry @registrar/render-state))
  ([db registry render-state]

  ;; TODO Handle dirty state here in dvelopment environement only ?

  (let [{::registrar/keys [opengl-registry input-registry]} registry
        {:keys [programs vaos program-inputs]} opengl-registry
        {::registrar/keys [steps custom-step-order queue entities]} render-state
        custom-steps  (keep steps custom-step-order)
        step-inputs    (input-buffer/inputs-by-step input-registry)
        update-inputs! (fn [step step-value]
                         (input-buffer/update-inputs!
                           db program-inputs (get step-inputs step) step-value))
        {group-step   :step/group
         vao-step     :step/vao
         program-step :step/program} steps]

    ;; TODO step/frame
    (GL11/glClearColor 0.1 0.1 0.1 1.0)
    (GL11/glClear (bit-or GL11/GL_COLOR_BUFFER_BIT GL11/GL_DEPTH_BUFFER_BIT))

    (update-inputs! :step/frame (get-in db [:core/loop :iter]))


    (loop [prev-k nil
           rende-queue (seq queue)]

      (when-let [[sk entity-ids] (first rende-queue)]

        (let [batch-entity (get entities (first entity-ids))
              {:keys [program group]} batch-entity
              {:keys [id vao-id]}     (get programs program)
              {:keys [vbos]}          (get vaos vao-id)]


          (when (rs/step-changed? group-step sk prev-k)
            ;; TODO Get group config and potentially do core stuff :
            ;; - framebuffer binding
            ;; - group inputs handler
            ;; ...
            (update-inputs! :step/group group))

          (when (rs/step-changed? vao-step sk prev-k)
            (GL30/glBindVertexArray vao-id)
            (update-inputs! :step/vao vao-id))

          (when (rs/step-changed? program-step sk prev-k)
            (GL20/glUseProgram id)
            (update-inputs! :step/program program))

          (doseq [custom-step custom-steps
                  :when (rs/step-changed? custom-step sk prev-k)]
            (update-inputs! (:name custom-step)
                            ((:handler custom-step) batch-entity)))

          (doseq [entity-id entity-ids
                  :let [entity (get entities entity-id)
                        {:keys [vbo-ids ibo-id ibo-length primitives vertex-count]}
                        entity
                        instances (render-entity/draw-count entity db)]]

            (update-inputs! :step/entity entity)

            ;; VBO binding
            (doseq [[index {:keys [binding-index stride]}] (map-indexed vector vbos)
                    :let [vbo-id (get vbo-ids index)]]
              (GL45/glVertexArrayVertexBuffer vao-id binding-index vbo-id 0 stride))

            (when ibo-id
              (GL45/glVertexArrayElementBuffer vao-id ibo-id)
              (if instances
                (when (pos? instances)
                  (GL31/glDrawElementsInstanced primitives ibo-length GL11/GL_UNSIGNED_INT 0 (int instances)))
                (GL11/glDrawElements primitives ibo-length GL11/GL_UNSIGNED_INT 0)))

            (when-not ibo-id
              (if instances
                (when (pos? instances)
                  (GL31/glDrawArraysInstanced primitives 0 vertex-count (int instances)))
                (GL11/glDrawArrays primitives 0 vertex-count)))


            ))

        (recur sk (next rende-queue)))))))
