(ns epiktetos.opengl.shader-program
  (:require [clojure.java.io :as io]
            [epiktetos.registrar :as registrar]
            [epiktetos.opengl.shader :as shader]
            [epiktetos.shader-input.registration :as input]
            [epiktetos.opengl.shader-attribute :as attribute])
  (:import (org.lwjgl.opengl GL20 GL30 GL11 GL43 GL45)
           (org.lwjgl BufferUtils)))

(defn validate-declaration!
  "Validates a reg-p declaration from the event handler — the pure
  stage, where an error pauses recoverably (development mode): a
  :pipeline of known stages whose files are on the classpath, and
  well-formed :vertex-layout entries. GLSL compilation and linking
  stay effects-side.
  id       - keyword, program id
  prog-map - map, the reg-p DSL
  Returns nil."
  [id prog-map]
  (when-not (map? prog-map)
    (throw (ex-info "Program declaration must be a map"
                    {:reg-p/id    id
                     :reg-p/value prog-map})))
  (let [{:keys [pipeline vertex-layout]} prog-map]
    (when (empty? pipeline)
      (throw (ex-info "Program declaration requires a :pipeline of [stage path] entries"
                      {:reg-p/id id})))
    (doseq [[stage path] pipeline]
      (when-not (contains? shader/STAGES stage)
        (throw (ex-info (str "Unknown shader stage " stage)
                        {:reg-p/id id
                         :stage    stage
                         :allowed  (set (keys shader/STAGES))})))
      (when-not (io/resource path)
        (throw (ex-info (str "Shader file not found on the classpath: " path)
                        {:reg-p/id id
                         :stage    stage
                         :path     path}))))
    (doseq [vertex-buffer vertex-layout]
      (when-not (and (map? vertex-buffer)
                     (sequential? (:layout vertex-buffer))
                     (seq (:layout vertex-buffer))
                     (every? string? (:layout vertex-buffer))
                     (some? (:handler vertex-buffer)))
        (throw (ex-info "Each :vertex-layout entry requires a :layout of GLSL attribute names and a :handler"
                        {:reg-p/id       id
                         :vertex-buffer  vertex-buffer})))))
  nil)

(defn link!
  "Create a shader program from program map DSL"
  [program-map]
  (let [{:keys [pipeline vertex-layout]} program-map
        shader-ids (map shader/interpret pipeline)
        id (GL20/glCreateProgram)]

    (doseq [shader-id shader-ids]
      (GL20/glAttachShader id shader-id))

    (GL20/glLinkProgram id)

    (when (= 0 (GL20/glGetProgrami id GL20/GL_LINK_STATUS))
      (-> (str "Error linking shader to program " (GL20/glGetProgramInfoLog id 1024))
          Exception.
          throw))

    (doseq [shader-id shader-ids]
      (GL20/glDeleteShader shader-id))

    (assoc program-map :id id)))

(defn link-and-introspect!
  "Compiles, links and introspects a program map: GLSL stages of
   :pipeline compiled and linked, then UBOs, SSBOs and default-block
   uniforms introspected and wired. Graphics-only setup (VAO,
   vertex-format) and registration stay with the caller.
   prog-k   - keyword, program id in the registry
   prog-map - map, the declaration DSL with a :pipeline
   Returns prog-map + :id and :inputs."
  [prog-k prog-map]
  (-> prog-map
      link!
      input/setup-ubos!
      input/setup-ssbos!
      (input/setup-uniforms! prog-k)))

(defn setup!
  [prog-k prog-map]
  (let [old-prog        (registrar/get-program prog-k)
        layout-changed? (and old-prog
                             (not= (:vertex-layout old-prog)
                                   (:vertex-layout prog-map)))
        program (-> (link-and-introspect! prog-k prog-map)
                    attribute/setup!
                    (assoc :dirty layout-changed?))]

    (when old-prog
      (GL20/glDeleteProgram (:id old-prog)))

    (registrar/register-program prog-k program)

    (when-let [old-vao-id (:vao-id old-prog)]
      (when-not (= old-vao-id (:vao-id program))
        (attribute/delete-vao! old-vao-id)))

    program))

(defn delete-programs!
  "Delete the GL program of every registered shader program.
   registry - map, the registry value
   Returns nil."
  [registry]
  (doseq [{:keys [id]} (vals (get-in registry [::registrar/opengl-registry :programs]))]
    (GL20/glDeleteProgram id))
  nil)
