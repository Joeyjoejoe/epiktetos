(ns epictetus.utils.keyword)

(defn derivev
  ([tags parent]
   (doseq [tag tags]
     (derive tag parent)))
  ([h tags parent]
   (doseq [tag tags]
     (derive h tag parent))))
