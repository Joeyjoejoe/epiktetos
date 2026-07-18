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

(defn members->schema
  "Builds a recursive block schema from introspected block members.
   block-name - string, GLSL block variable name
   members    - coll of maps, introspected member properties
   Returns a map {field-name schema} where schema is a leaf
   ({:kind :scalar|:vector|:matrix ...}), a struct
   ({:kind :struct :fields {...}}) or an array
   ({:kind :array :count n :elements [...]})."
  [block-name members]
  (-> (reduce (fn [tree member]
                (let [member (update member :varname
                                     #(strip-block-prefix block-name %))]
                  (when (pos? (:is-row-major member 0))
                    (throw (ex-info "Row-major block member not supported"
                                    {:block  block-name
                                     :member (:varname member)})))
                  (assoc-in tree (member-path member) (member-schema member))))
              {}
              members)
      (update-vals finalize-schema)))
