(ns epiktetos.render.entity
  (:require [epiktetos.registrar :as registrar]
            [epiktetos.render.step :as render-step]
            [epiktetos.effect :as fx])
  (:import (org.lwjgl.opengl GL11 GL32 GL40)))

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
  #{:program :assets})

(defonce OPTIONAL-RENDER-PARAMS
  #{:group :primitives :indices :instances})

(defonce RESERVED-ENTITY-KEYS
  #{:prog-id :vao-id :vbo-ids :ibo-id :sort-key})

(defn build-vbos [entity vao]
  (assoc entity :vbo-ids []))

(defn build-ibo  [entity indices]
  (assoc entity :ibo-id nil))

(defn prep-entity
  "Prepare an entity for rendering.
  Return entity map"

  ([render-params]
   (prep-entity (::registrar/opengl @registrar/register) render-params))

  ([opengl-register render-params]
   (let [{::registrar/keys [programs vaos]} opengl-register
         {:keys [program primitives indices]
          :or   {primitives :triangles}}
         render-params]

     (if-let [{:p/keys [id vao-id]} (get programs program)]
       (-> render-params
           (assoc :prog-id id :vao-id vao-id :primitives primitives)
           (build-vbos (get vaos vao-id))
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
                [sort-key] (fnil conj #{}) entity-id))))

(defn delete-entity
  "Remove entity from :entities and :queue"
  [render-register entity-id]
  (if-let [{sort-key :sort-key} (get-in render-register [:entities entity-id])]
    (let [remaining (disj (get-in render-register [:queue sort-key]) entity-id)]
      (cond-> render-register
        (empty? remaining) (update :queue dissoc sort-key)
        (seq remaining)    (assoc-in [:queue sort-key] remaining)
        true (update :entities dissoc entity-id)))
    render-register))

(defn add-entity
  [register entity-id render-params]
  (let [{::registrar/keys [render opengl]} register]
    (->> render-params
         (prep-entity opengl)
         (reg-entity (delete-entity render entity-id) entity-id)
         (assoc register ::registrar/render))))

(defn add-entity!
  [entity-id render-params]
  (swap! registrar/register add-entity entity-id render-params))

(defn delete-entity!
  [entity-id]
  (swap! registrar/register update ::registrar/render delete-entity entity-id))


(comment

  (add-entity! :my-entity2 {:program :some-program
                            :assets  {}
                            :group   "group0"
                            :primitives :triangles
                            :indices    [1 2 3]
                            :instances  10
                            ;; custom user defined stuff for shader inputs
                            :position []
                            :material "water"})

  (delete-entity! :my-entity2)


  ;; (get-in @registrar/register [::registrar/render :queue])
  ;; (assoc fx :e/render! [:my-entity {:program "MyShaderProgram" :assets myModel}])

  )
