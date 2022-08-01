(ns epictetus.vocabulary.opengl
  (:require [epictetus.utils.keyword :as k])
  (:import (org.lwjgl.opengl GL20 GL32 GL40 GL43)))

(defonce dictionary
  {:shader.stage/vertex          GL20/GL_VERTEX_SHADER
   :shader.stage/fragment        GL20/GL_FRAGMENT_SHADER
   :shader.stage/geometry        GL32/GL_GEOMETRY_SHADER
   :shader.stage/tess_control    GL40/GL_TESS_CONTROL_SHADER
   :shader.stage/tess_evaluation GL40/GL_TESS_EVALUATION_SHADER
   :shader.stage/compute         GL43/GL_COMPUTE_SHADER})
