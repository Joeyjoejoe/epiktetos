(ns epictetus.utils.glsl-parser
  (:require [clojure.java.io :as io]))

(def glsl-regexps {
  :attr/layout        #"(?m)^layout.+location.+=\s*([^\s)]).+in\s*([^\s]+)"
  :uniform/one-liners #"(?m)^[^\/\n\r]*uniform\s+(\S+)\s+(\S+)(?:;|\s*=)"
  :uniform/blocks     #""
  :struct/blocks      #"(?m)^struct\s+([^{\s]+)[^{]*\{([^}]*)\}"
  :block/members      #"(?m)^\s*(\S+)\s+([^;\s]+);"
})

(defn- block-members
  "Given a GLSL block content string `s`, return a map
   of its members"
  [s]
  (->> s
       (re-seq (:block/members glsl-regexps))
       (mapv #(into [] (rseq (subvec % 1))))
       ;;(reduce #(apply assoc %1 (reverse (subvec %2 1))) {})
       ))

(defn- map-attributes [source]
  (->> source
       (re-seq (:attr/layout glsl-regexps))
       (mapv #(vector (Integer/parseInt (second %))
                     (keyword (last %))))))

(defn- map-structs
  "Return a map of all `struct` definied in shader-str"
  [shader-str]
  (->> shader-str
       (re-seq (:struct/blocks glsl-regexps))
       (map (fn [m] [(nth m 1) (block-members (last m))]))
       (reduce #(apply assoc %1 %2) {})
  ))

(defn- map-uniforms
  "Return a map of uniform names and their types declared in a shader string,
   using the one-liner glsl syntax variations:

     uniform type name;
     uniform type name = default_value;
     layout_declaration uniform type name = default_value;
  "
  [shader-str]
  (let [structs (map-structs shader-str)]
    (->> shader-str
         (re-seq (:uniform/one-liners glsl-regexps))
         (mapv #(vector (last %)
                        (keyword (second %)))))))

(defn- map-inputs [shader-str] nil)
(defn- map-outputs [shader-str] nil)

(defn analyze-shader
  "Return a map of shader meta data"
  [shader-str]
  {:attribs (map-attributes shader-str)
   :structs    (map-structs shader-str)
   :uniforms   (map-uniforms shader-str)
   :input      (map-inputs shader-str)
   :output     (map-outputs shader-str)})

;;TODO
;; * Implement uniform Interface block
;;     storage_qualifier block_name
;;     {
;;       <define members here>
;;     } instance_name;
;;
;; * Input mapping
;; * Outpout mapping
