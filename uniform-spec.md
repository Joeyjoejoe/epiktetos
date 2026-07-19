# Plain Uniform Input Specification

## Overview

This specification extends `input-spec.md` to **plain uniforms** — uniforms of
the default block, declared outside any `uniform { ... }` or `buffer { ... }`
block:

```glsl
uniform float uTime;
uniform vec2  uResolution;
uniform int   uDebugMode;
```

Plain uniforms are registered with the same `reg-input` call, the same handler
signature and the same steps as bindable blocks. What differs is everything
behind the varname: a plain uniform is **per-program state addressed by
location**, not a shared buffer bound to a binding point.

| | Block (UBO/SSBO) | Plain uniform |
|---|---|---|
| Identity | binding point, global to the context | location, private to each program |
| Sharing | one buffer, read by every program declaring the block | one independent copy per program |
| Write | one buffer upload | one `glProgramUniform*` per program declaring the name |
| Storage | buffer object, survives program relink | program state, **reset by relink** |
| Layout | introspected offsets/strides (std140/430) | none — typed values at locations |
| Runtime array | yes (SSBO) | no |

---

## When To Use

The write cost sets the doctrine. Writing a plain uniform fans out to every
program declaring the name (N small writes); writing a block is a single
buffer upload shared by all. Rule of thumb:

| Data | Use |
|---|---|
| Small per-frame scalars: time, resolution, seeds, debug toggles, effect knobs | **plain uniform** |
| Structured data shared by several programs: camera, lights, materials | **UBO** |
| Large or variable-count data: palettes, particles, instances | **SSBO** |

A plain uniform is the right tool when the value is small, the fan-out is
cheap, and declaring a block would be ceremony. `uniform float uTime;` should
never require a block.

GLSL initializers (`uniform float uSpeed = 1.0;`) are the idiomatic way to
give a plain uniform a default: the value holds until the first handler write,
and relinking restores it. Block members cannot do this — one more reason
small tunables belong to plain uniforms.

---

## Registration

Same forms, same handler signature, same steps as `input-spec.md`:

```clojure
(reg-input "uTime" (fn [db _frame] (get-in db [:core/loop :time :curr])))

(reg-input "uPulse" pulse-handler {:step :step/entity})
```

- `varname` — string, must match the GLSL uniform name exactly.
- The handler returns the **value** directly (see Value Format Rules), not a
  map: a plain uniform has no members.

### Fan-out

A registered uniform input feeds **every** program declaring the name. This
is not configurable: as everywhere in the engine, **one variable name means
one input, with one meaning** — the fan-out is what makes the name a single
source of truth across programs, exactly like a block's shared buffer. Two
programs needing different values under the same name must use distinct
names (the multi-view rule of `input-spec.md`, applied to uniforms).

### Execution model

The handler runs at each transition of its `:step`, like any input — once
per loop iteration for `:step/frame`, once per program batch for
`:step/program`, once per entity for `:step/entity`. Each execution then
writes the returned value to the location of every program declaring the
uniform — **but only when the value differs from the last written one**
(see Write semantics): a static value costs zero writes after the first
frame, and a per-frame value like `uTime` costs n small `glProgramUniform*`
calls per iteration, n being the number of declaring programs.

### Registration-time validation

Same ordering freedom as blocks — `reg-input` and `reg-p` may be called in
any order:

- When a program is registered, each introspected default-block uniform is
  matched against registered inputs by varname. A uniform with no matching
  input produces a **warning** — it keeps its GLSL initializer (or zero).
- `reg-input` on a name unknown to every registered program is **valid** and
  silent.
- A uniform declared in the GLSL source but unused may be optimized out by the
  compiler (location -1): the engine skips it silently for that program.

### Write semantics

- Values are written with `glProgramUniform*` (no program binding required),
  at the location introspected per program.
- A written value **persists** in the program: the engine only writes when
  the handler output changes (the unchanged-value skip of `input-spec.md`
  applies — return cached immutable structures for static values and the
  write is free).
- **Relinking a program resets its uniforms** (hot-reload, `reg-p` on an
  existing id): the engine drops its written-value knowledge for that program
  and rewrites at the next step execution. User handlers never need to care.

---

## Value Format Rules

The handler returns the value itself, following the same per-type rules as
`input-spec.md`:

```clojure
; uniform float uTime;
(reg-input "uTime" (fn [db _] 1.5))

; uniform vec2 uResolution;
(reg-input "uResolution" (fn [db _] [800.0 600.0]))

; uniform mat4 uModel;                    — column-major, as everywhere
(reg-input "uModel" (fn [db _] [1 0 0 0  0 1 0 0  0 0 1 0  0 0 0 1]))

; uniform float uWeights[3];
(reg-input "uWeights" (fn [db _] [0.5 1.0 0.8]))
```

Booleans are accepted for `bool` and converted to `1`/`0`. Struct uniforms
and arrays of structs in the default block follow the recursive map/vector
format of `input-spec.md`; each leaf is written to its own introspected
location:

```glsl
struct Fog { vec3 color; float density; };
uniform Fog uFog;
```

```clojure
(reg-input "uFog" (fn [db _] {"color" [0.1 0.1 0.1] "density" 0.35}))
```

Samplers (`sampler2D`, ...) are plain uniforms in GLSL but are **out of
scope** here: they carry texture-unit semantics and belong to the texture
specification.

---

## Validation

Same engine-side validation as blocks, against the introspected GLSL type,
before any write:

- Value type mismatches (non-number for scalar, wrong component count for
  vectors/matrices, float provided for `int`/`uint`/`bool`, out-of-range
  integers) → error.
- Fixed-size array element count mismatch → error.
- Missing struct field → error; extra field → warning, ignored.
- Matrices are column-major; the engine cannot detect row-major data.

The error report carries the varname, the step, the expected GLSL type and
the offending value. As with blocks, the current behavior is to throw (data
confinement is deferred to the engine-wide error session).

---

## Notes

- **No uniform-specific option is warranted.** Rejected candidates: a
  program-scoping option (it would weaken the one-name-one-input rule the
  engine enforces everywhere), a `:uniform/transpose` matrix flag (the
  engine is column-major everywhere, one convention beats an option), and a
  `:uniform/default` value (GLSL initializers already do this, closer to
  the shader and relink-proof). `:step` is the only accepted option.
- Implementation-wise, plain uniforms are a third resource kind next to
  `:ubo` and `:ssbo` — location caches per program instead of binding
  allocation, fan-out writes instead of buffer uploads — behind the same
  `reg-input` surface.

---

## Summary Table

| GLSL declaration | Handler return value |
|---|---|
| `uniform float u` | `1.5` |
| `uniform vec3 u` | `[x y z]` |
| `uniform mat4 u` | `[m0 ... m15]` |
| `uniform bool u` | `true` |
| `uniform float u[N]` | `[v0 ... vN-1]` |
| `uniform S u` (struct) | `{"field" value ...}` |
| `uniform sampler2D u` | out of scope — texture spec |
