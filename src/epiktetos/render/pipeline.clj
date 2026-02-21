(ns epiktetos.render.pipeline
  (:require [epiktetos.registrar :as registrar]
            [epiktetos.render.step :as rs])
  (:import (org.lwjgl.opengl GL11 GL20 GL30 GL31 GL45)))

(defn pipeline
  ""
  ([] (pipeline @registrar/registry @registrar/render-state))
  ([registry render-state]

  ;; TODO Handle dirty state here in dvelopment environement only ?

  (let [{::registrar/keys [opengl-registry]} registry
        {:keys [programs vaos ubos ssbos]} opengl-registry
        {::registrar/keys [steps custom-step-order queue entities]} render-state
        custom-steps  (keep steps custom-step-order)
        {group-step   :step/group
         vao-step     :step/vao
         program-step :step/program} steps]

    ;; TODO step/frame
    (GL11/glClearColor 0.0 1.0 1.0 1.0)
    (GL11/glClear (bit-or GL11/GL_COLOR_BUFFER_BIT GL11/GL_DEPTH_BUFFER_BIT))


    (loop [prev-k nil
           rende-queue (seq queue)]

      (when-let [[sk entity-ids] (first rende-queue)]

        (let [{:keys [program]} (get entities (first entity-ids))
              {:keys [id vao-id]}      (get programs program)
              {:keys [vbos]}           (get vaos vao-id)]


          (when (rs/step-changed? group-step sk prev-k)
            ;; TODO Get group config and potentially do core stuff :
            ;; - framebuffer binding
            ;; - group inputs handler
            ;; ...
            )

          (when (rs/step-changed? vao-step sk prev-k)
            (GL30/glBindVertexArray vao-id)
            ;;TODO exec step handlers
            )

          (when (rs/step-changed? program-step sk prev-k)
            (GL20/glUseProgram id)
            ;;TODO exec step handlers
            )

          (doseq [custom-step custom-steps
                  :when (rs/step-changed? custom-step sk prev-k)]
            ;; TODO custom step changed?
            )

          (doseq [entity-id entity-ids
                  :let [{:keys [vbo-ids ibo-id ibo-length primitives instances vertex-count]}
                        (get entities entity-id)]]

            ;; VBO binding
            (doseq [[index {:keys [binding-index stride]}] (map-indexed vector vbos)
                    :let [vbo-id (get vbo-ids index)]]
              (GL45/glVertexArrayVertexBuffer vao-id binding-index vbo-id 0 stride))

            (when ibo-id
              (GL45/glVertexArrayElementBuffer vao-id ibo-id)
              (if instances
                (GL31/glDrawElementsInstanced primitives ibo-length GL11/GL_UNSIGNED_INT 0 instances)
                (GL11/glDrawElements primitives ibo-length GL11/GL_UNSIGNED_INT 0)))

            (when-not ibo-id
              (if instances
                (GL31/glDrawArraysInstanced primitives 0 vertex-count instances)
                (GL11/glDrawArrays primitives 0 vertex-count)))


            ))

        (recur sk (next rende-queue)))))))
