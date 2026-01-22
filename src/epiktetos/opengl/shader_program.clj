(ns epiktetos.opengl.shader-program
  (:require [epiktetos.event :as event]
            [epiktetos.registrar :as registrar]
            [epiktetos.opengl.shader :as shader]
            [epiktetos.opengl.shader-attribute :as attribute])
  (:import (org.lwjgl.opengl GL20 GL32 GL40 GL43)))

(defn link
  "Create a shader program from program map DSL"
  [program-map]
  (let [{:p/keys [pipeline vertex-layout]} program-map
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

    (assoc program-map :p/id id)))

(defn setup
  [prog-k prog-map]
  (let [program (-> prog-map
                    link
                    attribute/setup)]

    (registrar/register-program prog-k program)

    program))


(comment

  (do

  (def prog-map
    #:p{:pipeline [[:vertex "shaders/default.vert"]
                   [:fragment "shaders/default.frag"]]
        :vertex-layout [{:layout ["vLocal" "vColor" "vertexTexCoords"]
                         :handler #()
                         :storage :dynamic
                         :normalize #{"vColor"}
                         :divisor 0}]})

  (def instanced
    #:p{:pipeline [[:vertex "shaders/instanced.vert"]
                   [:fragment "shaders/instanced.frag"]]
        :vertex-layout [{:layout ["vLocal" "vertexTexCoords"]
                         :handler #()
                         :storage :persistent
                         :divisor 0}
                        {:layout ["instancePosition" "instanceColor" "instanceSpeed"]
                         :handler #()
                         :storage :dynamic
                         :divisor 1}]})

  ;; Program registration :
  ;;   1) init (interpret DSL)
  ;;   2) intropspect :
  ;;      - Attributes (lookup/reg vao)
  ;;      - Location inputs (Uniforms lookup/reg)
  ;;      - Binding inputs (ssbos, ubos, counter lookup/reg)
  (event/dispatch [:dev/eval #(setup :some-program instanced)])


  )

)
