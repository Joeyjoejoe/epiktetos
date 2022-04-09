(require 'clojure.pprint)
(require 'clojure.edn)

(def JVM-OPTS
  {:common   []
   :macosx   ["-XstartOnFirstThread" "-Djava.awt.headless=true"]
   :linux    []
   :windows  []})

(defn jvm-opts
  "Return a complete vector of jvm-opts for the current os."
  [] (let [os (keyword (clojure.string/lower-case (System/getProperty "os.name")))]
       (vec (set (concat (get JVM-OPTS :common)
                         (get JVM-OPTS os))))))

(def LWJGL_NS "org.lwjgl")

;; Edit this to add/remove packages.
(def LWJGL_MODULES ["lwjgl"
                    ;; "lwjgl-assimp"
                    ;; "lwjgl-bgfx"
                    ;; "lwjgl-egl"
                    "lwjgl-glfw"
                    ;; "lwjgl-jawt"
                    ;; "lwjgl-jemalloc"
                    ;; "lwjgl-lmdb"
                    ;; "lwjgl-lz4"
                    ;; "lwjgl-nanovg"
                    ;; "lwjgl-nfd"
                    ;; "lwjgl-nuklear"
                    ;; "lwjgl-odbc"
                    ;; "lwjgl-openal"
                    ;; "lwjgl-opencl"
                    "lwjgl-opengl"
                    ;; "lwjgl-opengles"
                    ;; "lwjgl-openvr"
                    ;; "lwjgl-par"
                    ;; "lwjgl-remotery"
                    ;; "lwjgl-rpmalloc"
                    ;; "lwjgl-sse"
                    "lwjgl-stb"
                    ;; "lwjgl-tinyexr"
                    ;; "lwjgl-tinyfd"
                    ;; "lwjgl-tootle"
                    ;; "lwjgl-vulkan"
                    ;; "lwjgl-xxhash"
                    ;; "lwjgl-yoga"
                    ;; "lwjgl-zstd"
                    ])

;; It's safe to just include all native dependencies, but you might
;; save some space if you know you don't need some platform.
(def LWJGL_PLATFORMS ["linux" "macos" "windows"])

;; These packages don't have any associated native ones.
(def no-natives? #{"lwjgl-egl" "lwjgl-jawt" "lwjgl-odbc"
                   "lwjgl-opencl" "lwjgl-vulkan"})

(defn get-classifiers [m]
  (cond (no-natives? m)
        []
        :else (mapv #(str "natives-" %) LWJGL_PLATFORMS)))

(defn set-module [module version]
  (let [lib-name (symbol LWJGL_NS module)
        mvn      {:mvn/version version}
        classifiers (get-classifiers module)]
    (cond (empty? classifiers)
          {lib-name mvn}
          :else (into {lib-name mvn} (mapv #(hash-map (symbol (str lib-name "$" %)) (merge mvn {:native-prefix ""})) classifiers)))))


(cond
  (nil? (first *command-line-args*)) (println "You must specify a version number")
  :else (let [version      (first *command-line-args*)
              system       (System/getProperty "os.name")
              lwjgl-deps   (into {} (mapv #(set-module % version) LWJGL_MODULES))]
          (binding [*print-namespace-maps* false]
            (println (str lwjgl-deps)))))
