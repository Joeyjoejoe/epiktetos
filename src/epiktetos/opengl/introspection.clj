(ns epiktetos.opengl.introspection
  (:require [epiktetos.event :as event]
            [epiktetos.opengl.glsl :as glsl])
  (:import (org.lwjgl.opengl GL20 GL30 GL42 GL43 GL44)
           (org.lwjgl BufferUtils)))

(defonce PROGRAM-INTERFACES
  #::{:attribute GL43/GL_PROGRAM_INPUT
      :uniform GL43/GL_UNIFORM
      :uniform-block GL43/GL_UNIFORM_BLOCK
      :shader-storage-block GL43/GL_SHADER_STORAGE_BLOCK
      :buffer-variable GL43/GL_BUFFER_VARIABLE
      :atomic-count-buffer GL42/GL_ATOMIC_COUNTER_BUFFER
      :program-output GL43/GL_PROGRAM_OUTPUT
      :transform-feedback-varying GL43/GL_TRANSFORM_FEEDBACK_VARYING
      :transform-feedback-buffer GL30/GL_TRANSFORM_FEEDBACK_BUFFER})

(def PROPERTIES
  {:name-length                          GL43/GL_NAME_LENGTH
   :type                                 GL43/GL_TYPE
   :array-size                           GL43/GL_ARRAY_SIZE
   :offset                               GL43/GL_OFFSET
   :block-index                          GL43/GL_BLOCK_INDEX
   :array-stride                         GL43/GL_ARRAY_STRIDE
   :matrix-stride                        GL43/GL_MATRIX_STRIDE
   :is-row-major                         GL43/GL_IS_ROW_MAJOR
   :atomic-counter-buffer-index          GL43/GL_ATOMIC_COUNTER_BUFFER_INDEX
   :location                             GL43/GL_LOCATION
   :location-index                       GL43/GL_LOCATION_INDEX
   :location-component                   GL44/GL_LOCATION_COMPONENT
   :is-per-patch                         GL43/GL_IS_PER_PATCH
   :buffer-binding                       GL43/GL_BUFFER_BINDING
   :buffer-data-size                     GL43/GL_BUFFER_DATA_SIZE
   :num-active-variables                 GL43/GL_NUM_ACTIVE_VARIABLES
   :active-variables                     GL43/GL_ACTIVE_VARIABLES
   :top-level-array-size                 GL43/GL_TOP_LEVEL_ARRAY_SIZE
   :top-level-array-stride               GL43/GL_TOP_LEVEL_ARRAY_STRIDE
   :transform-feedback-buffer-index      GL44/GL_TRANSFORM_FEEDBACK_BUFFER_INDEX
   :transform-feedback-buffer-stride     GL44/GL_TRANSFORM_FEEDBACK_BUFFER_STRIDE
   :num-compatible-subroutines           GL43/GL_NUM_COMPATIBLE_SUBROUTINES
   :compatible-subroutines               GL43/GL_COMPATIBLE_SUBROUTINES
   :referenced-by-vertex-shader          GL43/GL_REFERENCED_BY_VERTEX_SHADER
   :referenced-by-tess-control-shader    GL43/GL_REFERENCED_BY_TESS_CONTROL_SHADER
   :referenced-by-tess-evaluation-shader GL43/GL_REFERENCED_BY_TESS_EVALUATION_SHADER
   :referenced-by-geometry-shader        GL43/GL_REFERENCED_BY_GEOMETRY_SHADER
   :referenced-by-fragment-shader        GL43/GL_REFERENCED_BY_FRAGMENT_SHADER
   :referenced-by-compute-shader         GL43/GL_REFERENCED_BY_COMPUTE_SHADER})

(def INTERFACE-PROPERTIES
  {GL43/GL_UNIFORM
   [:name-length :type :array-size :offset :block-index :array-stride
    :matrix-stride :is-row-major :atomic-counter-buffer-index :location
    :referenced-by-vertex-shader :referenced-by-tess-control-shader
    :referenced-by-tess-evaluation-shader :referenced-by-geometry-shader
    :referenced-by-fragment-shader :referenced-by-compute-shader]

   GL43/GL_UNIFORM_BLOCK
   [:name-length :buffer-binding :buffer-data-size :num-active-variables
    :active-variables :referenced-by-vertex-shader :referenced-by-tess-control-shader
    :referenced-by-tess-evaluation-shader :referenced-by-geometry-shader
    :referenced-by-fragment-shader :referenced-by-compute-shader]

   GL42/GL_ATOMIC_COUNTER_BUFFER
   [:buffer-binding :buffer-data-size :num-active-variables :active-variables
    :referenced-by-vertex-shader :referenced-by-tess-control-shader
    :referenced-by-tess-evaluation-shader :referenced-by-geometry-shader
    :referenced-by-fragment-shader :referenced-by-compute-shader]

   GL43/GL_SHADER_STORAGE_BLOCK
   [:name-length :buffer-binding :buffer-data-size :num-active-variables
    :active-variables :referenced-by-vertex-shader :referenced-by-tess-control-shader
    :referenced-by-tess-evaluation-shader :referenced-by-geometry-shader
    :referenced-by-fragment-shader :referenced-by-compute-shader]

   GL43/GL_BUFFER_VARIABLE
   [:name-length :type :array-size :offset :block-index :array-stride
    :matrix-stride :is-row-major :top-level-array-size :top-level-array-stride
    :referenced-by-vertex-shader :referenced-by-tess-control-shader
    :referenced-by-tess-evaluation-shader :referenced-by-geometry-shader
    :referenced-by-fragment-shader :referenced-by-compute-shader]

   GL43/GL_PROGRAM_INPUT
   [:name-length :type :array-size :location :is-per-patch :location-component
    :referenced-by-vertex-shader :referenced-by-tess-control-shader
    :referenced-by-tess-evaluation-shader :referenced-by-geometry-shader
    :referenced-by-fragment-shader :referenced-by-compute-shader]

   GL43/GL_PROGRAM_OUTPUT
   [:name-length :type :array-size :location :location-index :is-per-patch
    :location-component :referenced-by-vertex-shader :referenced-by-tess-control-shader
    :referenced-by-tess-evaluation-shader :referenced-by-geometry-shader
    :referenced-by-fragment-shader :referenced-by-compute-shader]

   GL43/GL_TRANSFORM_FEEDBACK_VARYING
   [:name-length :type :array-size :offset :transform-feedback-buffer-index]

   GL30/GL_TRANSFORM_FEEDBACK_BUFFER
   [:buffer-binding :num-active-variables :active-variables
    :transform-feedback-buffer-stride]

   GL43/GL_VERTEX_SUBROUTINE_UNIFORM
   [:name-length :array-size :location :num-compatible-subroutines
    :compatible-subroutines]

   GL43/GL_TESS_CONTROL_SUBROUTINE_UNIFORM
   [:name-length :array-size :location :num-compatible-subroutines
    :compatible-subroutines]

   GL43/GL_TESS_EVALUATION_SUBROUTINE_UNIFORM
   [:name-length :array-size :location :num-compatible-subroutines
    :compatible-subroutines]

   GL43/GL_GEOMETRY_SUBROUTINE_UNIFORM
   [:name-length :array-size :location :num-compatible-subroutines
    :compatible-subroutines]

   GL43/GL_FRAGMENT_SUBROUTINE_UNIFORM
   [:name-length :array-size :location :num-compatible-subroutines
    :compatible-subroutines]

   GL43/GL_COMPUTE_SUBROUTINE_UNIFORM
   [:name-length :array-size :location :num-compatible-subroutines
    :compatible-subroutines]

   GL43/GL_VERTEX_SUBROUTINE
   [:name-length]

   GL43/GL_TESS_CONTROL_SUBROUTINE
   [:name-length]

   GL43/GL_TESS_EVALUATION_SUBROUTINE
   [:name-length]

   GL43/GL_GEOMETRY_SUBROUTINE
   [:name-length]

   GL43/GL_FRAGMENT_SUBROUTINE
   [:name-length]

   GL43/GL_COMPUTE_SUBROUTINE
   [:name-length]})


(defn shader-interface-indexes
  [program-id program-interface-id]
  (let [buffer (BufferUtils/createIntBuffer 1)
        _      (GL43/glGetProgramInterfaceiv program-id program-interface-id GL43/GL_ACTIVE_RESOURCES buffer)
        interface-count (.get buffer 0)]
  (range interface-count)))

(defn shader-interface-name
  [program-id program-interface-id interface-index]
  (GL43/glGetProgramResourceName program-id program-interface-id interface-index))


(defn shader-interface-props
  [program-id program-interface-id interface-index]
  (let [props-keys (get INTERFACE-PROPERTIES program-interface-id)
        properties (int-array (map PROPERTIES props-keys))
        length (int-array 1)
        props-values (int-array (alength properties))
        _         (GL43/glGetProgramResourceiv program-id program-interface-id interface-index properties length props-values)
        props-map (zipmap props-keys (vec props-values))
        varname   (GL43/glGetProgramResourceName program-id program-interface-id interface-index)]

    ;; Add detailled types data
    (-> props-map
        (assoc :varname varname
               :block-index interface-index
               :program program-id)
        (update :type #(get glsl/TRANSPARENT-TYPE %)))))

(defn program-resource-infos
  [program-id resource-k]
  (let [resource (get PROGRAM-INTERFACES resource-k)
        indexes  (shader-interface-indexes program-id resource)]
;;    (reduce #(assoc %1 (shader-interface-name program-id resource %2)
;;                    (shader-interface-props program-id resource %2))
;;            {}
;;            indexes)
    (map #(shader-interface-props program-id resource %) indexes)

    ))

(defn attributes-infos
  [program-id]
  (let [attribs-coll (program-resource-infos program-id ::attribute)]
    (into {} (map (juxt :varname identity) attribs-coll))))

(defn ubo-infos
  [program-id]
  (program-resource-infos program-id ::uniform-block))

(defn ubo-block-infos
  [program-id]
  (->> (program-resource-infos program-id ::uniform)
       (filter (fn [[_ v]] (>= (:block-index v) 0)))
       (into {})))

(defn uniform-infos
  [program-id]
  (->> (program-resource-infos program-id ::uniform)
       (filter (fn [[_ v]] (< (:block-index v) 0)))
       (into {})))
(comment

    (event/dispatch [:dev/eval #(program-resource-infos 4 ::uniform-block)])
    (event/dispatch [:dev/eval #(ubo-infos 4)])
    (event/dispatch [:dev/eval #(uniform-infos 4)])
  ;;    // OpenGL 4.3+ constants
  ;;  GL_PROGRAM_INPUT              // Vertex attributes
  ;;  GL_UNIFORM                    // Uniforms simples (uniform float, vec3, mat4, etc)
  ;;  GL_UNIFORM_BLOCK              // Uniform Buffer Objects (UBO)
  ;;  GL_SHADER_STORAGE_BLOCK       // Shader Storage Buffer Objects (SSBO)
  ;;  GL_BUFFER_VARIABLE            // Variables individuelles dans un SSBO
  ;;  GL_ATOMIC_COUNTER_BUFFER      // Atomic counter buffers
  ;;
  ;;  // Autres interfaces (moins prioritaires)
  ;;  GL_PROGRAM_OUTPUT             // Fragment shader outputs
  ;;  GL_TRANSFORM_FEEDBACK_VARYING // Transform feedback
  ;;  GL_TRANSFORM_FEEDBACK_BUFFER  // Transform feedback buffers
  ;;
  ;;  API moderne (OpenGL 4.3+)
  ;;
  ;;  Fonctions principales :
  ;;
  ;;  // 1. Obtenir le nombre de ressources d'une interface
  ;;  glGetProgramInterfaceiv(program, interface, GL_ACTIVE_RESOURCES, params)
  ;;
  ;;  // 2. Obtenir l'index d'une ressource par nom
  ;;  glGetProgramResourceIndex(program, interface, name)
  ;;
  ;;  // 3. Obtenir le nom d'une ressource par index
  ;;  glGetProgramResourceName(program, interface, index, length, name)
  ;;
  ;;  // 4. Obtenir les propriétés d'une ressource
  ;;  glGetProgramResourceiv(program, interface, index, propCount, props, bufSize, length, params)
  ;;
  ;;  // 5. Obtenir la location (raccourci)
  ;;  glGetProgramResourceLocation(program, interface, name)
  ;;  glGetProgramResourceLocationIndex(program, interface, name)
  ;;
  ;;  Propriétés interrogeables (pour glGetProgramResourceiv)
  ;;
  ;;  GL_NAME_LENGTH                    // Longueur du nom
  ;;  GL_TYPE                           // Type GLSL (GL_FLOAT, GL_FLOAT_VEC3, GL_FLOAT_MAT4, etc)
  ;;  GL_ARRAY_SIZE                     // Taille si array
  ;;  GL_LOCATION                       // Location/binding point
  ;;  GL_BLOCK_INDEX                    // Index du block parent (UBO/SSBO)
  ;;  GL_OFFSET                         // Offset en bytes
  ;;  GL_ARRAY_STRIDE                   // Stride pour arrays
  ;;  GL_MATRIX_STRIDE                  // Stride pour matrices
  ;;  GL_IS_ROW_MAJOR                   // Row-major vs column-major
  ;;  GL_ATOMIC_COUNTER_BUFFER_INDEX    // Index atomic counter
  ;;  GL_REFERENCED_BY_VERTEX_SHADER    // Utilisé dans VS?
  ;;  GL_REFERENCED_BY_FRAGMENT_SHADER  // Utilisé dans FS?
  ;;  GL_REFERENCED_BY_COMPUTE_SHADER   // Utilisé dans CS?
  )
