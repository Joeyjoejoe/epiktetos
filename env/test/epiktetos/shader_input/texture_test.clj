(ns epiktetos.shader-input.texture-test
  (:require [clojure.test :as t]
            [epiktetos.shader-input.texture :as texture]))

(t/deftest validate-spec-test
  (t/testing "file sources default to sRGB, mips and flip"
    (let [spec (texture/validate-spec :skin {:file "textures/skin.png"})]
      (t/is (= :srgb8-alpha8 (:format spec)))
      (t/is (true? (:mips? spec)))
      (t/is (true? (:flip? spec)))))

  (t/testing "data sources default to their pixel format, no mips"
    (let [spec (texture/validate-spec :heights
                 {:data {:width 2 :height 2 :pixel-format :r32f
                         :pixels [0.0 1.0 2.0 3.0]}})]
      (t/is (= :r32f (:format spec)))
      (t/is (false? (:mips? spec)))))

  (t/testing "explicit options are kept"
    (t/is (= :rgba8 (:format (texture/validate-spec :raw
                               {:file "x.png" :format :rgba8})))))

  (t/testing "exactly one source is required"
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (texture/validate-spec :t {})))
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (texture/validate-spec :t {:file "x.png"
                                             :data {:width 1 :height 1
                                                    :pixel-format :r8
                                                    :pixels [0]}}))))

  (t/testing "invalid data throws"
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (texture/validate-spec :t
                     {:data {:width 2 :height 2 :pixel-format :r8
                             :pixels [0 0 0]}})))
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (texture/validate-spec :t
                     {:data {:width 1 :height 1 :pixel-format :hsl
                             :pixels [0]}}))))

  (t/testing "invalid format and swizzle throw"
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (texture/validate-spec :t {:file "x.png" :format :cmyk})))
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (texture/validate-spec :t {:file "x.png"
                                             :swizzle [:r :g :b]})))
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (texture/validate-spec :t {:file "x.png"
                                             :swizzle [:r :g :b :x]})))))

(t/deftest validate-sampler-options-test
  (t/testing "valid options pass"
    (t/is (nil? (texture/validate-sampler-options "uAlbedo" {})))
    (t/is (nil? (texture/validate-sampler-options "uAlbedo"
                  {:sampler/mag-filter :nearest
                   :sampler/min-filter :linear-mipmap-linear
                   :sampler/wrap       [:repeat :clamp-to-edge]
                   :sampler/anisotropy 8
                   :sampler/lod-bias   -0.5}))))

  (t/testing "invalid options throw"
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (texture/validate-sampler-options "u"
                     {:sampler/mag-filter :trilinear})))
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (texture/validate-sampler-options "u"
                     {:sampler/wrap :around})))
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (texture/validate-sampler-options "u"
                     {:sampler/anisotropy 32})))
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (texture/validate-sampler-options "u"
                     {:sampler/border-color [1 0 0]})))))
