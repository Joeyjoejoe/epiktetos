(ns epiktetos.utils.hash
  (:import (java.security MessageDigest)))

(defn sha256
  "Convert a clojure data structure to a string use sha-256 algorithm.
  Using anonymous function forms in the data structure will generate
  a different string each time. Use function symbols to provide this
  behavior"
  [data]
  (let [string (pr-str data)
        digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes string "UTF-8"))]
    (apply str (map (partial format "%02x") digest))))
