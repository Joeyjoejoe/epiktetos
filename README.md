# Epiktetos

> "Some things are up to us, and some things are not up to us."
> — Epictetus, Enchiridion, 1

A re-frame-inspired rendering engine for GLSL shaders.

Your application state is a single immutable value, changed only by pure
event handlers; what appears on screen is a set of declarations the engine
renders every frame. Epiktetos runs on OpenGL (through LWJGL) and GLFW, and
keeps the dangerous parts — GL state, buffers, the loop — on its side of the
line: you write data and pure functions, the engine executes the effects.

## Requirements

- A GPU driver supporting OpenGL 4.6 core — Linux (Windows should work,
  untested). Check yours with `glxinfo | grep "core profile"`
  (from the `mesa-utils` package).
- A JDK (11 or later) and the
  [Clojure CLI](https://clojure.org/guides/install_clojure).
- An editor connected to a Clojure REPL — the workflow this whole tutorial
  relies on: you evaluate code from your editor into a live program. If
  that sentence is new to you, set it up first with the official
  [editors guide](https://clojure.org/guides/editors) and
  [Programming at the REPL](https://clojure.org/guides/repl/introduction).

## Getting started

In this tutorial we will build a spinning triangle: open a window, draw a
triangle with a shader, and control its spin from the keyboard. On the way
we will use each part of the engine once — declarations, events, state,
shader inputs, and the error pause. No prior shader experience is needed.
Count about twenty minutes, all of them spent in a live, running program.

First, the lay of the land. A GPU draws by running two small programs of
ours — *shaders* — over data we supply: for a triangle, three corners,
each carrying a position and, here, a color. So drawing anything takes
exactly two ingredients: the shaders, and the data that feeds them.
Epiktetos frames both as declarations — we state once what exists (a
program, a triangle), the engine draws it every frame from then on — and
the changing values flow in through pure functions. Every step below adds
one of those pieces.

### Step 1 — Create the project

Create this layout:

```
my-app/
├── deps.edn
├── src/my_app/core.clj
└── resources/shaders/
    ├── hello.vert
    └── hello.frag
```

with Epiktetos as a git dependency in `deps.edn`:

```clojure
{:paths ["src" "resources"]
 :deps  {io.github.joeyjoejoe/epiktetos
         {:git/url "https://github.com/Joeyjoejoe/epiktetos"
          :git/sha "06b69d956c783b392b2c1ad5abae5875bb92cbc4"}}}
```

The three other files start empty; we fill them one by one.

### Step 2 — The application namespace

Put this in `src/my_app/core.clj`:

```clojure
(ns my-app.core
  (:require [epiktetos.core :as epik]))

(defn -main [& _] (epik/start!))

(comment
  (def engine (epik/start!))
  (epik/stop!)
  @engine)
```

We require a single namespace: `epiktetos.core` holds the engine's whole
public API, aliased here as `epik`. We will meet its functions one at a
time, as we need them. Everything we declare in this tutorial goes in
this file; the `comment` block at the end holds our REPL gestures — we
evaluate them by hand from the editor, and they never run on a file
load. From step 6 on, every form we meet goes into the file too, above
the `comment` block: add it, then evaluate it.

### Step 3 — Start the engine

`epik/start!` starts the engine: it opens the window and runs the render
loop on a thread of its own, so our REPL stays free while the engine
runs.

Jack in from your editor, load `my-app.core`, and evaluate
`(def engine (epik/start!))` from the `comment` block. The REPL shows the
new var (`#'my-app.core/engine`) and, next to it, a log line from the
engine,

```
:engine/start
```

and a window opens, filled with dark grey — possibly behind your editor. The engine is now rendering —
nothing, so far. Leave it running: the rest of this tutorial happens live,
in that window.

### Step 4 — The vertex shader

Time to draw. A GPU draws with a pair of small programs called *shaders*:
the first places points on the screen, the second colors the pixels in
between. We write both in GLSL, the shader language — copy them as they
are, no need to understand every line.

Put this in `resources/shaders/hello.vert`:

```glsl
#version 460 core

in vec3 vPosition;
in vec3 vColor;

uniform float uAngle;

out vec3 color;

void main()
{
    float c = cos(uAngle);
    float s = sin(uAngle);
    gl_Position = vec4(mat2(c, s, -s, c) * vPosition.xy, vPosition.z, 1.0);
    color = vColor;
}
```

This one runs once per corner of our triangle. Its `in` lines declare
what each corner brings — a position `vPosition`, a color `vColor`; we
will say in step 6 how to feed them. It rotates the position by an angle
`uAngle` (a single value for the whole triangle, fed later, in step 9),
places the corner on screen, and passes the color on through `out`.

### Step 5 — The fragment shader

Put this in `resources/shaders/hello.frag`:

```glsl
#version 460 core

in vec3 color;

out vec4 fragColor;

void main()
{
    fragColor = vec4(color, 1.0);
}
```

This one runs once per *fragment* — roughly, per pixel — inside the
triangle: its `in vec3 color` receives what the vertex shader sent `out`,
blended between the three corners, and it paints the pixel with it. Nothing to run yet — in the
next step we hand both files to the engine.

### Step 6 — Register a program

`epik/reg-p` registers a shader **program**: the engine compiles our two
GLSL files and links them into one GPU program, stored under a keyword of
our choosing — declared once, usable by any number of drawn things. Add this to the
file and evaluate it:

```clojure
(epik/reg-p :hello
  {:pipeline      [[:vertex   "shaders/hello.vert"]
                   [:fragment "shaders/hello.frag"]]
   :vertex-layout [{:layout  ["vPosition" "vColor"]
                    :handler (fn [entity]
                               (mapcat #(concat (:pos %) (:color %))
                                       (:vertices entity)))}]})
```

`:pipeline` lists the GLSL files, by classpath path, with their stage.
`:vertex-layout` is the bridge between our Clojure data and the shader —
it exists because a GPU does not read maps: it reads flat buffers of
numbers. Each entry declares which shader `in`s it feeds (`:layout`,
spelled exactly as `hello.vert` declares them) and how to produce their
values: the `:handler` receives the thing we will draw — a plain map, see
the next step — and returns the flat list of numbers, interleaved in
layout order: position, color, corner after corner (each `vec3`
takes three numbers, so six per corner). It runs when such a
thing is declared, to build its GPU buffers once — not on every frame.

Nothing appears yet, but notice that nothing failed either: the shaders
were compiled and checked just now, including that `:layout` matches what
they declare — the evaluation itself has nothing to show, the work
happens on the engine thread. A typo in a `.vert` file would have paused
the engine right here, with a message in the REPL like the one we will
meet in step 12 — not later, on screen.

### Step 7 — Draw the triangle

`epik/render` declares an **entity**: a map of our own data, drawn under an id,
every frame, from now on. Evaluate:

```clojure
(epik/render :triangle
  {:program  :hello
   :vertices [{:pos [ 0.0  0.6 0.0] :color [1 0 0]}
              {:pos [-0.6 -0.6 0.0] :color [0 1 0]}
              {:pos [ 0.6 -0.6 0.0] :color [0 0 1]}]})
```

The triangle appears, blending red, green and blue. The positions run
from -1 to 1 across the window, with (0, 0) at the center; a color is
[red green blue], each component between 0 and 1 — change a number and
re-evaluate to see. Two things happened:
the handler of step 6 ran — it read `:vertices` and fed `vPosition` and
`vColor` from it — and the entity joined the render loop. Note the
division: `:program` is the only key the engine requires; `:vertices` is
our own, free-form data, read by nothing but our handler. And there is nothing
to call again: the engine draws `:triangle` on every frame until we
`(epik/delete :triangle)`.

Notice the warning in the REPL:

```
✖ input "uAngle" unfed — no reg-input (drawn by :hello)
```

Our shader asks for an angle, and nobody provides one — so it stays at 0
and the triangle stands still. We will provide it in step 9.

### Step 8 — React to the keyboard

In Epiktetos, all application state lives in one map, the **db**, and
only events change it. `epik/reg-event` associates an event id with a
**handler**: a pure function that receives what happened (`cofx`, which
carries the current db) along with `fx`, the map of effects being built —
empty at this point — and returns that map, enriched with what should
change. Evaluate:

```clojure
(epik/reg-event [:press :space]
  (fn [cofx fx]
    (assoc fx :db (update (:db cofx) :spinning? not))))
```

The keyboard arrives as ordinary events: `[:press :space]` fires when
Space is pressed. Our handler flips a `:spinning?` flag — it reads the
current state in `(:db cofx)` and returns the new one under `:db`.

Click the window (it needs the keyboard focus), press Space, then let's
check from the REPL:

```clojure
(get @epik/db :spinning?)   ; => true
```

`epik/db` is there for reading and inspection only — changes always go
through events. Nothing moves yet: `:spinning?` is our own flag, the shader does not know
it. The next step connects them.

### Step 9 — Feed the shader

Remember the two kinds of shader input: the `in`s of the layout carry one
value per corner, baked into buffers when the entity is declared; a
`uniform` like `uAngle` is one value for the whole draw, and it can change
on every frame. That second kind is fed by `epik/reg-input`: it connects a
shader variable to a handler, by its GLSL name, and on every frame the
engine calls the handler with the db and writes the returned value into
every program declaring the variable. Evaluate:

```clojure
(epik/reg-input "uAngle"
  (fn [db _step]
    (if (:spinning? db)
      (* (:speed db 1.0) (get-in db [:core/window :time :curr]))
      0.0)))
```

The triangle starts spinning at once — `:spinning?` is still true from the
last step. Press Space: it stops. Press again: it spins. The `unfed`
warning is no longer reported.

Notice `:core/window` in there: the engine keeps its own state in the db
too — the clock, in seconds, the window size — so our handler derives
the angle from time like any other piece of state. GLSL angles are in
radians: at speed 1.0, the triangle turns once in about six seconds. (The second handler argument is unused
here. And two visual notes: the angle jumps when toggling, because we
recompute it from the clock rather than accumulate it; and on a non-square
window the triangle stretches as it turns, because our -1..1 coordinates
stretch with the window's width and height.)

### Step 10 — A coeffect: what handlers may know

Let's randomize the speed. A handler is pure — no `(rand)` allowed inside.
Anything a handler needs beyond the event and the db is declared as a
**coeffect** and injected: `epik/reg-cofx` declares one, `epik/inject-cofx` asks
for it on an event. Evaluate:

```clojure
(epik/reg-cofx :random-speed
  (fn [cofx]
    (assoc cofx :random-speed (+ 0.5 (rand 3.0)))))

(epik/reg-event [:press :r]
  [(epik/inject-cofx :random-speed)]
  (fn [{:keys [db random-speed]} fx]
    (assoc fx :db (assoc db :speed random-speed))))
```

Press R while it spins: new speed (the angle jumps, as before). The point
of this detour: because a handler receives *everything* it uses, the
engine can capture its exact inputs when something breaks. We will use
that in step 12.

### Step 11 — An effect: what handlers may do

Symmetrically, anything a handler wants to *do* beyond changing the db is
an **effect**, described in the `fx` map it returns. `epik/reg-fx` registers
our own effects; and the engine's functions — `epik/render`,
`epik/delete` — have a
second form that threads through `fx`: the short form of step 7 acts
immediately, fine at the REPL but wrong inside a pure handler; the `fx`
form only *describes* the action, and the engine executes it after the
handler returns. Evaluate:

```clojure
(epik/reg-fx :log
  (fn [message] (println message)))

(epik/reg-event [:press :d]
  (fn [_cofx fx]
    (-> fx
        (epik/delete :triangle)
        (assoc :log "triangle deleted"))))
```

Press D: the triangle disappears and `triangle deleted` prints in the
REPL. Re-evaluate the `render` form from step 7 — the triangle comes
back.

### Step 12 — Break it, pause, fix, resume

Last stop: what happens when our code throws. Evaluate this handler,
which has a bug — `:count` does not exist in the db yet, and `inc` on nil
throws:

```clojure
(defn count-press
  [cofx fx]
  (assoc fx :db (update (:db cofx) :count inc)))

(epik/reg-event [:press :b] count-press)
```

Press B. Instead of crashing, the engine **pauses** and prints:

```
⏸  Event Error [:press :b] (core.clj:…) ───────────────────────────────

│  NullPointerException:
│  at my-app.core/count-press (core.clj:…)

│  Debug with:
├─ (epiktetos.dev/retry!)             re-run the event and resume
├─ (epiktetos.dev/retry! [:id args])  replace the event, re-run and resume
├─ (epiktetos.dev/skip!)              drop the event and resume
├─ (epiktetos.core/stop!)             stop the engine
╰─ (epiktetos.dev/error-report)       the full context
```

The loop waits; nothing was applied. Let's look at what the engine
captured (`epiktetos.dev` is already loaded for us):

```clojure
(epiktetos.dev/error-report)
```

Notice `:coeffects` in the report: the exact inputs of the failed
handler. We can replay the failure as a plain function call, outside the
engine: `(count-press (:coeffects (epiktetos.dev/error-report)) {})`.

Now we fix the bug — replace `inc` with `(fnil inc 0)` in `count-press` —
and reload the whole file (re-evaluating the `defn` alone is not enough:
the engine holds the old function). Then:

```clojure
(epiktetos.dev/retry!)
```

The event replays with the fixed handler, the loop resumes, and
`(get @epik/db :count)` returns 1. One caveat to remember: this works
because the error struck *before* any effect ran. An error during an
effect's execution stops the engine instead — it will not resume over
half-applied changes.

### Step 13 — Stop

Evaluate `(epik/stop!)`, or close the window. Every GPU resource is freed, and
`@engine` returns `:engine/stop`. (Stopping forgets all declarations: once `@engine` has returned, reload
the file, then `(def engine (epik/start!))`.)

That was the whole working loop of the engine: we declared a program and
an entity, changed state through events, fed a shader from the db,
injected a coeffect, described an effect, and repaired a live bug without
restarting. Everything below works by these same few moves.

## Going further

The same small API covers, in the same declarative style:

- **Uniform blocks and SSBOs** — `reg-input` with a map-returning handler,
  validated against the introspected shader layout.
- **Textures** — `reg-texture` for the resource, `reg-input` to bind it.
- **Compute shaders** — `reg-compute`, dispatched at transitions of a
  render step, before the draws.
- **Instanced rendering** — `:instances` on the entity, per-instance
  buffers with `:divisor 1`.
- **Render steps** — custom sort dimensions for draw batching
  (`reg-steps!`).
- **Gamepads** — plugged in, they just emit events (`[:press
  :gamepad/south]`, `:gamepad/left-stick`).
- **GPU→CPU readback** — the `:read-input` coeffect.
- **Shader hot-reload** — on by default for `resources/shaders/`.
- **Window settings at runtime** — the `window` function (cursor capture,
  fullscreen, title…).
- **Your own configuration** — `(start! "my-config.edn")`, a classpath
  path.
- **Running without a REPL** — `clj -M -m my-app.core`: the engine thread
  keeps the process alive until `stop!`.
