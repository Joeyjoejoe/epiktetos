(ns epiktetos.gamepad
  (:require [epiktetos.event :as event])
  (:import (java.nio IntBuffer)
           (org.lwjgl.sdl SDLError SDLEvents SDLGamepad SDLHints SDLInit
                          SDLStdinc SDL_Event)))

(def BUTTONS
  "SDL gamepad button index to button name"
  {SDLGamepad/SDL_GAMEPAD_BUTTON_SOUTH          :gamepad/south
   SDLGamepad/SDL_GAMEPAD_BUTTON_EAST           :gamepad/east
   SDLGamepad/SDL_GAMEPAD_BUTTON_WEST           :gamepad/west
   SDLGamepad/SDL_GAMEPAD_BUTTON_NORTH          :gamepad/north
   SDLGamepad/SDL_GAMEPAD_BUTTON_BACK           :gamepad/back
   SDLGamepad/SDL_GAMEPAD_BUTTON_GUIDE          :gamepad/guide
   SDLGamepad/SDL_GAMEPAD_BUTTON_START          :gamepad/start
   SDLGamepad/SDL_GAMEPAD_BUTTON_LEFT_STICK     :gamepad/left-stick
   SDLGamepad/SDL_GAMEPAD_BUTTON_RIGHT_STICK    :gamepad/right-stick
   SDLGamepad/SDL_GAMEPAD_BUTTON_LEFT_SHOULDER  :gamepad/left-shoulder
   SDLGamepad/SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER :gamepad/right-shoulder
   SDLGamepad/SDL_GAMEPAD_BUTTON_DPAD_UP        :gamepad/dpad-up
   SDLGamepad/SDL_GAMEPAD_BUTTON_DPAD_DOWN      :gamepad/dpad-down
   SDLGamepad/SDL_GAMEPAD_BUTTON_DPAD_LEFT      :gamepad/dpad-left
   SDLGamepad/SDL_GAMEPAD_BUTTON_DPAD_RIGHT     :gamepad/dpad-right
   SDLGamepad/SDL_GAMEPAD_BUTTON_MISC1          :gamepad/misc1
   SDLGamepad/SDL_GAMEPAD_BUTTON_RIGHT_PADDLE1  :gamepad/right-paddle1
   SDLGamepad/SDL_GAMEPAD_BUTTON_LEFT_PADDLE1   :gamepad/left-paddle1
   SDLGamepad/SDL_GAMEPAD_BUTTON_RIGHT_PADDLE2  :gamepad/right-paddle2
   SDLGamepad/SDL_GAMEPAD_BUTTON_LEFT_PADDLE2   :gamepad/left-paddle2
   SDLGamepad/SDL_GAMEPAD_BUTTON_TOUCHPAD       :gamepad/touchpad
   SDLGamepad/SDL_GAMEPAD_BUTTON_MISC2          :gamepad/misc2
   SDLGamepad/SDL_GAMEPAD_BUTTON_MISC3          :gamepad/misc3
   SDLGamepad/SDL_GAMEPAD_BUTTON_MISC4          :gamepad/misc4
   SDLGamepad/SDL_GAMEPAD_BUTTON_MISC5          :gamepad/misc5
   SDLGamepad/SDL_GAMEPAD_BUTTON_MISC6          :gamepad/misc6})

(def STICKS
  "Stick names with their [x-axis y-axis] SDL axis indices, in
  emission order"
  [[:gamepad/left-stick  [SDLGamepad/SDL_GAMEPAD_AXIS_LEFTX
                          SDLGamepad/SDL_GAMEPAD_AXIS_LEFTY]]
   [:gamepad/right-stick [SDLGamepad/SDL_GAMEPAD_AXIS_RIGHTX
                          SDLGamepad/SDL_GAMEPAD_AXIS_RIGHTY]]])

(def TRIGGERS
  "Trigger names with their SDL axis index, in emission order"
  [[:gamepad/left-trigger  SDLGamepad/SDL_GAMEPAD_AXIS_LEFT_TRIGGER]
   [:gamepad/right-trigger SDLGamepad/SDL_GAMEPAD_AXIS_RIGHT_TRIGGER]])

(def DPAD
  "D-pad button index to its [x y] direction, y growing downward"
  {SDLGamepad/SDL_GAMEPAD_BUTTON_DPAD_UP    [0.0 -1.0]
   SDLGamepad/SDL_GAMEPAD_BUTTON_DPAD_DOWN  [0.0 1.0]
   SDLGamepad/SDL_GAMEPAD_BUTTON_DPAD_LEFT  [-1.0 0.0]
   SDLGamepad/SDL_GAMEPAD_BUTTON_DPAD_RIGHT [1.0 0.0]})

(def NEUTRAL-STATE
  "The state of a gamepad at rest, and of an absent gamepad: no button
  held, the six axes at zero"
  {:buttons #{}
   :axes    [0 0 0 0 0 0]})

(defn normalize-stick
  "Convert a raw stick axis value to a position component.
  value - int, SDL signed 16-bit axis value in [-32768, 32767]
  Returns a double in [-1.0, 1.0]"
  [value]
  (max -1.0 (min 1.0 (/ value 32767.0))))

(defn normalize-trigger
  "Convert a raw trigger axis value to a position.
  value - int, SDL axis value in [0, 32767]
  Returns a double in [0.0, 1.0]"
  [value]
  (max 0.0 (min 1.0 (/ value 32767.0))))

(defn button-event
  "Build the event of a button reading.
  reading - map, {:gamepad-id int :button int :down? boolean}
  Returns [[:press|:release button-name] {:gamepad-id int}], or nil
  when the button index has no name"
  [reading]
  (when-let [button-name (get BUTTONS (:button reading))]
    [[(if (:down? reading) :press :release) button-name]
     {:gamepad-id (:gamepad-id reading)}]))

(defn connection-event
  "Build the event of a connection reading.
  reading - map, {:kind :connected :gamepad-id int :name string
            :player-index int} or {:kind :disconnected :gamepad-id int}
  Returns [:gamepad/connected {:gamepad-id :name :player-index}] or
  [:gamepad/disconnected {:gamepad-id}]"
  [reading]
  (if (= :connected (:kind reading))
    [:gamepad/connected (select-keys reading [:gamepad-id :name :player-index])]
    [:gamepad/disconnected (select-keys reading [:gamepad-id])]))

(defn dpad-position
  "Compute the d-pad position from the held buttons: the sum of the
  directions of the held d-pad buttons.
  buttons - set of held button indices
  Returns [x y], each -1.0, 0.0 or 1.0, y growing downward"
  [buttons]
  (reduce #(mapv + %1 %2) [0.0 0.0] (keep DPAD buttons)))

(defn- control-position-events
  "Build the position events of the sticks and triggers owning at
  least one of the changed axes, sticks first.
  gamepad-id   - int
  axes         - vector of the six raw axis values
  changed-axes - set of axis indices
  Returns a seq of [control {:gamepad-id int :position [x y]|double}]"
  [gamepad-id axes changed-axes]
  (concat
    (map (fn [[stick stick-axes]]
           [stick {:gamepad-id gamepad-id
                   :position   (mapv #(normalize-stick (nth axes %)) stick-axes)}])
         (filter (fn [[_ stick-axes]]
                   (seq (filter changed-axes stick-axes)))
                 STICKS))
    (map (fn [[trigger axis]]
           [trigger {:gamepad-id gamepad-id
                     :position   (normalize-trigger (nth axes axis))}])
         (filter (fn [[_ axis]]
                   (contains? changed-axes axis))
                 TRIGGERS))))

(defn position-events
  "Coalesce the position readings of one poll: one event per gamepad
  and per stick or trigger that has at least one axis reading, and one
  :gamepad/dpad event per gamepad that has at least one d-pad button
  reading, each carrying its latest position. An axis without a
  reading takes its value from the gamepad state; the d-pad position
  is the held buttons of the state updated by the readings in order.
  readings - seq of reading maps; the {:kind :axis :gamepad-id int
             :axis int :value int} and the {:kind :button} of the
             d-pad are considered
  states   - map, {gamepad-id {:buttons set :axes vector}}
  Returns a vector of position events, by gamepad id: sticks,
  triggers, d-pad"
  [readings states]
  (let [moved (reduce (fn [moved {:keys [gamepad-id axis value]}]
                        (assoc-in moved [gamepad-id axis] value))
                      (sorted-map)
                      (filter #(and (= :axis (:kind %))
                                    (contains? (:axes NEUTRAL-STATE) (:axis %)))
                              readings))
        dpads (reduce (fn [dpads {:keys [gamepad-id button down?]}]
                        (assoc dpads gamepad-id
                               ((if down? conj disj)
                                (get dpads gamepad-id
                                     (:buttons (get states gamepad-id NEUTRAL-STATE)))
                                button)))
                      (sorted-map)
                      (filter #(and (= :button (:kind %))
                                    (contains? DPAD (:button %)))
                              readings))]
    (vec (mapcat (fn [gamepad-id]
                   (concat
                     (when-let [values (get moved gamepad-id)]
                       (control-position-events
                         gamepad-id
                         (reduce-kv assoc
                                    (:axes (get states gamepad-id NEUTRAL-STATE))
                                    values)
                         (set (keys values))))
                     (when-let [buttons (get dpads gamepad-id)]
                       [[:gamepad/dpad {:gamepad-id gamepad-id
                                        :position   (dpad-position buttons)}]])))
                 (sort (distinct (concat (keys moved) (keys dpads))))))))

(defn synchronize-states
  "Build the events taking the user from the known states to the
  target states: a release for each button held only in known, a press
  for each button held only in target, the target position of each
  stick and trigger whose raw axes differ, the target d-pad position
  when it differs. A gamepad missing on one side counts as
  NEUTRAL-STATE.
  known-states  - map, {gamepad-id {:buttons set :axes vector}}
  target-states - map, same shape
  Returns a vector of events, by gamepad id: releases, presses,
  positions"
  [known-states target-states]
  (vec (mapcat (fn [gamepad-id]
                 (let [known  (get known-states gamepad-id NEUTRAL-STATE)
                       target (get target-states gamepad-id NEUTRAL-STATE)]
                   (concat
                     (keep #(button-event {:gamepad-id gamepad-id
                                           :button     %
                                           :down?      false})
                           (sort (remove (:buttons target) (:buttons known))))
                     (keep #(button-event {:gamepad-id gamepad-id
                                           :button     %
                                           :down?      true})
                           (sort (remove (:buttons known) (:buttons target))))
                     (control-position-events
                       gamepad-id
                       (:axes target)
                       (set (filter #(not= (nth (:axes known) %)
                                           (nth (:axes target) %))
                                    (range (count (:axes target))))))
                     (when (not= (dpad-position (:buttons known))
                                 (dpad-position (:buttons target)))
                       [[:gamepad/dpad
                         {:gamepad-id gamepad-id
                          :position   (dpad-position (:buttons target))}]]))))
               (sort (distinct (concat (keys known-states)
                                       (keys target-states)))))))

(defn paused-since?
  "True when a loop pause happened between two polls.
  previous-context - map, the poll context of the previous poll
  context          - map, the poll context of this poll
  Returns a boolean"
  [previous-context context]
  (not= (:pause-count previous-context) (:pause-count context)))

(defn poll-events
  "Decide the events of one poll, in dispatch order: buttons and
  connections in reading order, coalesced positions, synthesized
  events, disconnections.

  Inputs are delivered when the window was focused at the previous
  poll and no pause happened since. The synthesized events take the
  user from what they know to the target: neutral when focus is lost,
  the actual state when focus returns, the difference with the state
  before the pause after a pause.

  readings         - seq of reading maps, in SDL order
  previous-states  - map of states read before the drain, used after
                     a pause; nil otherwise
  states           - map, {gamepad-id {:buttons set :axes vector}},
                     read after the drain
  previous-context - map, {:focused? boolean :pause-count int} of the
                     previous poll
  context          - map, same shape, of this poll
  Returns a vector of events"
  [readings previous-states states previous-context context]
  (let [paused?        (paused-since? previous-context context)
        deliver?       (and (:focused? previous-context) (not paused?))
        disconnections (filter #(= :disconnected (:kind %)) readings)
        states         (apply dissoc states (map :gamepad-id disconnections))
        known          (cond
                         (not (:focused? previous-context)) {}
                         paused?                            previous-states
                         :else                              states)
        target         (if (:focused? context) states {})]
    (vec (concat
           (keep (fn [reading]
                   (case (:kind reading)
                     :button    (when deliver? (button-event reading))
                     :connected (connection-event reading)
                     nil))
                 readings)
           (when deliver?
             (position-events readings states))
           (synchronize-states known target)
           (map connection-event disconnections)))))

(defn- read-gamepad
  "Query the identity of an opened gamepad.
  handle - long, SDL gamepad handle
  Returns {:name string :player-index int}"
  [^long handle]
  {:name         (SDLGamepad/SDL_GetGamepadName handle)
   :player-index (long (SDLGamepad/SDL_GetGamepadPlayerIndex handle))})

(defn- read-state
  "Query the state SDL holds for an opened gamepad.
  handle - long, SDL gamepad handle
  Returns {:buttons set of held button indices, :axes vector of the
  six raw axis values}"
  [^long handle]
  {:buttons (set (filter #(SDLGamepad/SDL_GetGamepadButton handle (int %))
                         (keys BUTTONS)))
   :axes    (mapv #(long (SDLGamepad/SDL_GetGamepadAxis handle (int %)))
                  (range (count (:axes NEUTRAL-STATE))))})

(defn- opened-gamepad-ids
  "List the connected gamepads the engine has opened.
  Returns a vector of gamepad ids"
  []
  (if-let [^IntBuffer ids (SDLGamepad/SDL_GetGamepads)]
    (try
      (filterv #(not (zero? (SDLGamepad/SDL_GetGamepadFromID (int %))))
               (mapv #(long (.get ids (int %))) (range (.remaining ids))))
      (finally
        (SDLStdinc/SDL_free ids)))
    []))

(defn- read-states
  "Query the state of every opened gamepad.
  Returns {gamepad-id {:buttons set :axes vector}}"
  []
  (into {}
        (map (fn [gamepad-id]
               [gamepad-id
                (read-state (SDLGamepad/SDL_GetGamepadFromID (int gamepad-id)))]))
        (opened-gamepad-ids)))

(defn- take-reading!
  "Convert the SDL event held by the struct into a reading. An added
  gamepad is opened, so that it emits inputs; it yields a :connected
  reading only when this call opened it.
  event - SDL_Event, filled by SDL_PollEvent
  Returns a reading map, or nil for an event the engine ignores"
  [^SDL_Event event]
  (let [type (.type event)]
    (cond
      (or (= type SDLEvents/SDL_EVENT_GAMEPAD_BUTTON_DOWN)
          (= type SDLEvents/SDL_EVENT_GAMEPAD_BUTTON_UP))
      (let [button (.gbutton event)]
        {:kind       :button
         :gamepad-id (long (.which button))
         :button     (long (.button button))
         :down?      (.down button)})

      (= type SDLEvents/SDL_EVENT_GAMEPAD_AXIS_MOTION)
      (let [axis (.gaxis event)]
        {:kind       :axis
         :gamepad-id (long (.which axis))
         :axis       (long (.axis axis))
         :value      (long (.value axis))})

      (= type SDLEvents/SDL_EVENT_GAMEPAD_ADDED)
      (let [gamepad-id (.which (.gdevice event))]
        (when (zero? (SDLGamepad/SDL_GetGamepadFromID gamepad-id))
          (let [handle (SDLGamepad/SDL_OpenGamepad gamepad-id)]
            (when-not (zero? handle)
              (assoc (read-gamepad handle)
                     :kind       :connected
                     :gamepad-id (long gamepad-id))))))

      (= type SDLEvents/SDL_EVENT_GAMEPAD_REMOVED)
      (let [gamepad-id (.which (.gdevice event))]
        (when-not (zero? (SDLGamepad/SDL_GetGamepadFromID gamepad-id))
          {:kind       :disconnected
           :gamepad-id (long gamepad-id)})))))

(defn- drain-readings!
  "Take every pending SDL event out of SDL's queue.
  event - SDL_Event, the struct SDL_PollEvent fills
  Returns the vector of readings, in SDL order"
  [^SDL_Event event]
  (loop [readings []]
    (if (SDLEvents/SDL_PollEvent event)
      (recur (if-let [reading (take-reading! event)]
               (conj readings reading)
               readings))
      readings)))

(defn- close-gamepads!
  "Close the gamepads reported as disconnected.
  readings - seq of reading maps
  Returns nil"
  [readings]
  (doseq [reading (filter #(= :disconnected (:kind %)) readings)]
    (let [handle (SDLGamepad/SDL_GetGamepadFromID (int (:gamepad-id reading)))]
      (when-not (zero? handle)
        (SDLGamepad/SDL_CloseGamepad handle))))
  nil)

(defn- dispatch-events!
  "Dispatch the events that have a handler registered for their exact
  id.
  events - seq of event vectors
  Returns nil"
  [events]
  (doseq [gamepad-event (filter #(event/handler? (event/get-id %)) events)]
    (event/dispatch gamepad-event))
  nil)

(defn initialize-sdl!
  "Initialize the SDL gamepad subsystem and allocate the event struct
  the poll reuses. A failure is reported and not fatal: the engine
  runs without gamepads.
  Returns {:event SDL_Event}, or nil when SDL is unavailable"
  []
  (try
    (SDLHints/SDL_SetHint SDLHints/SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS "1")
    (SDLHints/SDL_SetHint SDLHints/SDL_HINT_NO_SIGNAL_HANDLERS "1")
    (if (SDLInit/SDL_Init SDLInit/SDL_INIT_GAMEPAD)
      {:event (SDL_Event/calloc)}
      (println "[epiktetos] Could not initialize SDL, gamepads are unavailable:"
               (SDLError/SDL_GetError)))
    (catch Throwable t
      (println "[epiktetos] Could not initialize SDL, gamepads are unavailable:"
               (.getMessage t)))))

(defn quit-sdl!
  "Close every opened gamepad, free the event struct and quit SDL.
  sdl - map returned by initialize-sdl!, or nil
  Returns nil"
  [sdl]
  (when sdl
    (doseq [gamepad-id (opened-gamepad-ids)]
      (SDLGamepad/SDL_CloseGamepad
        (SDLGamepad/SDL_GetGamepadFromID (int gamepad-id))))
    (.free ^SDL_Event (:event sdl))
    (SDLInit/SDL_Quit))
  nil)

(defn gamepads
  "List the connected gamepads, as of the last poll.
  sdl - map returned by initialize-sdl!, or nil
  Returns {gamepad-id {:name string :player-index int}}, empty when
  SDL is unavailable"
  [sdl]
  (if sdl
    (into {}
          (map (fn [gamepad-id]
                 [gamepad-id
                  (read-gamepad (SDLGamepad/SDL_GetGamepadFromID (int gamepad-id)))]))
          (opened-gamepad-ids))
    {}))

(defn poll!
  "Poll the gamepads once: drain SDL's queue, open and close gamepads,
  dispatch the events decided by poll-events. A no-op when SDL is
  unavailable.
  sdl              - map returned by initialize-sdl!, or nil
  previous-context - map, {:focused? boolean :pause-count int} of the
                     previous poll
  context          - map, same shape, of this poll
  Returns context"
  [sdl previous-context context]
  (when sdl
    (let [previous-states (when (paused-since? previous-context context)
                            (read-states))
          readings        (drain-readings! (:event sdl))
          states          (read-states)]
      (dispatch-events! (poll-events readings previous-states states
                                     previous-context context))
      (close-gamepads! readings)))
  context)
