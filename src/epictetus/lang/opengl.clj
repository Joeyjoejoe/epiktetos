(ns epictetus.lang.opengl
  (:require [epictetus.utils.keyword :as k])
  (:import (org.lwjgl.opengl GL20 GL32 GL40 GL43)))

(defonce dictionary
  {:vertex          GL20/GL_VERTEX_SHADER
   :fragment        GL20/GL_FRAGMENT_SHADER
   :geometry        GL32/GL_GEOMETRY_SHADER
   :tess_control    GL40/GL_TESS_CONTROL_SHADER
   :tess_evaluation GL40/GL_TESS_EVALUATION_SHADER
   :compute         GL43/GL_COMPUTE_SHADER})
