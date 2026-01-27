(ns epiktetos.opengl.shader-input
  (:require [epiktetos.event :as event]
            [epiktetos.opengl.introspection :as introspect]
            [epiktetos.registrar :as registrar])
  (:import  (org.lwjgl.opengl GL11 GL20 GL31 GL42 GL43)))

(defonce RESOURCE-BINDING-MAX
  #::registrar{:ubo  GL31/GL_MAX_UNIFORM_BUFFER_BINDINGS
               :ssbo GL43/GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS
               :atomic-counter GL42/GL_MAX_ATOMIC_COUNTER_BUFFER_BINDINGS
               :texture-unit GL20/GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS
               :image-unit GL42/GL_MAX_IMAGE_UNITS})

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
  [resource resource-infos]
  (let [{:keys [buffer-binding varname]} resource-infos]

    (if-let [{:keys [binding-point] :as res} (registrar/lookup-resource resource varname)]
      (cond
        (= buffer-binding 0)             (assoc resource-infos :alloc :registrar :buffer-binding binding-point)
        (= buffer-binding binding-point) (assoc resource-infos :alloc :valid)
        :else (throw (ex-info "Binding conflict"
                              {:program (:program resource-infos)
                               :resource varname
                               :cause (str varname " can't be bound to " buffer-binding ", already registered at binding point " binding-point ".")})))
      resource-infos)))

(defn alloc-known-bindings
 [resource resources-infos]
   (map #(alloc-known-binding resource %)
        resources-infos))

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

        allocated-bindings (->> resource
                                registrar/lookup-resource
                                vals
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
  (try
    (->> intro-data
         tag-explicit-bindings
         (alloc-known-bindings register-resource)
         detect-binding-conflict
         (alloc-new-bindings register-resource))
    (catch clojure.lang.ExceptionInfo e
      (prn e))))

(defn setup-ubos
  "Auto allocate binding points of program ubos"
  [prog-map]
  (let [prog-id (:p/id prog-map)
        ubos    (->> ::introspect/uniform-block
                     (introspect/resource-properties prog-id)
                     (allocate-binding-points ::registrar/ubo))
        ubo-names (map :varname ubos)]

    (doseq [{:keys [interface-index buffer-binding]
             :as   ubo} ubos]
      (GL31/glUniformBlockBinding prog-id interface-index buffer-binding)
      (registrar/register-ubo! ubo))

    (assoc prog-map :p/ubos ubo-names)))

(defn setup-ssbos
  "Auto allocate binding points of program ssbos"
  [prog-map]
  (let [prog-id (:p/id prog-map)
        ssbos    (->> ::introspect/shader-storage-block
                     (introspect/resource-properties prog-id)
                     (allocate-binding-points ::registrar/ssbo))
        ssbo-names (map :varname ssbos)]

    (doseq [{:keys [interface-index buffer-binding]
             :as   ssbo} ssbos]
      (GL43/glShaderStorageBlockBinding prog-id interface-index buffer-binding)
      (registrar/register-ssbo! ssbo))

    (assoc prog-map :p/ssbos ssbo-names)))


(comment

    (event/dispatch [:dev/eval #(sort (remove (set (range 5 90)) (resource-binding-set ::registrar/ubo)))])



    )
