(ns epiktetos.shader-input.types
  (:require [clojure.string :as string]))

(def ^:private MEMBER-NAME-SEGMENT #"([^.\[\]]+)(?:\[(\d+)\])?")

(defn- parse-path
  "Parses a flattened GLSL member name into a path of field names and
   array indices.
   varname - string, e.g. \"lights[1].position\"
   Returns a vector of strings and integers, e.g. [\"lights\" 1 \"position\"]."
  [varname]
  (reduce (fn [path [_ field index]]
            (cond-> (conj path field)
              index (conj (parse-long index))))
          []
          (re-seq MEMBER-NAME-SEGMENT varname)))

(defn- strip-block-prefix
  "Removes the block name prefix from a member name, present when the
   block is declared with an instance name.
   block-name  - string, GLSL block name
   member-name - string, introspected member name
   Returns the member name without prefix."
  [block-name member-name]
  (let [prefix (str block-name ".")]
    (if (string/starts-with? member-name prefix)
      (subs member-name (count prefix))
      member-name)))

(defn- glsl-type-kind
  "Returns the schema kind of a GLSL type.
   glsl-type - map, GLSL type
   Returns :matrix, :vector or :scalar."
  [glsl-type]
  (cond
    (:total-locations glsl-type) :matrix
    (< 1 (:size glsl-type))      :vector
    :else                        :scalar))

(defn- glsl-type-schema
  "Builds the schema of a member's GLSL type, ignoring arrayness.
   member - map, introspected member properties
   Returns a schema map with :kind, :type, :offset, and :matrix-stride
   for matrices."
  [member]
  (let [{:keys [type offset matrix-stride]} member
        kind (glsl-type-kind type)]
    (cond-> {:kind kind :type type :offset offset}
      (= :matrix kind) (assoc :matrix-stride matrix-stride))))

(defn- member-schema
  "Builds the schema of an introspected member: its GLSL type schema,
   or an array of it expanded from :array-size and :array-stride.
   member - map, introspected member properties
   Returns a schema map."
  [member]
  (let [{:keys [array-size array-stride offset]} member
        schema (glsl-type-schema member)]
    (if (< 1 array-size)
      {:kind     :array
       :count    array-size
       :elements (mapv #(assoc schema :offset (+ offset (* % array-stride)))
                       (range array-size))}
      schema)))

(defn- member-path
  "Returns the insertion path of an introspected member in the schema.
   Basic-type arrays drop their trailing [0] index, as they are
   expanded into a single array schema.
   member - map, introspected member properties
   Returns a vector of strings and integers."
  [member]
  (let [path (parse-path (:varname member))]
    (if (< 1 (:array-size member))
      (pop path)
      path)))

(defn- finalize-schema
  "Converts a raw schema tree into its final form: maps indexed by
   integers become array schemas, other maps become struct schemas.
   schema - map, raw tree or member schema
   Returns a schema map."
  [schema]
  (if (:kind schema)
    schema
    (if (every? integer? (keys schema))
      {:kind     :array
       :count    (count schema)
       :elements (mapv (comp finalize-schema val) (sort-by key schema))}
      {:kind   :struct
       :fields (update-vals schema finalize-schema)})))

(defn- runtime-member?
  "Returns true when an introspected member belongs to the block's
   runtime array (unsized last member of an SSBO), signaled by a
   top-level-array-size of 0 (struct elements) or an array-size of 0
   (basic-type elements, drivers reporting a top-level size of 1).
   member - map, introspected member properties"
  [member]
  (or (= 0 (:top-level-array-size member))
      (= 0 (:array-size member))))

(defn- runtime-stride
  "Byte stride between two consecutive elements of a runtime array:
   the top-level array stride when positive, the member's own array
   stride otherwise (basic-type elements).
   member - map, introspected member properties
   Returns a positive int."
  [member]
  (let [stride (:top-level-array-stride member 0)]
    (if (pos? stride) stride (:array-stride member))))

(defn- runtime-array-schema
  "Converts the finalized single-element array schema of a runtime
   array into its runtime form.
   schema - map, array schema holding the introspected element 0
   stride - int, byte stride between two consecutive elements
   Returns {:kind :array :count :runtime :stride stride :element schema}."
  [schema stride]
  {:kind    :array
   :count   :runtime
   :stride  stride
   :element (first (:elements schema))})

(defn runtime-array
  "Returns the [field-name schema] entry of the schema's runtime
   array, or nil when the block has none.
   schema - map {field-name schema}, from members->schema"
  [schema]
  (->> schema
       (filter (fn [[_ fschema]] (= :runtime (:count fschema))))
       first))

(defn set-capacity
  "Sets the element capacity of the schema's runtime array.
   schema   - map {field-name schema}, from members->schema
   capacity - pos int, maximum element count
   Returns the schema, unchanged when it has no runtime array."
  [schema capacity]
  (if-let [[fname _] (runtime-array schema)]
    (assoc-in schema [fname :capacity] capacity)
    schema))

(defn members->schema
  "Builds a recursive block schema from introspected block members.
   block-name - string, GLSL block variable name
   members    - coll of maps, introspected member properties
   Returns a map {field-name schema} where schema is a leaf
   ({:kind :scalar|:vector|:matrix ...}), a struct
   ({:kind :struct :fields {...}}), an array
   ({:kind :array :count n :elements [...]}) or a runtime array
   ({:kind :array :count :runtime :stride s :element schema})."
  [block-name members]
  (let [members (mapv (fn [member]
                        (update member :varname
                                #(strip-block-prefix block-name %)))
                      members)
        strides (->> members
                     (filter runtime-member?)
                     (map (juxt (comp first parse-path :varname)
                                runtime-stride))
                     (into {}))
        schema  (-> (reduce (fn [tree member]
                              (when (pos? (:is-row-major member 0))
                                (throw (ex-info "Row-major block member not supported"
                                                {:block  block-name
                                                 :member (:varname member)})))
                              (assoc-in tree (member-path member) (member-schema member)))
                            {}
                            members)
                    (update-vals finalize-schema))]
    (reduce-kv (fn [schema fname stride]
                 (update schema fname runtime-array-schema stride))
               schema
               strides)))
