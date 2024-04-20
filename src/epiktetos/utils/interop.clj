(ns epiktetos.utils.interop)

(defmacro m->f
  "JAVA method to function"
  [meth arity]
  (let [args (take arity (map symbol [:a :b :c :d
                                      :e :f :g :h
                                      :i :j :k :l]))
        signature (vec args)
        body (conj args meth)]
    `(fn ~signature
       ~body)))
