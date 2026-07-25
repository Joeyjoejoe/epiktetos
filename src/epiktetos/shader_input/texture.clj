(ns epiktetos.shader-input.texture
  (:require [clojure.java.io :as io]
            [epiktetos.opengl.glsl :as glsl]
            [epiktetos.registrar :as registrar])
  (:import (java.io ByteArrayOutputStream)
           (org.lwjgl BufferUtils)
           (org.lwjgl.stb STBImage)
           (org.lwjgl.opengl GL11 GL12 GL13 GL14 GL20 GL21 GL30 GL33 GL41
                             GL44 GL45 GL46)))

(defonce ^:private INTERNAL-FORMATS
  {:rgba8        GL11/GL_RGBA8
   :srgb8-alpha8 GL21/GL_SRGB8_ALPHA8
   :r8           GL30/GL_R8
   :rg8          GL30/GL_RG8
   :rgba16f      GL30/GL_RGBA16F
   :rgba32f     GL30/GL_RGBA32F
   :r32f         GL30/GL_R32F
   :r32i         GL30/GL_R32I})

(defonce ^:private PIXEL-FORMATS
  {:rgba8   {:gl-format GL11/GL_RGBA :components 4 :internal :rgba8   :scalars :byte}
   :r8      {:gl-format GL11/GL_RED  :components 1 :internal :r8      :scalars :byte}
   :rg8     {:gl-format GL30/GL_RG   :components 2 :internal :rg8     :scalars :byte}
   :r32f    {:gl-format GL11/GL_RED  :components 1 :internal :r32f    :scalars :float}
   :rgba32f {:gl-format GL11/GL_RGBA :components 4 :internal :rgba32f :scalars :float}})

(defonce ^:private SWIZZLES
  {:r GL11/GL_RED :g GL11/GL_GREEN :b GL11/GL_BLUE :a GL11/GL_ALPHA
   :zero GL11/GL_ZERO :one GL11/GL_ONE})

(defonce ^:private MAG-FILTERS
  {:nearest GL11/GL_NEAREST
   :linear  GL11/GL_LINEAR})

(defonce ^:private MIN-FILTERS
  {:nearest                GL11/GL_NEAREST
   :linear                 GL11/GL_LINEAR
   :nearest-mipmap-nearest GL11/GL_NEAREST_MIPMAP_NEAREST
   :linear-mipmap-nearest  GL11/GL_LINEAR_MIPMAP_NEAREST
   :nearest-mipmap-linear  GL11/GL_NEAREST_MIPMAP_LINEAR
   :linear-mipmap-linear   GL11/GL_LINEAR_MIPMAP_LINEAR})

(defonce ^:private WRAP-MODES
  {:repeat          GL11/GL_REPEAT
   :mirrored-repeat GL14/GL_MIRRORED_REPEAT
   :clamp-to-edge   GL12/GL_CLAMP_TO_EDGE
   :clamp-to-border GL13/GL_CLAMP_TO_BORDER
   :mirror-clamp    GL44/GL_MIRROR_CLAMP_TO_EDGE})

(defonce ^:private SAMPLER-OPTION-KEYS
  #{:sampler/mag-filter :sampler/min-filter :sampler/wrap
    :sampler/border-color :sampler/anisotropy
    :sampler/min-lod :sampler/max-lod :sampler/lod-bias})

(defn sampler-kind
  "Returns the supported sampler kind of an introspected uniform, or
   nil when the uniform is not a supported sampler.
   uniform - map, introspected uniform properties with :type-enum"
  [uniform]
  (glsl/SAMPLER-TYPE (:type-enum uniform)))

(defn validate-spec
  "Validates and normalizes a reg-texture spec: exactly one source,
   known formats, pixel count matching the dimensions, defaults
   filled (sRGB, mips and flip for files; the pixel format's internal
   format and no mips for data). Throws ex-info on the first problem.
   id   - keyword, texture id
   spec - map, reg-texture spec
   Returns the normalized spec."
  [id spec]
  (let [{:keys [file data]} spec
        fail! (fn [cause data]
                (throw (ex-info "Invalid texture spec"
                                (merge {:texture id :cause cause} data))))]
    (when (= (some? file) (some? data))
      (fail! "Exactly one of :file or :data is required"
             {:file file :data (some? data)}))
    (when-let [swizzle (:swizzle spec)]
      (when-not (and (= 4 (count swizzle)) (every? SWIZZLES swizzle))
        (fail! "Invalid :swizzle" {:swizzle swizzle
                                   :allowed (set (keys SWIZZLES))})))
    (when-let [format (:format spec)]
      (when-not (INTERNAL-FORMATS format)
        (fail! "Unknown :format" {:format format
                                  :allowed (set (keys INTERNAL-FORMATS))})))
    (if file
      (merge {:format :srgb8-alpha8 :mips? true :flip? true} spec)
      (let [{:keys [width height pixel-format pixels]} data
            pf (PIXEL-FORMATS pixel-format)]
        (when-not pf
          (fail! "Unknown :pixel-format" {:pixel-format pixel-format
                                         :allowed (set (keys PIXEL-FORMATS))}))
        (when-not (and (pos-int? width) (pos-int? height))
          (fail! "Invalid :data dimensions" {:width width :height height}))
        (when-not (= (* width height (:components pf)) (count pixels))
          (fail! "Pixel count does not match dimensions"
                 {:expected (* width height (:components pf))
                  :actual   (count pixels)}))
        (merge {:format (:internal pf) :mips? false} spec)))))

(defn validate-sampler-options
  "Validates the :sampler/* options of a texture input registration.
   Throws ex-info on the first unknown value.
   varname - string, input variable name
   options - map, reg-input options
   Returns nil."
  [varname options]
  (let [fail! (fn [option value allowed]
                (throw (ex-info "Invalid sampler option"
                                {:varname varname :option option
                                 :value value :allowed allowed})))
        {:sampler/keys [mag-filter min-filter wrap border-color
                        anisotropy min-lod max-lod lod-bias]} options]
    (when (and mag-filter (not (MAG-FILTERS mag-filter)))
      (fail! :sampler/mag-filter mag-filter (set (keys MAG-FILTERS))))
    (when (and min-filter (not (MIN-FILTERS min-filter)))
      (fail! :sampler/min-filter min-filter (set (keys MIN-FILTERS))))
    (when wrap
      (let [modes (if (keyword? wrap) [wrap wrap] wrap)]
        (when-not (and (= 2 (count modes)) (every? WRAP-MODES modes))
          (fail! :sampler/wrap wrap (set (keys WRAP-MODES))))))
    (when (and border-color
               (not (and (= 4 (count border-color))
                         (every? number? border-color))))
      (fail! :sampler/border-color border-color "[r g b a] numbers"))
    (when (and anisotropy (not (<= 1 anisotropy 16)))
      (fail! :sampler/anisotropy anisotropy "1 to 16"))
    (doseq [[option value] {:sampler/min-lod  min-lod
                            :sampler/max-lod  max-lod
                            :sampler/lod-bias lod-bias}]
      (when (and value (not (number? value)))
        (fail! option value "a number")))
    nil))

(defn- mip-levels
  "Full mipmap chain length for a texture size.
   width, height - pos ints
   Returns a pos int."
  [width height]
  (inc (int (/ (Math/log (max width height)) (Math/log 2)))))

(defn- resource-bytes
  "Reads a classpath resource into a direct ByteBuffer.
   path - string, classpath-relative path
   Returns a flipped ByteBuffer, or nil when the resource is missing."
  [path]
  (when-let [resource (io/resource path)]
    (let [out (ByteArrayOutputStream.)]
      (with-open [in (io/input-stream resource)]
        (io/copy in out))
      (let [bytes (.toByteArray out)]
        (doto (BufferUtils/createByteBuffer (alength bytes))
          (.put bytes)
          .flip)))))

(defn- file-pixels
  "Decodes an image resource into RGBA8 pixels through STB.
   spec - normalized reg-texture spec with :file and :flip?
   Returns {:width :height :gl-format :gl-type :pixels :free!}."
  [{:keys [file flip?]}]
  (let [encoded (or (resource-bytes file)
                    (throw (ex-info "Texture file not found" {:file file})))
        width   (BufferUtils/createIntBuffer 1)
        height  (BufferUtils/createIntBuffer 1)
        color   (BufferUtils/createIntBuffer 1)]
    (STBImage/stbi_set_flip_vertically_on_load (boolean flip?))
    (if-let [pixels (STBImage/stbi_load_from_memory
                      ^java.nio.ByteBuffer encoded width height color 4)]
      {:width     (.get width 0)
       :height    (.get height 0)
       :gl-format GL11/GL_RGBA
       :gl-type   GL11/GL_UNSIGNED_BYTE
       :pixels    pixels
       :free!     #(STBImage/stbi_image_free pixels)}
      (throw (ex-info "Texture decoding failed"
                      {:file file
                       :cause (STBImage/stbi_failure_reason)})))))

(defn- data-pixels
  "Packs a :data source into an upload buffer.
   spec - normalized reg-texture spec with :data
   Returns {:width :height :gl-format :gl-type :pixels :free!}."
  [{:keys [data]}]
  (let [{:keys [width height pixel-format pixels]} data
        {:keys [gl-format scalars]} (PIXEL-FORMATS pixel-format)
        buffer (case scalars
                 :byte  (let [b (BufferUtils/createByteBuffer (count pixels))]
                          (doseq [p pixels] (.put b (unchecked-byte (long p))))
                          (.flip b))
                 :float (let [b (BufferUtils/createFloatBuffer (count pixels))]
                          (doseq [p pixels] (.put b (float p)))
                          (.flip b)))]
    {:width     width
     :height    height
     :gl-format gl-format
     :gl-type   (case scalars :byte GL11/GL_UNSIGNED_BYTE :float GL11/GL_FLOAT)
     :pixels    buffer
     :free!     (fn [])}))

(defn- create-texture-2d!
  "Creates an immutable 2D texture and uploads its pixels.
   spec   - normalized reg-texture spec
   source - map from file-pixels or data-pixels
   Returns the GL texture id."
  [{:keys [format mips? swizzle]} {:keys [width height gl-format gl-type pixels free!]}]
  (let [texture (GL45/glCreateTextures GL11/GL_TEXTURE_2D)
        levels  (if mips? (mip-levels width height) 1)]
    (GL45/glTextureStorage2D texture levels (INTERNAL-FORMATS format) width height)
    (if (instance? java.nio.FloatBuffer pixels)
      (GL45/glTextureSubImage2D texture 0 0 0 width height gl-format gl-type
                                ^java.nio.FloatBuffer pixels)
      (GL45/glTextureSubImage2D texture 0 0 0 width height gl-format gl-type
                                ^java.nio.ByteBuffer pixels))
    (when mips?
      (GL45/glGenerateTextureMipmap texture))
    (when (and swizzle (not= swizzle [:r :g :b :a]))
      (GL45/glTextureParameteriv texture GL33/GL_TEXTURE_SWIZZLE_RGBA
                                 (int-array (map SWIZZLES swizzle))))
    (free!)
    texture))

(def ^:private FALLBACK
  "Built-in fallback texture, bound whenever a texture input cannot be
   resolved: an unmistakable magenta and black checkerboard."
  (delay
    (let [size   8
          pixels (BufferUtils/createByteBuffer (* size size 4))]
      (doseq [y (range size)
              x (range size)]
        (let [magenta? (even? (+ x y))]
          (.put pixels (unchecked-byte (if magenta? 255 0)))
          (.put pixels (unchecked-byte 0))
          (.put pixels (unchecked-byte (if magenta? 255 0)))
          (.put pixels (unchecked-byte 255))))
      (.flip pixels)
      (let [texture (GL45/glCreateTextures GL11/GL_TEXTURE_2D)]
        (GL45/glTextureStorage2D texture 1 GL11/GL_RGBA8 size size)
        (GL45/glTextureSubImage2D texture 0 0 0 size size
                                  GL11/GL_RGBA GL11/GL_UNSIGNED_BYTE
                                  ^java.nio.ByteBuffer pixels)
        texture))))

(defonce ^:private sampler-cache (atom {}))
(defonce ^:private warned-missing (atom #{}))

(defn- sampler-object!
  "Returns the GL sampler object for a texture input's :sampler/*
   options, creating and caching it on first use. The min filter
   defaults to trilinear when the bound texture has mips, linear
   otherwise.
   options - map, reg-input options
   mips?   - boolean, whether the bound texture carries mipmaps"
  [options mips?]
  (let [min-filter (or (:sampler/min-filter options)
                       (if mips? :linear-mipmap-linear :linear))
        key        (assoc (select-keys options SAMPLER-OPTION-KEYS)
                          :sampler/min-filter min-filter)]
    (or (@sampler-cache key)
        (let [sampler (GL33/glGenSamplers)
              {:sampler/keys [mag-filter wrap border-color anisotropy
                              min-lod max-lod lod-bias]} key
              [wrap-s wrap-t] (if (keyword? wrap) [wrap wrap] wrap)]
          (GL33/glSamplerParameteri sampler GL11/GL_TEXTURE_MIN_FILTER
                                    (MIN-FILTERS min-filter))
          (GL33/glSamplerParameteri sampler GL11/GL_TEXTURE_MAG_FILTER
                                    (MAG-FILTERS (or mag-filter :linear)))
          (GL33/glSamplerParameteri sampler GL11/GL_TEXTURE_WRAP_S
                                    (WRAP-MODES (or wrap-s :repeat)))
          (GL33/glSamplerParameteri sampler GL11/GL_TEXTURE_WRAP_T
                                    (WRAP-MODES (or wrap-t :repeat)))
          (when border-color
            (GL33/glSamplerParameterfv sampler GL11/GL_TEXTURE_BORDER_COLOR
                                       (float-array border-color)))
          (when anisotropy
            (GL33/glSamplerParameterf sampler GL46/GL_TEXTURE_MAX_ANISOTROPY
                                      (float anisotropy)))
          (when min-lod
            (GL33/glSamplerParameterf sampler GL12/GL_TEXTURE_MIN_LOD (float min-lod)))
          (when max-lod
            (GL33/glSamplerParameterf sampler GL12/GL_TEXTURE_MAX_LOD (float max-lod)))
          (when lod-bias
            (GL33/glSamplerParameterf sampler GL14/GL_TEXTURE_LOD_BIAS (float lod-bias)))
          (swap! sampler-cache assoc key sampler)
          sampler))))

(defn- allocate-unit!
  "Allocates a free texture unit for a new sampler input varname.
   Throws when every unit is taken.
   Returns an int."
  []
  (let [used (->> (get-in @registrar/registry
                          [::registrar/opengl-registry :program-inputs])
                  vals
                  (filter #(= :texture (:resource %)))
                  (keep :unit)
                  set)
        max-units (GL11/glGetInteger GL20/GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS)]
    (or (first (remove used (range max-units)))
        (throw (ex-info "No more texture units available"
                        {:max max-units})))))

(defn setup-sampler-uniform!
  "Registers the program side of a sampler uniform: allocates (or
   reuses) the varname's texture unit, records the program fan-out
   target, writes the unit index into the program's uniform, and
   binds the fallback texture on a fresh unit. Throws when the name
   is already registered as another input kind.
   program-id - int, GL program id
   program-k  - keyword, program id in the registry
   uniform    - map, introspected sampler uniform
   Returns nil."
  [program-id program-k {:keys [varname location] :as uniform}]
  (let [existing (registrar/lookup-program-input varname)]
    (when (and existing (not= :texture (:resource existing)))
      (throw (ex-info "Sampler name already registered as another input"
                      {:varname varname
                       :resource (:resource existing)
                       :in-program program-id})))
    (let [unit (or (:unit existing) (allocate-unit!))]
      (registrar/register-program-texture! varname program-k
                                           {:unit unit
                                            :sampler-type (:glsl-name (sampler-kind uniform))}
                                           {:program-id program-id
                                            :location   location})
      (GL41/glProgramUniform1i program-id location unit)
      (when-not existing
        (GL45/glBindTextureUnit unit @FALLBACK))
      (when-not (registrar/lookup-input varname)
        (println "[epiktetos] No input registered for sampler" varname))))
  nil)

(defn- bind-texture!
  "Binds the texture registered under id (or the fallback
   checkerboard when the id resolves to nothing, with a one-shot
   warning) and its sampler object to the input's unit.
   program-input - map, registered texture input with :unit
   input         - map, input definition carrying :sampler/* options
   id            - keyword, texture id returned by the handler
   Returns nil."
  [{:keys [unit]} input id]
  (if-let [{:keys [texture-id mips?]} (registrar/lookup-texture id)]
    (do (GL45/glBindTextureUnit unit texture-id)
        (GL33/glBindSampler unit (sampler-object! input mips?)))
    (do (GL45/glBindTextureUnit unit @FALLBACK)
        (GL33/glBindSampler unit (sampler-object! input false))
        (when-not (@warned-missing [(:varname input) id])
          (println "[epiktetos] No texture registered under" id
                   "for sampler" (:varname input) "- fallback bound")
          (swap! warned-missing conj [(:varname input) id]))))
  nil)

(defn update-texture-input!
  "Executes one texture input handler and binds the texture it names
   to the input's unit. The bind is skipped when the returned id
   equals the last bound one; registering a texture drops the cache
   of the inputs waiting for its id, which heals at the next step
   execution.
   db            - map, application state of the current frame
   program-input - map, registered texture input
   input         - map, input definition with :handler
   step-value    - the current step value, passed to the handler
   Returns nil."
  [db program-input input step-value]
  (let [{:keys [varname handler]} input
        value (handler db step-value)
        prev  (get-in @registrar/render-state
                      [::registrar/input-values varname])]
    (when-not (= prev value)
      (when-not (keyword? value)
        (throw (ex-info "Texture input handler must return a texture id"
                        {:varname varname :value value})))
      (bind-texture! program-input input value)
      (swap! registrar/render-state
             assoc-in [::registrar/input-values varname] value))
    nil))

(defn register-texture!
  "Validates a reg-texture spec, creates and uploads the GL texture,
   and registers it under id. Replacing an id deletes the previous GL
   texture and drops the binding cache of the inputs bound to (or
   waiting for) the id, so they pick the new content up at the next
   step execution.
   id   - keyword, texture id
   spec - map, reg-texture spec (see texture-spec.md)
   Returns nil."
  [id spec]
  (let [spec       (validate-spec id spec)
        source     (if (:file spec) (file-pixels spec) (data-pixels spec))
        texture-id (create-texture-2d! spec source)]
    (when-let [previous (registrar/lookup-texture id)]
      (GL11/glDeleteTextures (int (:texture-id previous))))
    (registrar/register-texture! id {:texture-id texture-id
                                     :target     :2d
                                     :width      (:width source)
                                     :height     (:height source)
                                     :format     (:format spec)
                                     :mips?      (boolean (:mips? spec))})
    (swap! registrar/render-state update ::registrar/input-values
           (fn [values]
             (into {} (remove (fn [[_ v]] (= v id))) values)))
    (swap! warned-missing
           (fn [warned] (set (remove (fn [[_ tid]] (= tid id)) warned))))
    nil))
