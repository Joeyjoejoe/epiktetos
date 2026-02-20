(ns epiktetos.render.entity
  (:require [epiktetos.registrar :as registrar]
            [epiktetos.render.step :as render-step]
            [epiktetos.opengl.buffer :as buffer]
            [epiktetos.effect :as fx])
  (:import (org.lwjgl.opengl GL11 GL32 GL40 GL44 GL45)))

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

(defonce MANDATORY-RENDER-PARAMS
  #{:program})

(defonce OPTIONAL-RENDER-PARAMS
  #{:group :primitives :indices :instances})

(defonce RESERVED-ENTITY-KEYS
  #{:vbo-ids :ibo-id :sort-key})

(defn- run-handler
  "Executes a VBO handler and return output with vertex count if divisor is 0."
  [entity {:keys [handler divisor type-layout]}]
  (let [data (handler entity)]
    (if (= 0 divisor)
      {:handler-output data :vertex-count (/ (count data) (buffer/type-layout-count type-layout))}
      {:handler-output data})))

(defn- validate-vertex-count
  "Validates VBOs have the same vertex number and return it"
  [handler-results]
  (let [counts (keep :vertex-count handler-results)]
    (when (empty? counts)
      (throw (ex-info "No vertex buffer found" {})))
    (when-not (apply = counts)
      (throw (ex-info "Per-vertex VBO size mismatch"
                      {:counts (vec counts)})))
    (first counts)))

(defn- create-vbo
  [program {:keys [handler-output]} {:keys [type-layout storage] :as vbo-template}]
  (let [byte-buf  (try
                    (buffer/fill-byte-buffer type-layout handler-output)
                    (catch clojure.lang.ExceptionInfo e
                      (throw (ex-info (ex-message e)
                                      (assoc (ex-data e) :program program :vbo vbo-template)))))
        buffer-id (GL45/glCreateBuffers)]
    (GL45/glNamedBufferStorage buffer-id byte-buf ^int storage)
    buffer-id))

(defn build-vbos
  "Creates OpenGL byte buffers for each VBO defined in the VAO."
  [entity vbo-templates]
  (let [program         (:program entity)
        handler-results (mapv #(run-handler entity %) vbo-templates)
        vtx-count       (validate-vertex-count handler-results)
        vbo-ids         (mapv #(create-vbo program %1 %2) handler-results vbo-templates)]
    (assoc entity :vbo-ids vbo-ids :vertex-count vtx-count)))

(defn build-ibo  [entity indices]
  (if-not (nil? indices)
    (let [ibo-id   (GL45/glCreateBuffers)
          ibo-data (buffer/int-buffer indices)
          ibo-length (count indices)]

      (GL45/glNamedBufferStorage ibo-id ibo-data GL44/GL_DYNAMIC_STORAGE_BIT)
      (assoc entity :ibo-id ibo-id :ibo-length ibo-length))
    entity))

(defn prep-entity
  "Prepare an entity for rendering.
  Return entity map"
  ([render-params]
   (prep-entity (::registrar/opengl-registry @registrar/registry) render-params))

  ([opengl-register render-params]
   (let [{:keys [programs vaos]} opengl-register
         {:keys [program primitives indices]
          :or   {primitives :triangles}}
         render-params]

     (if-let [vao-id (get-in programs [program :vao-id])]
       (-> render-params
           (assoc :primitives (DRAW-PRIMITIVES primitives))
           (build-vbos (get-in vaos [vao-id :vbos]))
           (build-ibo  indices))
       (throw (ex-info "Unknown shader program"
                       {:program program}))))))


(defn reg-entity
  "Compute entity sort key, add entity to render-queue and entities index"
  [render-register entity-id entity]
  (let [[updated-render-register sort-key] (render-step/sort-key render-register entity)]
    (-> updated-render-register
        (assoc-in  [:entities entity-id] entity)
        (update-in [:entities entity-id] assoc :sort-key sort-key)
        (update :queue
                (fnil update-in (sorted-map))
                [sort-key] (fnil conj []) entity-id))))

(defn delete-entity
  "Remove entity from :entities and :queue"
  [render-register entity-id]
  (if-let [{sort-key :sort-key} (get-in render-register [:entities entity-id])]
    (let [remaining (filterv #(not= % entity-id) (get-in render-register [:queue sort-key]))]
      (cond-> render-register
        (empty? remaining) (update :queue dissoc sort-key)
        (seq remaining)    (assoc-in [:queue sort-key] remaining)
        true (update :entities dissoc entity-id)))
    render-register))

(defn add-entity
  [render-state opengl-registry entity-id render-params]
  (->> render-params
       (prep-entity opengl-registry)
       (reg-entity (delete-entity render-state entity-id) entity-id)))

(defn add-entity!
  [entity-id render-params]
  (swap! registrar/render-state add-entity (::registrar/opengl-registry @registrar/registry) entity-id render-params))

(defn delete-entity!
  [entity-id]
  (swap! registrar/render-state delete-entity entity-id))
