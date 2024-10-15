(ns epiktetos.utils.benchmark)

(defmacro exec-time
  "Return the duration, in seconds, of executing forms.
   To change the unit, change the power :
    1e9 -> seconds
    1e6 -> milliseconds
  "
  ([& forms]
   `(let [start# (System/nanoTime)]
      ~@forms
      (double (- (System/nanoTime) start#)))))
