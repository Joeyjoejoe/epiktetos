# Input Handler Output Format Specification

## Overview

A shader input handler always returns a **map** whose top-level keys are the GLSL
variable names declared in the input block. The value associated with each key
mirrors the structure of the corresponding GLSL type.

This specification covers **bindable shader inputs** — inputs bound to a
binding point allocated by the engine: UBO, SSBO, atomic counters. Textures
and images are bindable inputs too, but are special cases specified
separately.

```glsl
struct Light {
    vec3  position;
    float intensity;
};

layout(std140) uniform Scene {
    mat4  view;           // matrix
    vec3  camera_pos;     // vector
    float time;           // scalar float
    int   light_count;    // scalar int
    float weights[3];     // array of scalars
    Light sun;            // struct
    Light lights[2];      // array of structs
};
```

```clojure
(fn [db _]
  {"view"        [1 0 0 0
                  0 1 0 0
                  0 0 1 0
                  0 0 0 1]
   "camera_pos"  [0.0 5.0 10.0]
   "time"        1.5
   "light_count" 2
   "weights"     [0.5 1.0 0.8]
   "sun"         {"position" [10.0 20.0 30.0] "intensity" 3.0}
   "lights"      [{"position" [1.0 0.0 0.0] "intensity" 2.0}
                  {"position" [0.0 1.0 0.0] "intensity" 0.5}]})
```

---

## Registration

```clojure
(reg-input varname handler)
(reg-input varname handler options)
(reg-input fx varname handler options)   ; pure form, threads the fx map
```

Like every effectful function of the API, `reg-input` exists in two
equivalent forms: immediate (interactive evaluation, bootstrap) and pure
(threaded through the `fx` map inside an event handler).

- `varname` — string, must match the GLSL block variable name exactly.
- `handler` — function, called at each step change to produce the buffer data.
- `options` — map (optional). Available keys:

| Key              | Type    | Default       | Description                                              |
|------------------|---------|---------------|----------------------------------------------------------|
| `:step`          | keyword | `:step/frame` | Render step at which the handler executes                |
| `:ssbo/capacity` | pos int | `1`           | Maximum element count of the block's runtime array (SSBO) |

Default is `:step/frame`. For inputs that vary per program or per entity,
specify the appropriate step explicitly.

### Handler signature

All handlers share the same signature:

```clojure
(fn [db step-value] ...)
```

All handlers of a given frame receive the **same `db` value**: the state only
changes between frames (design rule 2), so every input written during a frame
reflects a single consistent state.

Steps run in this order. `step-value` is re-evaluated at each transition:

**Epiktetos built-in steps**

| Order | Step            | `step-value`                            |
|-------|-----------------|-----------------------------------------|
| 1     | `:step/frame`   | frame number                            |
| 2     | `:step/group`   | group name (`:group` key on the entity) |
| 3     | `:step/vao`     | VAO id                                  |
| 4     | `:step/program` | program key                             |
| 5     | custom steps    | return value of the step function       |
| 6     | `:step/entity`  | entity map                              |

**Custom steps**

Custom steps are registered via `reg-steps!` with a step key and a step function:

```clojure
(reg-steps! [:per-material (fn [entity] (:material entity))])
```

The step function is called on each entity to derive a grouping value. The input
handler fires when that value changes across entities. `step-value` is the return
value of the step function.

Custom steps are interleaved between `:step/program` (order 4) and `:step/entity`
(order 6) according to their sort position.

### Registration-time validation

`reg-input` and `reg-p` may be called in any order: an input can be
registered before any program declares a matching block.

- When a program is registered, each introspected block is matched against
  registered inputs by varname. A block with no matching input produces a
  **warning** — its buffer keeps its default (zeroed) content.
- `reg-input` on a varname unknown to every registered program is **valid**
  and silent: the matching program may be registered later.

---

## Value Format Rules

The format of a value is determined by its GLSL type:

### Scalar types

`float` `int` `uint` `bool` `double`

A bare numeric value. Clojure booleans `true`/`false` are accepted and converted
to `1`/`0`.

```clojure
; float time;
{"time" 1.5}

; int count;
{"count" 4}

; bool isActive;
{"isActive" true}
```

### Vector and matrix types

`vec2` `vec3` `vec4` `ivec2` `ivec3` `ivec4` `uvec2` `uvec3` `uvec4`
`bvec2` `bvec3` `bvec4` `mat2` `mat3` `mat4` `mat2x3` ... `dmat4`

A flat sequential collection of the scalar components. Matrices are
column-major (matching OpenGL's default memory layout).

```clojure
; vec3 position;
{"position" [1.0 2.0 3.0]}

; mat4 view;
{"view" [1 0 0 0
         0 1 0 0
         0 0 1 0
         0 0 0 1]}
```

### Struct types

A map whose keys are the struct field names and whose values follow these same
rules recursively.

```glsl
struct Light {
    vec3  position;
    float intensity;
};

layout(std140) uniform Lighting {
    Light sun;
    vec3  ambient;
};
```

```clojure
{"sun"     {"position"  [10.0 20.0 30.0]
            "intensity" 1.5}
 "ambient" [0.1 0.1 0.15]}
```

### Array types

A sequential collection of elements. The format of each element follows the
rules for the array's base type:

| Array base type | Element format   | Result            |
|-----------------|------------------|-------------------|
| scalar          | bare value       | flat seq          |
| vector / matrix | flat seq         | seq of seqs       |
| struct          | map              | seq of maps       |

```clojure
; float weights[4];
{"weights" [0.0 3.0 2.0 1.0]}

; vec3 positions[3];
{"positions" [[1.0 0.0 0.0]
              [0.0 1.0 0.0]
              [0.0 0.0 1.0]]}

; mat4 transforms[2];
{"transforms" [[1 0 0 0  0 1 0 0  0 0 1 0  0 0 0 1]
               [1 0 0 0  0 1 0 0  0 0 1 0  2 0 0 1]]}

; Light lights[2];
{"lights" [{"position" [1.0 0.0 0.0] "intensity" 1.0}
           {"position" [0.0 1.0 0.0] "intensity" 0.5}]}
```

### Runtime arrays (SSBO)

A shader storage block may end with an **unsized array** — the runtime array,
exclusive to SSBOs and necessarily its last member:

```glsl
struct Particle {
    vec3  position;
    float energy;
};

layout(std430) buffer Particles {
    int      count;
    Particle particles[];
};
```

The value is a sequential collection of **0 to capacity** elements, each
following the rules for the array's base type. The element count is free per
update; a member like `count` is the way to communicate it to the shader
(GLSL `length()` returns the capacity, not the actual count):

```clojure
(reg-input "Particles" particles-handler {:ssbo/capacity 1000})

(fn [db _]
  (let [particles (:particles db)]
    {"count"     (count particles)
     "particles" (mapv (fn [{:keys [pos energy]}]
                         {"position" pos "energy" energy})
                       particles)}))
```

`:ssbo/capacity` is expressed in **elements**, never in bytes — offsets and
strides are the engine's business, introspected from the compiled shader. The
capacity sizes the GPU buffer at registration. `reg-input` and `reg-p` may
still be called in any order: when the capacity of an already-created buffer
no longer matches, the buffer is recreated (zeroed, same binding point, the
previous buffer is deleted); an unchanged capacity is a no-op, buffer content
intact. `:ssbo/capacity` on a block without runtime array produces a warning
and is ignored.

---

## Combining Rules

The rules compose recursively. A struct may contain array fields; an array may
contain structs with their own nested fields.

```glsl
struct Bone {
    mat4  transform;
    float weight;
};

layout(std140) uniform Skeleton {
    Bone  bones[4];
    int   bone_count;
};
```

```clojure
{"bones" [{"transform" [1 0 0 0  0 1 0 0  0 0 1 0  0 0 0 1]
           "weight"    1.0}
          {"transform" [1 0 0 0  0 1 0 0  0 0 1 0  1 0 0 1]
           "weight"    0.8}
          {"transform" [...]
           "weight"    0.6}
          {"transform" [...]
           "weight"    0.4}]
 "bone_count" 4}
```

---

## Validation

The engine validates the handler output against the introspected GLSL metadata
before writing to the GPU buffer. The goal is to prevent silent GPU data
corruption, which produces incorrect rendering with no runtime error.

Validation runs during rendering, inside the engine loop. A validation error
is therefore **confined as data, never thrown** (design rule 5): the GPU
write is skipped, the buffer keeps its last valid content, and the error is
reported with its context (input varname, step, expected GLSL type, offending
value). The engine keeps running — a faulty handler reloaded from the editor
degrades its input, never the session.

### Errors (reported, GPU write skipped)

**Top-level**

- Handler does not return a map → error.
- A key declared in the GLSL block is absent from the handler map → error.

**Scalar**

- Value is not a number → error.
- A floating-point value is provided for an integer GLSL type (`int`, `uint`,
  `bool`) → error.
- A Clojure `Long` value outside the `Integer` range is provided for an `int`
  or `uint` field → error.

**Vector / matrix**

- Value is not sequential → error.
- The number of components does not match the GLSL type (e.g. 3 values for
  `vec4`, 15 values for `mat4`) → error.
- A sequential value contains a non-numeric element → error.
- A floating-point component is provided for an integer vector type (`ivec`,
  `uvec`, `bvec`) → error.

**Struct**

- Value is not a map → error.
- A field declared in the struct is absent from the value map → error.

**Array**

- Value is not sequential → error.
- For fixed-size arrays: the number of elements does not match the declared
  array size (e.g. 3 elements for `lights[2]`) → error.
- For runtime arrays: the number of elements exceeds the registered
  `:ssbo/capacity` → error (`:element-count-exceeds-capacity`). Fewer
  elements is valid.
- An element does not match the validation rules for the array's base type
  → error.

### Warnings

- The handler map contains a key that does not match any member declared in the
  GLSL block → warning, key is ignored. No GPU data is written.

### Notes

- Full recursive validation runs on every handler execution. The engine may
  skip it in a production mode — performance is the engine's responsibility,
  never the user's (design preamble).
- A handler output whose members are all `identical?` to the last written
  value skips validation, serialization and upload entirely: static inputs
  are free when handlers return cached immutable structures for constant
  members. The cache is dropped whenever the GPU buffer is recreated.
- Clojure `true`/`false` are accepted for `bool` fields and converted to `1`/`0`.
- Matrix values are expected in **column-major** order, matching OpenGL's
  default memory layout. The engine cannot detect row-major matrices — providing
  row-major data will produce incorrect rendering without any error.
- Extra keys in a struct value map follow the same rule as extra top-level keys:
  warning, ignored.

---

## Multi-view Rendering

A GLSL variable name uniquely identifies a shader input across all programs.
When a scene requires multiple views in the same frame (shadow map pass,
split-screen, reflections), each view must use a **distinct variable name** in
the GLSL source and a corresponding `reg-input` call:

```glsl
uniform Camera       { mat4 view; mat4 projection; }; // main view
uniform ShadowCamera { mat4 view; mat4 projection; }; // shadow pass
```

```clojure
(reg-input "Camera"       handler-main   {:step :step/frame})
(reg-input "ShadowCamera" handler-shadow {:step :step/frame})
```

The engine emits a warning when two programs declare blocks with identical
structure under different names, as this is the likely pattern for multi-view
rendering and may indicate a missing `reg-input`.

---

## Summary Table

| GLSL declaration    | Handler value               |
|---------------------|-----------------------------|
| `float foo`         | `1.5`                       |
| `vec3 foo`          | `[x y z]`                   |
| `mat4 foo`          | `[m0 m1 ... m15]`           |
| `struct S {...}`    | `{"field" value ...}`       |
| `float foo[N]`      | `[v0 v1 ... vN-1]`          |
| `vec3 foo[N]`       | `[[x y z] [x y z] ...]`     |
| `mat4 foo[N]`       | `[[m0..m15] [m0..m15] ...]` |
| `S foo[N]`          | `[{"field" value} ...]`     |
