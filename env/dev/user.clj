(ns user
  (:require [integrant.core :as ig]
            [integrant.repl :refer [clear go halt prep init reset reset-all]]))

(integrant.repl/set-prep! (constantly {}))
