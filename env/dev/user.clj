(ns user
  (:require [epiktetos.core :as core
                            :refer [reg-event reg-cofx inject-cofx reg-fx
                                    reg-p reg-input reg-texture reg-steps!
                                    dispatch render delete stop!]]
            [epiktetos.dev :as dev]))





(comment

  (core/stop!)

  (dev/inspect!)
  (dev/error-report)
  (dev/retry!)
  (dev/skip!)

  )
