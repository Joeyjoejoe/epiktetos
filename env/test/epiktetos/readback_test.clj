(ns epiktetos.readback-test
  (:require [clojure.test :as t]
            [epiktetos.core :as core]
            [epiktetos.event :as event]
            [epiktetos.registrar :as registrar]
            [epiktetos.shader-input.buffer :as buffer]))

(defn- ex-of
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

(t/deftest read-input-cofx-test
  (try
    (core/install-core!)
    (swap! registrar/registry assoc-in
           [::registrar/opengl-registry :program-inputs "Blk"]
           {:varname "Blk" :resource :ssbo :buffer-id 7})
    (swap! registrar/registry assoc-in
           [::registrar/opengl-registry :program-inputs "Blk2"]
           {:varname "Blk2" :resource :ssbo :buffer-id 9})
    (swap! registrar/registry assoc-in
           [::registrar/opengl-registry :program-inputs "Cam"]
           {:varname "Cam" :resource :ubo :buffer-id 8})
    (let [reads   (atom [])
          marker  (java.nio.ByteBuffer/wrap (byte-array 4))
          marker2 (java.nio.ByteBuffer/wrap (byte-array 4))]
      (with-redefs [buffer/buffer-size (fn [_] 64)
                    buffer/read-block-bytes!
                    (fn [id offset length]
                      (swap! reads conj [id offset length])
                      (if (= id 9) marker2 marker))]
        (let [handler (event/get-handler :coeffects :read-input)]

          (t/testing "string form reads the whole buffer under the varname"
            (let [cofx (handler {} "Blk")]
              (t/is (= [[7 0 64]] @reads))
              (t/is (identical? marker (get-in cofx [:read-input "Blk"])))))

          (t/testing "region form and its defaults"
            (reset! reads [])
            (handler {} {:varname "Blk" :offset 16 :length 8})
            (handler {} {:varname "Blk" :offset 16})
            (handler {} {:varname "Blk" :length 8})
            (t/is (= [[7 16 8] [7 16 48] [7 0 8]] @reads)))

          (t/testing "injections accumulate by varname, last one wins"
            (let [cofx (-> {}
                           (handler "Blk")
                           (handler "Blk2")
                           (handler {:varname "Blk" :length 8}))]
              (t/is (= #{"Blk" "Blk2"} (set (keys (:read-input cofx)))))
              (t/is (identical? marker2 (get-in cofx [:read-input "Blk2"])))))

          (t/testing "unknown varname"
            (let [e (ex-of #(handler {} "Nope"))]
              (t/is (= "Nope" (:read-input (ex-data e))))))

          (t/testing "only SSBOs are readable"
            (let [e (ex-of #(handler {} "Cam"))]
              (t/is (= :ubo (:resource (ex-data e))))))

          (t/testing "unknown region keys are rejected with the allowed set"
            (let [e (ex-of #(handler {} {:varname "Blk" :range [0 8]}))]
              (t/is (contains? (:allowed (ex-data e)) :length))))

          (t/testing "regions are bounds-checked"
            (t/is (some? (ex-of #(handler {} {:varname "Blk" :offset -1}))))
            (t/is (some? (ex-of #(handler {} {:varname "Blk"
                                              :offset 60 :length 8}))))
            (let [e (ex-of #(handler {} {:varname "Blk" :length 0}))]
              (t/is (= 64 (:buffer-size (ex-data e))))))

          (t/testing "malformed values"
            (t/is (some? (ex-of #(handler {} 42))))
            (t/is (some? (ex-of #(handler {} {:offset 0}))))))))
    (finally
      (reset! event/queue clojure.lang.PersistentQueue/EMPTY)
      (swap! registrar/registry update-in
             [::registrar/opengl-registry :program-inputs]
             dissoc "Blk" "Blk2" "Cam"))))
