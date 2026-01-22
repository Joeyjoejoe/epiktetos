(ns epiktetos.opengl.buffer
  (:import (org.lwjgl.opengl GL30 GL44)))

(defonce BUFFER-STORAGE
  {:dynamic    GL44/GL_DYNAMIC_STORAGE_BIT
   :read       GL30/GL_MAP_READ_BIT
   :write      GL30/GL_MAP_WRITE_BIT
   :persistent GL44/GL_MAP_PERSISTENT_BIT
   :coherent   GL44/GL_MAP_COHERENT_BIT
   :client     GL44/GL_CLIENT_STORAGE_BIT})
