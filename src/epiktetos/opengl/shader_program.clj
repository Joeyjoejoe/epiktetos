(ns epiktetos.opengl.shader-program
  (:require [epiktetos.event :as event]
            [epiktetos.registrar :as registrar]
            [epiktetos.opengl.shader :as shader]
            [epiktetos.opengl.shader-input :as input]
            [epiktetos.opengl.shader-attribute :as attribute])
  (:import (org.lwjgl.opengl GL20 GL30 GL11 GL43 GL45)
           (org.lwjgl BufferUtils)))

(defn link!
  "Create a shader program from program map DSL"
  [program-map]
  (let [{:keys [pipeline vertex-layout]} program-map
        shader-ids (map shader/interpret pipeline)
        id (GL20/glCreateProgram)]

    (doseq [shader-id shader-ids]
      (GL20/glAttachShader id shader-id))

    (GL20/glLinkProgram id)

    (when (= 0 (GL20/glGetProgrami id GL20/GL_LINK_STATUS))
      (-> (str "Error linking shader to program " (GL20/glGetProgramInfoLog id 1024))
          Exception.
          throw))

    (doseq [shader-id shader-ids]
      (GL20/glDeleteShader shader-id))

    (assoc program-map :id id)))

(defn setup!
  [prog-k prog-map]
  (let [old-prog        (registrar/get-program prog-k)
        layout-changed? (and old-prog
                             (not= (:vertex-layout old-prog)
                                   (:vertex-layout prog-map)))
        program (-> prog-map
                    link!
                    attribute/setup!
                    input/setup-ubos!
                    input/setup-ssbos!
                    (assoc :dirty layout-changed?))]

    (when old-prog
      (GL20/glDeleteProgram (:id old-prog)))

    (registrar/register-program prog-k program)

    program))
