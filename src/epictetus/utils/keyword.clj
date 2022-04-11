(ns epictetus.utils.keyword)

(defn derivev
  ([tags parent]
   (doseq [tag tags]
     (derive tag parent)))
  ([h tags parent]
   (reduce  #(derive %1 %2 parent) h tags)))
