(ns epiktetos.opengl.glsl
  (:import (org.lwjgl.opengl GL11 GL20 GL21 GL30 GL40)))

(def TRANSPARENT-TYPE
  {;; Float types
   GL20/GL_FLOAT                {:base-type GL11/GL_FLOAT        :size 1 :bytes 4  :glsl-name :float}
   GL20/GL_FLOAT_VEC2           {:base-type GL11/GL_FLOAT        :size 2 :bytes 8  :glsl-name :vec2}
   GL20/GL_FLOAT_VEC3           {:base-type GL11/GL_FLOAT        :size 3 :bytes 12 :glsl-name :vec3}
   GL20/GL_FLOAT_VEC4           {:base-type GL11/GL_FLOAT        :size 4 :bytes 16 :glsl-name :vec4}

   ;; Int types
   GL20/GL_INT                  {:base-type GL11/GL_INT          :size 1 :bytes 4  :integer? true :glsl-name :int}
   GL20/GL_INT_VEC2             {:base-type GL11/GL_INT          :size 2 :bytes 8  :integer? true :glsl-name :ivec2}
   GL20/GL_INT_VEC3             {:base-type GL11/GL_INT          :size 3 :bytes 12 :integer? true :glsl-name :ivec3}
   GL20/GL_INT_VEC4             {:base-type GL11/GL_INT          :size 4 :bytes 16 :integer? true :glsl-name :ivec4}

   ;; Unsigned int types
   GL20/GL_UNSIGNED_INT         {:base-type GL11/GL_UNSIGNED_INT :size 1 :bytes 4  :integer? true :glsl-name :uint}
   GL30/GL_UNSIGNED_INT_VEC2    {:base-type GL11/GL_UNSIGNED_INT :size 2 :bytes 8  :integer? true :glsl-name :uvec2}
   GL30/GL_UNSIGNED_INT_VEC3    {:base-type GL11/GL_UNSIGNED_INT :size 3 :bytes 12 :integer? true :glsl-name :uvec3}
   GL30/GL_UNSIGNED_INT_VEC4    {:base-type GL11/GL_UNSIGNED_INT :size 4 :bytes 16 :integer? true :glsl-name :uvec4}

   ;; Bool types (treated as uint)
   GL20/GL_BOOL                 {:base-type GL11/GL_UNSIGNED_INT :size 1 :bytes 4  :integer? true :glsl-name :bool}
   GL20/GL_BOOL_VEC2            {:base-type GL11/GL_UNSIGNED_INT :size 2 :bytes 8  :integer? true :glsl-name :bvec2}
   GL20/GL_BOOL_VEC3            {:base-type GL11/GL_UNSIGNED_INT :size 3 :bytes 12 :integer? true :glsl-name :bvec3}
   GL20/GL_BOOL_VEC4            {:base-type GL11/GL_UNSIGNED_INT :size 4 :bytes 16 :integer? true :glsl-name :bvec4}

   ;; Double types
   GL11/GL_DOUBLE               {:base-type GL11/GL_DOUBLE       :size 1 :bytes 8  :double? true :glsl-name :double}
   GL40/GL_DOUBLE_VEC2          {:base-type GL11/GL_DOUBLE       :size 2 :bytes 16 :double? true :glsl-name :dvec2}
   GL40/GL_DOUBLE_VEC3          {:base-type GL11/GL_DOUBLE       :size 3 :bytes 24 :double? true :glsl-name :dvec3}
   GL40/GL_DOUBLE_VEC4          {:base-type GL11/GL_DOUBLE       :size 4 :bytes 32 :double? true :glsl-name :dvec4}

   ;; Float matrices (columns × rows) - :total-locations = number of columns
   GL20/GL_FLOAT_MAT2           {:base-type GL11/GL_FLOAT        :size 2 :bytes 16  :total-locations 2 :glsl-name :mat2}
   GL20/GL_FLOAT_MAT3           {:base-type GL11/GL_FLOAT        :size 3 :bytes 36  :total-locations 3 :glsl-name :mat3}
   GL20/GL_FLOAT_MAT4           {:base-type GL11/GL_FLOAT        :size 4 :bytes 64  :total-locations 4 :glsl-name :mat4}
   GL21/GL_FLOAT_MAT2x3         {:base-type GL11/GL_FLOAT        :size 3 :bytes 24  :total-locations 2 :glsl-name :mat2x3}
   GL21/GL_FLOAT_MAT2x4         {:base-type GL11/GL_FLOAT        :size 4 :bytes 32  :total-locations 2 :glsl-name :mat2x4}
   GL21/GL_FLOAT_MAT3x2         {:base-type GL11/GL_FLOAT        :size 2 :bytes 24  :total-locations 3 :glsl-name :mat3x2}
   GL21/GL_FLOAT_MAT3x4         {:base-type GL11/GL_FLOAT        :size 4 :bytes 48  :total-locations 3 :glsl-name :mat3x4}
   GL21/GL_FLOAT_MAT4x2         {:base-type GL11/GL_FLOAT        :size 2 :bytes 32  :total-locations 4 :glsl-name :mat4x2}
   GL21/GL_FLOAT_MAT4x3         {:base-type GL11/GL_FLOAT        :size 3 :bytes 48  :total-locations 4 :glsl-name :mat4x3}

   ;; Double matrices
   GL40/GL_DOUBLE_MAT2          {:base-type GL11/GL_DOUBLE       :size 2 :bytes 32  :total-locations 2 :double? true :glsl-name :dmat2}
   GL40/GL_DOUBLE_MAT3          {:base-type GL11/GL_DOUBLE       :size 3 :bytes 72  :total-locations 3 :double? true :glsl-name :dmat3}
   GL40/GL_DOUBLE_MAT4          {:base-type GL11/GL_DOUBLE       :size 4 :bytes 128 :total-locations 4 :double? true :glsl-name :dmat4}
   GL40/GL_DOUBLE_MAT2x3        {:base-type GL11/GL_DOUBLE       :size 3 :bytes 48  :total-locations 2 :double? true :glsl-name :dmat2x3}
   GL40/GL_DOUBLE_MAT2x4        {:base-type GL11/GL_DOUBLE       :size 4 :bytes 64  :total-locations 2 :double? true :glsl-name :dmat2x4}
   GL40/GL_DOUBLE_MAT3x2        {:base-type GL11/GL_DOUBLE       :size 2 :bytes 48  :total-locations 3 :double? true :glsl-name :dmat3x2}
   GL40/GL_DOUBLE_MAT3x4        {:base-type GL11/GL_DOUBLE       :size 4 :bytes 96  :total-locations 3 :double? true :glsl-name :dmat3x4}
   GL40/GL_DOUBLE_MAT4x2        {:base-type GL11/GL_DOUBLE       :size 2 :bytes 64  :total-locations 4 :double? true :glsl-name :dmat4x2}
   GL40/GL_DOUBLE_MAT4x3        {:base-type GL11/GL_DOUBLE       :size 3 :bytes 96  :total-locations 4 :double? true :glsl-name :dmat4x3}})
