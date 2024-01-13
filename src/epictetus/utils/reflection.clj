(ns epictetus.utils.reflection)

(defn arity-eql?
  [f n]
  (let [arity (-> f class .getDeclaredMethods first .getParameterTypes alength)]
    (= arity n)))
