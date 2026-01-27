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
   #{:type :array-size :offset :block-index :location
     ;; :name-length
     ;; :array-stride :matrix-stride :is-row-major
     ;; :atomic-counter-buffer-index
     ;; :referenced-by-vertex-shader :referenced-by-tess-control-shader
     ;; :referenced-by-tess-evaluation-shader :referenced-by-geometry-shader
     ;; :referenced-by-fragment-shader :referenced-by-compute-shader
     }

   GL43/GL_UNIFORM_BLOCK
   #{:buffer-binding :buffer-data-size :num-active-variables
     ;; :name-length :active-variables
     ;; :referenced-by-vertex-shader :referenced-by-tess-control-shader
     ;; :referenced-by-tess-evaluation-shader :referenced-by-geometry-shader
     ;; :referenced-by-fragment-shader :referenced-by-compute-shader
     }

   GL42/GL_ATOMIC_COUNTER_BUFFER
   #{:buffer-binding :buffer-data-size :num-active-variables
     ;; :active-variables
     ;; :referenced-by-vertex-shader :referenced-by-tess-control-shader
     ;; :referenced-by-tess-evaluation-shader :referenced-by-geometry-shader
     ;; :referenced-by-fragment-shader :referenced-by-compute-shader
     }

   GL43/GL_SHADER_STORAGE_BLOCK
   #{:buffer-binding :buffer-data-size :num-active-variables
     ;; :name-length :active-variables
     ;; :referenced-by-vertex-shader :referenced-by-tess-control-shader
     ;; :referenced-by-tess-evaluation-shader :referenced-by-geometry-shader
     ;; :referenced-by-fragment-shader :referenced-by-compute-shader
     }

   GL43/GL_BUFFER_VARIABLE
   #{:type :array-size :offset :block-index :array-stride
     ;; :name-length
     ;; :matrix-stride :is-row-major
     ;; :top-level-array-size :top-level-array-stride
     ;; :referenced-by-vertex-shader :referenced-by-tess-control-shader
     ;; :referenced-by-tess-evaluation-shader :referenced-by-geometry-shader
     ;; :referenced-by-fragment-shader :referenced-by-compute-shader
     }

   GL43/GL_PROGRAM_INPUT
   #{:name-length :type :array-size :location :is-per-patch :location-component
     :referenced-by-vertex-shader :referenced-by-tess-control-shader
     :referenced-by-tess-evaluation-shader :referenced-by-geometry-shader
     :referenced-by-fragment-shader :referenced-by-compute-shader}

   GL43/GL_PROGRAM_OUTPUT
   #{:name-length :type :array-size :location :location-index :is-per-patch
     :location-component :referenced-by-vertex-shader :referenced-by-tess-control-shader
     :referenced-by-tess-evaluation-shader :referenced-by-geometry-shader
     :referenced-by-fragment-shader :referenced-by-compute-shader}

   GL43/GL_TRANSFORM_FEEDBACK_VARYING
   #{:name-length :type :array-size :offset :transform-feedback-buffer-index}

   GL30/GL_TRANSFORM_FEEDBACK_BUFFER
   #{:buffer-binding :num-active-variables :active-variables
     :transform-feedback-buffer-stride}

   GL43/GL_VERTEX_SUBROUTINE_UNIFORM
   #{:name-length :array-size :location :num-compatible-subroutines
     :compatible-subroutines}

   GL43/GL_TESS_CONTROL_SUBROUTINE_UNIFORM
   #{:name-length :array-size :location :num-compatible-subroutines
     :compatible-subroutines}

   GL43/GL_TESS_EVALUATION_SUBROUTINE_UNIFORM
   #{:name-length :array-size :location :num-compatible-subroutines
     :compatible-subroutines}

   GL43/GL_GEOMETRY_SUBROUTINE_UNIFORM
   #{:name-length :array-size :location :num-compatible-subroutines
     :compatible-subroutines}

   GL43/GL_FRAGMENT_SUBROUTINE_UNIFORM
   #{:name-length :array-size :location :num-compatible-subroutines
     :compatible-subroutines}

   GL43/GL_COMPUTE_SUBROUTINE_UNIFORM
   #{:name-length :array-size :location :num-compatible-subroutines
     :compatible-subroutines}

   GL43/GL_VERTEX_SUBROUTINE
   #{:name-length}

   GL43/GL_TESS_CONTROL_SUBROUTINE
   #{:name-length}

   GL43/GL_TESS_EVALUATION_SUBROUTINE
   #{:name-length}

   GL43/GL_GEOMETRY_SUBROUTINE
   #{:name-length}

   GL43/GL_FRAGMENT_SUBROUTINE
   #{:name-length}

   GL43/GL_COMPUTE_SUBROUTINE
   #{:name-length}})

(def BLOCK-MEMBER-INTERFACES
  {GL43/GL_UNIFORM_BLOCK            GL43/GL_UNIFORM
   GL43/GL_SHADER_STORAGE_BLOCK     GL43/GL_BUFFER_VARIABLE
   GL42/GL_ATOMIC_COUNTER_BUFFER    GL42/GL_ATOMIC_COUNTER_BUFFER
   GL30/GL_TRANSFORM_FEEDBACK_BUFFER GL43/GL_TRANSFORM_FEEDBACK_VARYING})

(defn shader-interface-indexes
  [program-id program-interface-id]
  (let [buffer (BufferUtils/createIntBuffer 1)
        _      (GL43/glGetProgramInterfaceiv program-id program-interface-id GL43/GL_ACTIVE_RESOURCES buffer)
        interface-count (.get buffer 0)]
    (range interface-count)))


(defn shader-interface-props
  [program-id program-interface-id interface-index]
  (let [varname    (GL43/glGetProgramResourceName program-id program-interface-id interface-index)
        props-keys (get INTERFACE-PROPERTIES program-interface-id)
        properties (int-array (map PROPERTIES props-keys))
        length     (int-array 1)
        props-vals (int-array (alength properties))
        _          (GL43/glGetProgramResourceiv program-id program-interface-id interface-index properties length props-vals)
        props-map  (zipmap props-keys (vec props-vals))]


    (-> props-map
        (assoc :varname varname :interface-index interface-index :program program-id)
        (update :type #(get glsl/TRANSPARENT-TYPE %)))))


(defn resource-block-members
  [program-id resource props-map]
  (if-let [num-vars (:num-active-variables props-map)]
    (let [member-interface (get BLOCK-MEMBER-INTERFACES resource)
          block-index      (:interface-index props-map)
          member-indices   (int-array num-vars)
          _                (GL43/glGetProgramResourceiv
                             program-id
                             resource
                             block-index
                             (int-array [GL43/GL_ACTIVE_VARIABLES])
                             (int-array 1)
                             member-indices)
          members          (mapv (comp #(dissoc % :block-index :interface-index :program :location)
                                       #(shader-interface-props program-id member-interface %))
                                 member-indices)]
      (assoc props-map :members members))
    props-map))


(defn resource-properties
  [program-id resource-k]
  (let [resource (get PROGRAM-INTERFACES resource-k)]
      (->> resource
          (shader-interface-indexes program-id)
          (map #(shader-interface-props program-id resource %))
          (map #(resource-block-members program-id resource %)))))


(defn attributes-infos
  [program-id]
  (let [attribs-coll (resource-properties program-id ::attribute)]
    (into {} (map (juxt :varname identity) attribs-coll))))

(comment

  (event/dispatch [:dev/eval #(resource-properties 7 ::shader-storage-block)])

  )
