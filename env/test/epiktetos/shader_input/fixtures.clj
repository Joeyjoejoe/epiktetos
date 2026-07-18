(ns epiktetos.shader-input.fixtures
  (:require [epiktetos.opengl.buffer :as gl-buffer]
            [epiktetos.shader-input.types :as types]))

(defn member
  "Builds an introspected block member map as returned by the OpenGL
   introspection API.
   varname   - string, flattened member name
   glsl-name - keyword, GLSL type name
   props     - kvs, additional member properties (:offset, :array-size...)
   Returns a member map."
  [varname glsl-name & {:as props}]
  (merge {:varname       varname
          :type          (gl-buffer/glsl-type glsl-name)
          :array-size    1
          :array-stride  0
          :matrix-stride 0
          :is-row-major  0}
         props))

(def scene-members
  "Introspected members of the std140 block used as reference in
   input-spec.md:

   layout(std140) uniform Scene {
       mat4  view;         //   0
       vec3  camera_pos;   //  64
       float time;         //  76
       int   light_count;  //  80
       float weights[3];   //  96 (stride 16)
       Light sun;          // 144
       Light lights[2];    // 160
   };                      // size 192"
  [(member "view"                :mat4  :offset 0 :matrix-stride 16)
   (member "camera_pos"          :vec3  :offset 64)
   (member "time"                :float :offset 76)
   (member "light_count"         :int   :offset 80)
   (member "weights[0]"          :float :offset 96 :array-size 3 :array-stride 16)
   (member "sun.position"        :vec3  :offset 144)
   (member "sun.intensity"       :float :offset 156)
   (member "lights[0].position"  :vec3  :offset 160)
   (member "lights[0].intensity" :float :offset 172)
   (member "lights[1].position"  :vec3  :offset 176)
   (member "lights[1].intensity" :float :offset 188)])

(def scene-schema
  (types/members->schema "Scene" scene-members))

(def valid-scene-data
  {"view"        [1 0 0 0
                  0 1 0 0
                  0 0 1 0
                  0 0 0 1]
   "camera_pos"  [0.0 5.0 10.0]
   "time"        1.5
   "light_count" 2
   "weights"     [0.5 1.0 0.75]
   "sun"         {"position" [10.0 20.0 30.0] "intensity" 3.0}
   "lights"      [{"position" [1.0 0.0 0.0] "intensity" 2.0}
                  {"position" [0.0 1.0 0.0] "intensity" 0.5}]})
