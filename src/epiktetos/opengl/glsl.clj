(ns epiktetos.opengl.glsl
  (:import (org.lwjgl.opengl GL11 GL20 GL21 GL30 GL40)))

(def TRANSPARENT-TYPE
  {;; Float types
   GL20/GL_FLOAT                {:base-type GL11/GL_FLOAT        :size 1 :bytes 4}
   GL20/GL_FLOAT_VEC2           {:base-type GL11/GL_FLOAT        :size 2 :bytes 8}
   GL20/GL_FLOAT_VEC3           {:base-type GL11/GL_FLOAT        :size 3 :bytes 12}
   GL20/GL_FLOAT_VEC4           {:base-type GL11/GL_FLOAT        :size 4 :bytes 16}

   ;; Int types
   GL20/GL_INT                  {:base-type GL11/GL_INT          :size 1 :bytes 4  :integer? true}
   GL20/GL_INT_VEC2             {:base-type GL11/GL_INT          :size 2 :bytes 8  :integer? true}
   GL20/GL_INT_VEC3             {:base-type GL11/GL_INT          :size 3 :bytes 12 :integer? true}
   GL20/GL_INT_VEC4             {:base-type GL11/GL_INT          :size 4 :bytes 16 :integer? true}

   ;; Unsigned int types
   GL20/GL_UNSIGNED_INT         {:base-type GL11/GL_UNSIGNED_INT :size 1 :bytes 4  :integer? true}
   GL30/GL_UNSIGNED_INT_VEC2    {:base-type GL11/GL_UNSIGNED_INT :size 2 :bytes 8  :integer? true}
   GL30/GL_UNSIGNED_INT_VEC3    {:base-type GL11/GL_UNSIGNED_INT :size 3 :bytes 12 :integer? true}
   GL30/GL_UNSIGNED_INT_VEC4    {:base-type GL11/GL_UNSIGNED_INT :size 4 :bytes 16 :integer? true}

   ;; Bool types (treated as uint)
   GL20/GL_BOOL                 {:base-type GL11/GL_UNSIGNED_INT :size 1 :bytes 4  :integer? true}
   GL20/GL_BOOL_VEC2            {:base-type GL11/GL_UNSIGNED_INT :size 2 :bytes 8  :integer? true}
   GL20/GL_BOOL_VEC3            {:base-type GL11/GL_UNSIGNED_INT :size 3 :bytes 12 :integer? true}
   GL20/GL_BOOL_VEC4            {:base-type GL11/GL_UNSIGNED_INT :size 4 :bytes 16 :integer? true}

   ;; Double types
   GL11/GL_DOUBLE               {:base-type GL11/GL_DOUBLE       :size 1 :bytes 8  :double? true}
   GL40/GL_DOUBLE_VEC2          {:base-type GL11/GL_DOUBLE       :size 2 :bytes 16 :double? true}
   GL40/GL_DOUBLE_VEC3          {:base-type GL11/GL_DOUBLE       :size 3 :bytes 24 :double? true}
   GL40/GL_DOUBLE_VEC4          {:base-type GL11/GL_DOUBLE       :size 4 :bytes 32 :double? true}

   ;; Float matrices (columns × rows) - :total-locations = number of columns
   GL20/GL_FLOAT_MAT2           {:base-type GL11/GL_FLOAT        :size 2 :bytes 16  :total-locations 2}
   GL20/GL_FLOAT_MAT3           {:base-type GL11/GL_FLOAT        :size 3 :bytes 36  :total-locations 3}
   GL20/GL_FLOAT_MAT4           {:base-type GL11/GL_FLOAT        :size 4 :bytes 64  :total-locations 4}
   GL21/GL_FLOAT_MAT2x3         {:base-type GL11/GL_FLOAT        :size 3 :bytes 24  :total-locations 2}
   GL21/GL_FLOAT_MAT2x4         {:base-type GL11/GL_FLOAT        :size 4 :bytes 32  :total-locations 2}
   GL21/GL_FLOAT_MAT3x2         {:base-type GL11/GL_FLOAT        :size 2 :bytes 24  :total-locations 3}
   GL21/GL_FLOAT_MAT3x4         {:base-type GL11/GL_FLOAT        :size 4 :bytes 48  :total-locations 3}
   GL21/GL_FLOAT_MAT4x2         {:base-type GL11/GL_FLOAT        :size 2 :bytes 32  :total-locations 4}
   GL21/GL_FLOAT_MAT4x3         {:base-type GL11/GL_FLOAT        :size 3 :bytes 48  :total-locations 4}

   ;; Double matrices
   GL40/GL_DOUBLE_MAT2          {:base-type GL11/GL_DOUBLE       :size 2 :bytes 32  :total-locations 2 :double? true}
   GL40/GL_DOUBLE_MAT3          {:base-type GL11/GL_DOUBLE       :size 3 :bytes 72  :total-locations 3 :double? true}
   GL40/GL_DOUBLE_MAT4          {:base-type GL11/GL_DOUBLE       :size 4 :bytes 128 :total-locations 4 :double? true}
   GL40/GL_DOUBLE_MAT2x3        {:base-type GL11/GL_DOUBLE       :size 3 :bytes 48  :total-locations 2 :double? true}
   GL40/GL_DOUBLE_MAT2x4        {:base-type GL11/GL_DOUBLE       :size 4 :bytes 64  :total-locations 2 :double? true}
   GL40/GL_DOUBLE_MAT3x2        {:base-type GL11/GL_DOUBLE       :size 2 :bytes 48  :total-locations 3 :double? true}
   GL40/GL_DOUBLE_MAT3x4        {:base-type GL11/GL_DOUBLE       :size 4 :bytes 96  :total-locations 3 :double? true}
   GL40/GL_DOUBLE_MAT4x2        {:base-type GL11/GL_DOUBLE       :size 2 :bytes 64  :total-locations 4 :double? true}
   GL40/GL_DOUBLE_MAT4x3        {:base-type GL11/GL_DOUBLE       :size 3 :bytes 96  :total-locations 4 :double? true}})
