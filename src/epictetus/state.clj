(ns epictetus.state
  (:require [clojure.java.io :as io]
            [integrant.core :as ig]))

(def -game (atom {}))
(def -engine (atom {}))
