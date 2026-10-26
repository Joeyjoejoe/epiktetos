(ns epiktetos.window.settings
  "Window settings (ai-spec/specs/window-spec.md): the keys of the
  :glfw/window configuration. Creation settings (:hint.* keys) are
  fixed when the window is created; runtime settings (every other key)
  are applied by one applicator, at startup from the configuration and
  later by the window effect, so a key accepted by one is accepted by
  the other.

  A window is the :glfw/window system map, {:id long :state atom}; its
  state atom holds {:settings map :windowed map}: the current runtime
  settings and the windowed geometry {:x :y :width :height} restored
  when leaving full screen."
  (:require [epiktetos.lang.glfw :as glfw])
  (:import (org.lwjgl.glfw GLFW GLFWVidMode)
           (org.lwjgl.system MemoryUtil)))

(def APPLICATION-ORDER
  "Runtime settings in application order: the size follows the
  display, so that it lands on the windowed geometry whichever side the
  window ends on, and the window is shown last."
  [:title :window/resizable :display :width :height
   :mode/cursor :mode/raw-mouse-motion :mode/sticky-keys
   :mode/sticky-mouse-buttons :mode/lock-key :window/visible])

(def SIZE-SETTINGS
  "Runtime settings reflected in [:core/window] by the size callback,
  with the actual size, rather than by the applicator."
  #{:width :height})

(defonce raw-motion-warned? (atom false))

(defn creation-setting?
  "True when k is a creation setting, fixed at window creation.
  k - keyword
  Returns boolean"
  [k]
  (isa? glfw/GRAMMAR k :window/hint))

(defn runtime-setting?
  "True when k is a runtime setting, changeable after creation.
  k - keyword
  Returns boolean"
  [k]
  (contains? glfw/RUNTIME-SETTINGS k))

(defn unknown-settings
  "List the keys of a settings map that are neither creation nor
  runtime settings.
  settings - map, setting key -> value
  Returns a vector of keywords, empty when every key is known"
  [settings]
  (vec (remove #(or (creation-setting? %) (runtime-setting? %))
               (keys settings))))

(defn runtime-settings
  "Keep the runtime settings of a settings map.
  settings - map, setting key -> value
  Returns a map"
  [settings]
  (select-keys settings (filter runtime-setting? (keys settings))))

(defn validate-window-settings
  "Check a map of settings given to the window effect, as a whole:
  every key must be a runtime setting with a valid value.
  settings - map, runtime setting key -> value
  Returns settings, or throws an ex-info naming the first defect"
  [settings]
  (when-not (map? settings)
    (throw (ex-info (str "window takes a map of settings, got "
                         (pr-str settings))
                    {:settings settings})))
  (when-let [k (first (filter creation-setting? (keys settings)))]
    (throw (ex-info (str k " is fixed at window creation — change it in"
                         " the configuration and restart the engine")
                    {:settings settings :setting k})))
  (when-let [unknown (seq (unknown-settings settings))]
    (throw (ex-info (str "Unknown window setting(s) " (pr-str (vec unknown))
                         ", runtime settings are " (pr-str APPLICATION-ORDER))
                    {:settings settings :unknown (vec unknown)})))
  (when-let [[k v] (first (remove (fn [[k v]]
                                    ((get-in glfw/RUNTIME-SETTINGS [k :valid?]) v))
                                  settings))]
    (throw (ex-info (str "Invalid value " (pr-str v) " for " k ", expected "
                         (get-in glfw/RUNTIME-SETTINGS [k :expected]))
                    {:settings settings :setting k :value v})))
  settings)

(defn input-mode!
  "Set a GLFW input mode of the window.
  window-id - long, GLFW window handle
  property  - keyword, an :input/mode of the GLFW grammar
  value     - keyword or boolean, translated by the GLFW dictionary
  Returns nil"
  [window-id property value]
  (GLFW/glfwSetInputMode (long window-id)
                         (int (get glfw/DICTIONARY property))
                         (int (get glfw/DICTIONARY value)))
  nil)

(defn full-screen?
  "True when the window is attached to a monitor.
  window-id - long, GLFW window handle
  Returns boolean"
  [window-id]
  (not= MemoryUtil/NULL (GLFW/glfwGetWindowMonitor (long window-id))))

(defn get-geometry
  "Query the position and size of the window.
  window-id - long, GLFW window handle
  Returns {:x int :y int :width int :height int}"
  [window-id]
  (let [x      (int-array 1)
        y      (int-array 1)
        width  (int-array 1)
        height (int-array 1)]
    (GLFW/glfwGetWindowPos (long window-id) x y)
    (GLFW/glfwGetWindowSize (long window-id) width height)
    {:x (aget x 0) :y (aget y 0) :width (aget width 0) :height (aget height 0)}))

(defn centered-geometry
  "Geometry of a window of the given size centered on the primary
  monitor.
  width  - int, points
  height - int, points
  Returns {:x int :y int :width int :height int}"
  [width height]
  (let [^GLFWVidMode video (GLFW/glfwGetVideoMode (GLFW/glfwGetPrimaryMonitor))]
    {:x      (quot (- (.width video) width) 2)
     :y      (quot (- (.height video) height) 2)
     :width  width
     :height height}))

(defn- save-windowed-geometry!
  "Save the windowed geometry before entering full screen, when the
  window has been shown; a hidden window keeps its initial geometry.
  window - map, the :glfw/window system
  Returns nil"
  [{:keys [id state]}]
  (when (= GLFW/GLFW_TRUE (GLFW/glfwGetWindowAttrib (long id) GLFW/GLFW_VISIBLE))
    (swap! state assoc :windowed (get-geometry id)))
  nil)

(defn- apply-display!
  "Attach the window to the primary monitor at its current video mode,
  or detach it to its saved windowed geometry. A no-op when the window
  is already on the requested side.
  window  - map, the :glfw/window system
  display - keyword, :windowed, :fullscreen or :fullscreen/windowed
  Returns nil"
  [{:keys [id state] :as window} display]
  (if (= :windowed display)
    (when (full-screen? id)
      (let [{:keys [x y width height]} (:windowed @state)]
        (GLFW/glfwSetWindowMonitor (long id) MemoryUtil/NULL
                                   (int x) (int y) (int width) (int height)
                                   GLFW/GLFW_DONT_CARE)))
    (when-not (full-screen? id)
      (save-windowed-geometry! window)
      (let [monitor            (GLFW/glfwGetPrimaryMonitor)
            ^GLFWVidMode video (GLFW/glfwGetVideoMode monitor)]
        (GLFW/glfwSetWindowMonitor (long id) monitor 0 0
                                   (.width video) (.height video)
                                   (.refreshRate video)))))
  nil)

(defn- apply-size!
  "Set the windowed size: immediately when windowed, on return to
  windowed when in full screen.
  window - map, the :glfw/window system
  size   - map, {:width int :height int}, either key optional
  Returns nil"
  [{:keys [id state]} size]
  (if (full-screen? id)
    (swap! state update :windowed merge size)
    (let [current (get-geometry id)
          {:keys [width height]} (merge current size)]
      (GLFW/glfwSetWindowSize (long id) (int width) (int height))))
  nil)

(defn- apply-raw-mouse-motion!
  "Enable or disable raw mouse motion, when the platform supports it;
  otherwise warn once per session.
  window-id - long, GLFW window handle
  enabled?  - boolean
  Returns boolean, the effective value"
  [window-id enabled?]
  (cond
    (GLFW/glfwRawMouseMotionSupported)
    (do (input-mode! window-id :mode/raw-mouse-motion enabled?)
        enabled?)

    enabled?
    (do (when (compare-and-set! raw-motion-warned? false true)
          (println "[epiktetos] :mode/raw-mouse-motion is not supported on"
                   "this platform, ignored"))
        false)

    :else false))

(defn- apply-setting!
  "Apply one runtime setting other than the size.
  window - map, the :glfw/window system
  k      - keyword, runtime setting
  v      - its value
  Returns the effective value"
  [{:keys [id] :as window} k v]
  (case k
    :title                 (do (GLFW/glfwSetWindowTitle (long id) ^CharSequence v) v)
    :window/resizable      (do (GLFW/glfwSetWindowAttrib (long id) GLFW/GLFW_RESIZABLE
                                                         (int (get glfw/DICTIONARY v)))
                               v)
    :window/visible        (do (if v
                                 (GLFW/glfwShowWindow (long id))
                                 (GLFW/glfwHideWindow (long id)))
                               v)
    :display               (do (apply-display! window v) v)
    :mode/raw-mouse-motion (apply-raw-mouse-motion! id v)
    (do (input-mode! id k v) v)))

(defn apply-window-settings!
  "Apply runtime settings to the window in APPLICATION-ORDER. A setting
  equal to the current one is skipped, except the size, always applied
  since the window may have been resized by hand. Applied after the
  display, the size is set on the window when it ends windowed, and
  saved for the return to windowed when it ends in full screen.
  window   - map, the :glfw/window system
  settings - map, runtime setting key -> value, already validated
  Returns the map of applied settings with their effective values, to
  reflect in [:core/window], size settings excepted"
  [{:keys [state] :as window} settings]
  (let [current (:settings @state)
        size    (select-keys settings SIZE-SETTINGS)
        applied (reduce (fn [applied k]
                          (cond
                            (= :width k)
                            (do (when (seq size) (apply-size! window size))
                                applied)

                            (or (contains? SIZE-SETTINGS k)
                                (not (contains? settings k))
                                (= (get settings k) (get current k)))
                            applied

                            :else
                            (assoc applied k (apply-setting! window k (get settings k)))))
                        {}
                        APPLICATION-ORDER)]
    (swap! state update :settings merge applied size)
    applied))

(defn current-settings
  "The current runtime settings of the window, size settings excepted,
  as reflected in [:core/window].
  window - map, the :glfw/window system, or nil
  Returns a map, empty when no window state exists"
  [window]
  (if-let [state (:state window)]
    (apply dissoc (:settings @state) SIZE-SETTINGS)
    {}))

(defn release-cursor!
  "Give the pointer back to the user: set :mode/cursor to
  :cursor/normal, unless it is already.
  window - map, the :glfw/window system, or nil
  Returns the applied settings map to reflect, or nil when nothing
  changed or no window state exists"
  [window]
  (when-let [state (:state window)]
    (when-not (= :cursor/normal (get-in @state [:settings :mode/cursor]))
      (apply-window-settings! window {:mode/cursor :cursor/normal}))))

(defn create-window-state
  "Build the state atom of a new window, before any runtime setting is
  applied: no current settings, the windowed geometry centered at the
  configured size.
  width  - int, points
  height - int, points
  Returns an atom"
  [width height]
  (atom {:settings {} :windowed (centered-geometry width height)}))
