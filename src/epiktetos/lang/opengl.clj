(ns epiktetos.lang.opengl
  (:require [epiktetos.utils.keyword :as k])
  (:import (org.lwjgl.opengl GL11 GL20 GL32 GL40 GL43)))

(defonce DICTIONARY
  {:vertex          GL20/GL_VERTEX_SHADER
   :fragment        GL20/GL_FRAGMENT_SHADER
   :geometry        GL32/GL_GEOMETRY_SHADER
   :tess_control    GL40/GL_TESS_CONTROL_SHADER
   :tess_evaluation GL40/GL_TESS_EVALUATION_SHADER
   :compute         GL43/GL_COMPUTE_SHADER})

(defonce DRAW-PRIMITIVES
  {:triangles                GL11/GL_TRIANGLES
   :lines                    GL11/GL_LINES
   :points                   GL11/GL_POINTS
   :line-strip               GL11/GL_LINE_STRIP
   :line-loop                GL11/GL_LINE_LOOP
   :line-strip-adjacency     GL32/GL_LINE_STRIP_ADJACENCY
   :lines-adjacency          GL32/GL_LINES_ADJACENCY
   :triangle-strip           GL11/GL_TRIANGLE_STRIP
   :triangle-fan             GL11/GL_TRIANGLE_FAN
   :triangle-strip-adjacency GL32/GL_TRIANGLE_STRIP_ADJACENCY
   :triangles-adjacency      GL32/GL_TRIANGLES_ADJACENCY
   :patches                  GL40/GL_PATCHES})

