(ns epiktetos.gamepad-test
  (:require [clojure.test :as t]
            [epiktetos.gamepad :as gamepad]))

(def FOCUSED   {:focused? true  :pause-count 0})
(def UNFOCUSED {:focused? false :pause-count 0})
(def RESUMED   {:focused? true  :pause-count 1})

(defn- state
  "Build a gamepad state.
  buttons - set of held button indices
  axes    - map of axis index to raw value, the others at zero
  Returns {:buttons set :axes vector}"
  [buttons axes]
  {:buttons buttons
   :axes    (reduce-kv assoc (:axes gamepad/NEUTRAL-STATE) axes)})

(defn- press [gamepad-id button]
  {:kind :button :gamepad-id gamepad-id :button button :down? true})

(defn- release [gamepad-id button]
  {:kind :button :gamepad-id gamepad-id :button button :down? false})

(defn- axis [gamepad-id axis value]
  {:kind :axis :gamepad-id gamepad-id :axis axis :value value})

(def UP 11)
(def DOWN 12)
(def LEFT 13)
(def RIGHT 14)

(t/deftest dpad-position-test
  (t/testing "each direction, y growing downward"
    (t/is (= [0.0 -1.0] (gamepad/dpad-position #{UP})))
    (t/is (= [0.0 1.0] (gamepad/dpad-position #{DOWN})))
    (t/is (= [-1.0 0.0] (gamepad/dpad-position #{LEFT})))
    (t/is (= [1.0 0.0] (gamepad/dpad-position #{RIGHT}))))

  (t/testing "diagonals are not normalized, opposite directions cancel"
    (t/is (= [1.0 -1.0] (gamepad/dpad-position #{UP RIGHT})))
    (t/is (= [0.0 0.0] (gamepad/dpad-position #{LEFT RIGHT}))))

  (t/testing "the other buttons do not count"
    (t/is (= [0.0 0.0] (gamepad/dpad-position #{})))
    (t/is (= [-1.0 0.0] (gamepad/dpad-position #{0 9 LEFT})))))

(t/deftest dpad-events-test
  (t/testing "a d-pad press yields its button event and one position event"
    (t/is (= [[[:press :gamepad/dpad-right] {:gamepad-id 3}]
              [:gamepad/dpad {:gamepad-id 3 :position [1.0 0.0]}]]
             (gamepad/poll-events
               [(press 3 RIGHT)]
               nil {3 (state #{RIGHT} {})} FOCUSED FOCUSED))))

  (t/testing "several d-pad transitions coalesce to one position, buttons do not"
    (t/is (= [[[:press :gamepad/dpad-right] {:gamepad-id 3}]
              [[:release :gamepad/dpad-right] {:gamepad-id 3}]
              [[:press :gamepad/dpad-up] {:gamepad-id 3}]
              [:gamepad/dpad {:gamepad-id 3 :position [0.0 -1.0]}]]
             (gamepad/poll-events
               [(press 3 RIGHT) (release 3 RIGHT) (press 3 UP)]
               nil {3 (state #{UP} {})} FOCUSED FOCUSED))))

  (t/testing "a tap within one poll reports the neutral position"
    (t/is (= [[:gamepad/dpad {:gamepad-id 3 :position [0.0 0.0]}]]
             (gamepad/position-events [(press 3 LEFT) (release 3 LEFT)]
                                      {3 gamepad/NEUTRAL-STATE}))))

  (t/testing "the readings beat a stale state, the other held directions are kept"
    (t/is (= [[:gamepad/dpad {:gamepad-id 3 :position [1.0 -1.0]}]]
             (gamepad/position-events [(press 3 RIGHT)]
                                      {3 (state #{UP} {})}))))

  (t/testing "sticks, triggers, then d-pad, by gamepad id"
    (t/is (= [[:gamepad/dpad {:gamepad-id 1 :position [0.0 1.0]}]
              [:gamepad/left-stick {:gamepad-id 3 :position [1.0 0.0]}]
              [:gamepad/dpad {:gamepad-id 3 :position [-1.0 0.0]}]]
             (gamepad/position-events
               [(press 3 LEFT) (axis 3 0 32767) (press 1 DOWN)]
               {}))))

  (t/testing "another button does not produce a d-pad position"
    (t/is (= [] (gamepad/position-events [(press 3 0)] {}))))

  (t/testing "focus loss: release, then the neutral d-pad position"
    (t/is (= [[[:release :gamepad/dpad-left] {:gamepad-id 3}]
              [:gamepad/dpad {:gamepad-id 3 :position [0.0 0.0]}]]
             (gamepad/poll-events
               [] nil {3 (state #{LEFT} {})} FOCUSED UNFOCUSED))))

  (t/testing "focus return: press, then the actual d-pad position"
    (t/is (= [[[:press :gamepad/dpad-left] {:gamepad-id 3}]
              [:gamepad/dpad {:gamepad-id 3 :position [-1.0 0.0]}]]
             (gamepad/poll-events
               [] nil {3 (state #{LEFT} {})} UNFOCUSED FOCUSED))))

  (t/testing "after a pause: the direction changed during it"
    (t/is (= [[[:release :gamepad/dpad-left] {:gamepad-id 3}]
              [[:press :gamepad/dpad-up] {:gamepad-id 3}]
              [:gamepad/dpad {:gamepad-id 3 :position [0.0 -1.0]}]]
             (gamepad/poll-events
               [(release 3 LEFT) (press 3 UP)]
               {3 (state #{LEFT} {})} {3 (state #{UP} {})} FOCUSED RESUMED))))

  (t/testing "unplug with the lever held: release, neutral position, disconnection last"
    (t/is (= [[[:release :gamepad/dpad-left] {:gamepad-id 3}]
              [:gamepad/dpad {:gamepad-id 3 :position [0.0 0.0]}]
              [:gamepad/disconnected {:gamepad-id 3}]]
             (gamepad/poll-events
               [(release 3 LEFT) {:kind :disconnected :gamepad-id 3}]
               nil {} FOCUSED FOCUSED)))))

(t/deftest tables-test
  (t/testing "26 distinct button names, all under the gamepad namespace"
    (t/is (= 26 (count gamepad/BUTTONS)))
    (t/is (= 26 (count (set (vals gamepad/BUTTONS)))))
    (t/is (every? #(= "gamepad" (namespace %)) (vals gamepad/BUTTONS))))

  (t/testing "button indices follow SDL's layout"
    (t/is (= :gamepad/south (get gamepad/BUTTONS 0)))
    (t/is (= :gamepad/left-stick (get gamepad/BUTTONS 7)))
    (t/is (= :gamepad/dpad-right (get gamepad/BUTTONS 14)))
    (t/is (= :gamepad/right-paddle1 (get gamepad/BUTTONS 16)))
    (t/is (= :gamepad/left-paddle1 (get gamepad/BUTTONS 17)))
    (t/is (= :gamepad/touchpad (get gamepad/BUTTONS 20)))
    (t/is (= :gamepad/misc2 (get gamepad/BUTTONS 21)))
    (t/is (= :gamepad/misc6 (get gamepad/BUTTONS 25))))

  (t/testing "sticks and triggers cover the six axes once"
    (t/is (= [0 1 2 3 4 5]
             (sort (concat (mapcat second gamepad/STICKS)
                           (map second gamepad/TRIGGERS)))))))

(t/deftest normalization-test
  (t/testing "sticks span [-1.0, 1.0], the asymmetric minimum clamped"
    (t/is (= 1.0 (gamepad/normalize-stick 32767)))
    (t/is (= -1.0 (gamepad/normalize-stick -32768)))
    (t/is (= 0.0 (gamepad/normalize-stick 0))))

  (t/testing "triggers span [0.0, 1.0]"
    (t/is (= 1.0 (gamepad/normalize-trigger 32767)))
    (t/is (= 0.0 (gamepad/normalize-trigger 0)))
    (t/is (= 0.0 (gamepad/normalize-trigger -5)))))

(t/deftest button-event-test
  (t/testing "press and release carry the gamepad id"
    (t/is (= [[:press :gamepad/south] {:gamepad-id 3}]
             (gamepad/button-event (press 3 0))))
    (t/is (= [[:release :gamepad/south] {:gamepad-id 3}]
             (gamepad/button-event (release 3 0)))))

  (t/testing "the stick click is a button event on the stick's name"
    (t/is (= [[:press :gamepad/left-stick] {:gamepad-id 1}]
             (gamepad/button-event (press 1 7)))))

  (t/testing "an unknown button index yields no event"
    (t/is (nil? (gamepad/button-event (press 1 99))))))

(t/deftest connection-event-test
  (t/is (= [:gamepad/connected {:gamepad-id 3 :name "Pad" :player-index 0}]
           (gamepad/connection-event {:kind :connected :gamepad-id 3
                                      :name "Pad" :player-index 0})))
  (t/is (= [:gamepad/disconnected {:gamepad-id 3}]
           (gamepad/connection-event {:kind :disconnected :gamepad-id 3}))))

(t/deftest position-events-test
  (t/testing "many axis readings coalesce to one event per stick"
    (t/is (= [[:gamepad/left-stick {:gamepad-id 3 :position [1.0 -1.0]}]]
             (gamepad/position-events
               [(axis 3 0 100) (axis 3 1 100) (axis 3 0 20000) (axis 3 1 -20000)
                (axis 3 0 32767) (axis 3 1 -32768)]
               {3 (state #{} {0 32767 1 -32768})}))))

  (t/testing "an axis without a reading takes its value from the state"
    (t/is (= [[:gamepad/left-stick {:gamepad-id 3 :position [1.0 -1.0]}]]
             (gamepad/position-events
               [(axis 3 0 32767)]
               {3 (state #{} {0 32767 1 -32768})}))))

  (t/testing "a reading beats a stale state"
    (t/is (= [[:gamepad/left-stick {:gamepad-id 3 :position [0.0 0.0]}]]
             (gamepad/position-events
               [(axis 3 0 0) (axis 3 1 0)]
               {3 (state #{} {0 32767 1 32767})}))))

  (t/testing "sticks before triggers, gamepads by id"
    (t/is (= [[:gamepad/left-stick    {:gamepad-id 1 :position [0.0 1.0]}]
              [:gamepad/right-stick   {:gamepad-id 3 :position [1.0 0.0]}]
              [:gamepad/left-trigger  {:gamepad-id 3 :position 1.0}]
              [:gamepad/right-trigger {:gamepad-id 3 :position 1.0}]]
             (gamepad/position-events
               [(axis 3 5 32767) (axis 3 4 32767) (axis 3 2 32767) (axis 1 1 32767)]
               {}))))

  (t/testing "no axis reading, an unknown axis: nothing"
    (t/is (= [] (gamepad/position-events [(press 3 0)] {})))
    (t/is (= [] (gamepad/position-events [(axis 3 9 100)] {})))))

(t/deftest synchronize-states-test
  (let [held (state #{0 9} {0 32767 4 32767})]
    (t/testing "to neutral: releases, then zero positions"
      (t/is (= [[[:release :gamepad/south] {:gamepad-id 3}]
                [[:release :gamepad/left-shoulder] {:gamepad-id 3}]
                [:gamepad/left-stick {:gamepad-id 3 :position [0.0 0.0]}]
                [:gamepad/left-trigger {:gamepad-id 3 :position 0.0}]]
               (gamepad/synchronize-states {3 held} {}))))

    (t/testing "from nothing known: presses, then actual positions"
      (t/is (= [[[:press :gamepad/south] {:gamepad-id 3}]
                [[:press :gamepad/left-shoulder] {:gamepad-id 3}]
                [:gamepad/left-stick {:gamepad-id 3 :position [1.0 0.0]}]
                [:gamepad/left-trigger {:gamepad-id 3 :position 1.0}]]
               (gamepad/synchronize-states {} {3 held}))))

    (t/testing "the difference only: what is held on both sides is silent"
      (t/is (= [[[:release :gamepad/left-shoulder] {:gamepad-id 3}]
                [[:press :gamepad/east] {:gamepad-id 3}]
                [:gamepad/left-trigger {:gamepad-id 3 :position 0.0}]]
               (gamepad/synchronize-states
                 {3 held}
                 {3 (state #{0 1} {0 32767})}))))

    (t/testing "identical states: nothing"
      (t/is (= [] (gamepad/synchronize-states {3 held} {3 held}))))

    (t/testing "every release of the neutral has its press in the return"
      (let [released (map (comp second first)
                          (filter (comp vector? first)
                                  (gamepad/synchronize-states {3 held} {})))
            pressed  (map (comp second first)
                          (filter (comp vector? first)
                                  (gamepad/synchronize-states {} {3 held})))]
        (t/is (= released pressed))))))

(t/deftest poll-events-test
  (let [held (state #{0} {0 32767})]
    (t/testing "normal: inputs delivered in order, nothing synthesized"
      (t/is (= [[[:press :gamepad/east] {:gamepad-id 3}]
                [[:release :gamepad/east] {:gamepad-id 3}]
                [:gamepad/left-stick {:gamepad-id 3 :position [1.0 0.0]}]]
               (gamepad/poll-events
                 [(press 3 1) (axis 3 0 32767) (release 3 1)]
                 nil {3 (state #{} {0 32767})} FOCUSED FOCUSED))))

    (t/testing "30 taps yield 30 presses and 30 releases, alternating"
      (let [events (gamepad/poll-events
                     (mapcat (fn [_] [(press 3 1) (release 3 1)]) (range 30))
                     nil {3 gamepad/NEUTRAL-STATE} FOCUSED FOCUSED)]
        (t/is (= 60 (count events)))
        (t/is (= (take 60 (cycle [:press :release]))
                 (map ffirst events)))))

    (t/testing "focus loss: this iteration's inputs, then neutral"
      (t/is (= [[[:press :gamepad/east] {:gamepad-id 3}]
                [[:release :gamepad/south] {:gamepad-id 3}]
                [[:release :gamepad/east] {:gamepad-id 3}]
                [:gamepad/left-stick {:gamepad-id 3 :position [0.0 0.0]}]]
               (gamepad/poll-events
                 [(press 3 1)]
                 nil {3 (state #{0 1} {0 32767})} FOCUSED UNFOCUSED))))

    (t/testing "unfocused: inputs discarded, connections kept"
      (t/is (= [[:gamepad/connected {:gamepad-id 5 :name "Pad" :player-index 1}]]
               (gamepad/poll-events
                 [(press 3 1)
                  {:kind :connected :gamepad-id 5 :name "Pad" :player-index 1}
                  (axis 3 0 100)]
                 nil {3 held 5 gamepad/NEUTRAL-STATE} UNFOCUSED UNFOCUSED))))

    (t/testing "focus return: queued inputs discarded, actual state announced"
      (t/is (= [[[:press :gamepad/south] {:gamepad-id 3}]
                [:gamepad/left-stick {:gamepad-id 3 :position [1.0 0.0]}]]
               (gamepad/poll-events
                 [(press 3 1) (release 3 1)]
                 nil {3 held} UNFOCUSED FOCUSED))))

    (t/testing "after a pause: inputs discarded, the difference announced"
      (t/is (= [[[:release :gamepad/south] {:gamepad-id 3}]
                [:gamepad/left-stick {:gamepad-id 3 :position [0.0 0.0]}]]
               (gamepad/poll-events
                 [(press 3 1) (release 3 1) (release 3 0) (axis 3 0 0)]
                 {3 held} {3 gamepad/NEUTRAL-STATE} FOCUSED RESUMED))))

    (t/testing "after a pause: a button held across it is silent"
      (t/is (= []
               (gamepad/poll-events [] {3 held} {3 held} FOCUSED RESUMED))))

    (t/testing "after a pause, resumed unfocused: neutral from the state before"
      (t/is (= [[[:release :gamepad/south] {:gamepad-id 3}]
                [:gamepad/left-stick {:gamepad-id 3 :position [0.0 0.0]}]]
               (gamepad/poll-events
                 [] {3 held} {3 held} FOCUSED (assoc RESUMED :focused? false)))))

    (t/testing "a pause while already unfocused: nothing was known, nothing to undo"
      (t/is (= []
               (gamepad/poll-events
                 [] {3 held} {3 held} UNFOCUSED (assoc RESUMED :focused? false)))))

    (t/testing "unplug: SDL's return to neutral, then the disconnection last"
      (t/is (= [[[:release :gamepad/south] {:gamepad-id 3}]
                [:gamepad/left-stick {:gamepad-id 3 :position [0.0 0.0]}]
                [:gamepad/disconnected {:gamepad-id 3}]]
               (gamepad/poll-events
                 [(axis 3 0 0) (release 3 0) {:kind :disconnected :gamepad-id 3}]
                 nil {} FOCUSED FOCUSED))))

    (t/testing "unplug during a pause: neutral from the state before, then the disconnection"
      (t/is (= [[[:release :gamepad/south] {:gamepad-id 3}]
                [:gamepad/left-stick {:gamepad-id 3 :position [0.0 0.0]}]
                [:gamepad/disconnected {:gamepad-id 3}]]
               (gamepad/poll-events
                 [(axis 3 0 0) (release 3 0) {:kind :disconnected :gamepad-id 3}]
                 {3 held} {} FOCUSED RESUMED))))

    (t/testing "first poll with a held button: the engine starts unfocused, then announces"
      (t/is (= [[:gamepad/connected {:gamepad-id 3 :name "Pad" :player-index 0}]
                [[:press :gamepad/south] {:gamepad-id 3}]
                [:gamepad/left-stick {:gamepad-id 3 :position [1.0 0.0]}]]
               (gamepad/poll-events
                 [{:kind :connected :gamepad-id 3 :name "Pad" :player-index 0}]
                 nil {3 held} UNFOCUSED FOCUSED))))

    (t/testing "an empty poll yields nothing"
      (t/is (= [] (gamepad/poll-events [] nil {} FOCUSED FOCUSED))))))

(t/deftest unavailable-sdl-test
  (t/testing "without SDL the shell is inert and touches no SDL function"
    (t/is (= {} (gamepad/gamepads nil)))
    (t/is (= FOCUSED (gamepad/poll! nil UNFOCUSED FOCUSED)))
    (t/is (nil? (gamepad/quit-sdl! nil)))))
