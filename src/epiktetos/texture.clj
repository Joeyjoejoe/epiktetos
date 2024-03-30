(ns epiktetos.texture
  (:require [clojure.java.io :as io])
  (:import (org.lwjgl.stb STBImage)
           (org.lwjgl BufferUtils)
           (org.lwjgl.opengl GL11 GL12 GL30 GL45)))

;; TODO Use a cache namespace for all cache needs.
(def text-cache (atom {}))

;; TODO Study GL45 methods params and extract the
;;      relevant ones in an option map.
(defn load-from-path
  [path]
  (let [file   (.getAbsolutePath (io/file (io/resource path)))
        width  (BufferUtils/createIntBuffer 1)
        height (BufferUtils/createIntBuffer 1)
        color  (BufferUtils/createIntBuffer 1)
        ;; TODO channels depends on format :
        ;;      - GL11/RGB have 3 color channels
        ;;      - GL12/RGBA have 4 color channels
        desired-color-channels 4
        texture (GL45/glCreateTextures GL11/GL_TEXTURE_2D)]

    (GL45/glTextureParameteri texture GL11/GL_TEXTURE_WRAP_S GL12/GL_CLAMP_TO_EDGE) ;; GL11/GL_REPEAT)
    (GL45/glTextureParameteri texture GL11/GL_TEXTURE_WRAP_T GL12/GL_CLAMP_TO_EDGE) ;; GL11/GL_REPEAT)
    (GL45/glTextureParameteri texture GL11/GL_TEXTURE_MIN_FILTER GL11/GL_NEAREST)
    (GL45/glTextureParameteri texture GL11/GL_TEXTURE_MAG_FILTER GL11/GL_NEAREST)

    ;; Flip the texture horizontaly, because OpenGL expects the 0.0 coordinate on the y-axis to be on the bottom side of the image, but images usually have 0.0 at the top of the y-axis
    (STBImage/stbi_set_flip_vertically_on_load true)

    (if-let [texture-data (STBImage/stbi_load file width height color desired-color-channels)]
      (let [w (.get width)
            h (.get height)]

        (GL45/glTextureStorage2D texture 1 GL11/GL_RGBA8 w h)
        (GL45/glTextureSubImage2D texture 0 0 0 w h GL11/GL_RGBA GL11/GL_UNSIGNED_BYTE texture-data)
        (GL45/glGenerateTextureMipmap texture)
        (STBImage/stbi_image_free texture-data)

        (swap! text-cache assoc path texture)

        texture)
      (throw (Exception. (str "Texture loading failed: " (STBImage/stbi_failure_reason) " at " path))))))


(defn load-entity
  [entity]
  (if-let [texture-paths (get-in entity [:assets :textures])]
    (assoc-in entity [:assets :textures]
              (mapv #(or (get @text-cache %)
                         (load-from-path %)) texture-paths))
    entity))
