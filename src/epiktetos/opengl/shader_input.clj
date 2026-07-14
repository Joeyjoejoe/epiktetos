(ns epiktetos.opengl.shader-input
  (:require [epiktetos.opengl.introspection :as introspect]
            [epiktetos.registrar :as registrar])
  (:import  (org.lwjgl.opengl GL11 GL20 GL31 GL42 GL43)))

(defonce RESOURCE-BINDING-MAX
  {:ubo            GL31/GL_MAX_UNIFORM_BUFFER_BINDINGS
   :ssbo           GL43/GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS
   :atomic-counter GL42/GL_MAX_ATOMIC_COUNTER_BUFFER_BINDINGS
   :texture-unit   GL20/GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS
   :image-unit     GL42/GL_MAX_IMAGE_UNITS})

(defn resource-binding-set
  "Returns a set of all valid binding points for resource.
   Binding point 0 is reserved for implicit binding detection
   and never returned"
  [resource]
  (->> resource
      RESOURCE-BINDING-MAX
      GL11/glGetInteger
      (range 1)
      set))

(defn alloc-known-binding
  "Lookup for a single resource in registrar.
  Return resource-infos unchanged if lookup returns nil.
  Return resource-infos with updated binding-point if lookup succeed"
  [resource-infos]
  (let [{:keys [buffer-binding varname]} resource-infos]

    (if-let [{:keys [binding-point]} (registrar/lookup-program-input varname)]
      (cond
        (= buffer-binding 0)             (assoc resource-infos :alloc :registrar :buffer-binding binding-point)
        (= buffer-binding binding-point) (assoc resource-infos :alloc :valid)
        :else (throw (ex-info "Binding conflict"
                              {:program (:program resource-infos)
                               :resource varname
                               :cause (str varname " can't be bound to " buffer-binding ", already registered at binding point " binding-point ".")})))
      resource-infos)))

(defn alloc-known-bindings
  [resources-infos]
  (map alloc-known-binding resources-infos))

(defn tag-explicit-binding
  [resources-info]
  (if (> (:buffer-binding resources-info) 0)
    (assoc resources-info :alloc :explicit)
    resources-info))

(defn tag-explicit-bindings
  [resources-infos]
  (map tag-explicit-binding resources-infos))

(defn detect-binding-conflict
  [resources-infos]
  (if-let [conflicts (->> resources-infos
                          (filter :alloc)
                          (group-by :buffer-binding)
                          (vals)
                          (filter #(> (count %) 1))
                          (mapcat identity)
                          not-empty)]
           (throw (ex-info "Binding conflict"
                           {:conflicts conflicts}))
           resources-infos))

(defn alloc-new-bindings
  [resource resources-infos]
  (let [bindings (resource-binding-set resource)

        allocated-bindings (->> (registrar/lookup-resource-inputs resource)
                                (map :binding-point))

        free-bindings (sort (remove (set allocated-bindings) bindings))]

    (first
      (reduce
        (fn [[acc remaining-bindings] m]
          (if (:alloc m)
            [(conj acc m) remaining-bindings]
            (if-let [binding-point (first remaining-bindings)]
              [(conj acc (assoc m :buffer-binding binding-point :alloc :auto))
               (rest remaining-bindings)]
              (throw (ex-info "No more binding points available"
                              {:element m})))))
        [[] free-bindings]
        resources-infos))))

(defn allocate-binding-points
  [register-resource intro-data]
  (->> intro-data
       tag-explicit-bindings
       alloc-known-bindings
       detect-binding-conflict
       (alloc-new-bindings register-resource)))

(defn- setup-block-bindings!
  "Allocates and applies binding points for a program's interface blocks.
   prog-map    - program map with :id
   resource    - keyword, :ubo or :ssbo
   interface-k - introspection interface keyword
   bind!       - function (fn [prog-id interface-index binding-point])
   register!   - function (fn [block]), registers the block in the registry
   Returns prog-map with block varnames added to :inputs."
  [prog-map resource interface-k bind! register!]
  (let [prog-id (:id prog-map)
        blocks  (try (->> interface-k
                          (introspect/resource-properties prog-id)
                          (allocate-binding-points resource))
                     (catch clojure.lang.ExceptionInfo e
                       (throw (ex-info (ex-message e)
                                       (assoc (ex-data e) :in-program prog-id)))))]

    (doseq [{:keys [interface-index buffer-binding]
             :as   block} blocks]
      (bind! prog-id interface-index buffer-binding)
      (register! block))

    (update prog-map :inputs into (map :varname blocks))))

(defn setup-ubos!
  "Auto allocate binding points of program ubos"
  [prog-map]
  (setup-block-bindings! prog-map :ubo ::introspect/uniform-block
                         (fn [prog-id idx binding-point]
                           (GL31/glUniformBlockBinding prog-id idx binding-point))
                         registrar/register-ubo!))

(defn setup-ssbos!
  "Auto allocate binding points of program ssbos"
  [prog-map]
  (setup-block-bindings! prog-map :ssbo ::introspect/shader-storage-block
                         (fn [prog-id idx binding-point]
                           (GL43/glShaderStorageBlockBinding prog-id idx binding-point))
                         registrar/register-ssbo!))

(defn register-input-handler!
  "Registers a user input handler for a bindable shader input.
   varname - string, GLSL block variable name
   handler - function (fn [db step-value]), produces the buffer data
   options - map, :step defaults to :step/frame
   Returns the updated render-state value."
  [varname handler options]
  (let [{:keys [step] :or {step :step/frame}} options
        input-handler (merge options {:handler handler :step step})]
    (swap! registrar/render-state
           assoc-in [::registrar/step-inputs step varname] input-handler)))
