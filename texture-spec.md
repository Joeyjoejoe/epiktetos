# Texture Input Specification

## Overview

This specification extends `input-spec.md` and `uniform-spec.md` to
**textures**, read by shaders through opaque sampler uniforms:

```glsl
uniform sampler2D uAlbedo;

void main()
{
    FragColor = texture(uAlbedo, uv);
}
```

Three concepts, three owners:

| Concept | What it is | Owner |
|---|---|---|
| **Texture** | the data: an image or any gridded values, with format and mipmaps | `reg-texture` — a heavyweight resource with an id, like a program |
| **Sampler** | how the data is read: filtering, wrapping, anisotropy | `reg-input` options — the read configuration belongs to the read site |
| **Unit** | the slot connecting both to a `sampler` uniform | the engine — allocated like binding points, never user-facing |

A usable shader texture is: **texture (data) + sampler (read config),
attached by the engine to a texture unit**, whose index is written into
the opaque uniform. Sampler objects are an implementation detail: the
engine creates and deduplicates them from the `:sampler/*` options.

**The handler of a texture input returns a texture id** — the keyword
chosen by the user at `reg-texture` — **never a unit index**. Units do
not exist in the API.

This specification covers 2D textures read as inputs. Out of scope,
each for a later chapter: render targets (textures as outputs — the
framebuffer/group work), cubemaps, 3D and array textures, compressed
formats, shadow comparison samplers, and image load/store (compute).

---

## When To Use

Textures are the second bulk-data channel next to SSBOs. The rule:

| Data | Use |
|---|---|
| Structured records, variable counts, exact reads | **SSBO** (runtime arrays) |
| 2D/gridded data meant to be **sampled**: filtered, interpolated, mipmapped, wrapped | **texture** |
| Images, of course | **texture** |

If the shader benefits from `texture(u, uv)` semantics — smooth
interpolation between cells, automatic level-of-detail, coordinate
wrapping — it is a texture. If it indexes exact elements, it is an
SSBO.

---

## Registration: reg-texture

```clojure
(reg-texture :zombie-skin {:file "textures/zombie.png"})

(reg-texture :heightmap
  {:data   {:width 256 :height 256 :pixel-format :r32f :pixels heights}
   :mips?  false})

(reg-texture fx :noise {...})   ; pure form, threads the fx map
```

Like every resource of the API, `reg-texture` exists in the immediate
and pure forms, and registering an existing id **replaces** the texture
(entities and inputs untouched, like `reg-p`).

- `id` — keyword, the texture identity across the whole API.
- `source` (exactly one of):
  - `:file` — classpath-relative image path, decoded by the engine
    (PNG, JPG). Dimensions and pixel format come from the file.
  - `:data` — map with `:width`, `:height`, `:pixel-format` and
    `:pixels` (flat sequence, row-major).
- Options:

| Key | Default | Description |
|---|---|---|
| `:format` | `:srgb8-alpha8` for color files, matches `:pixel-format` for data | GPU internal format: `:rgba8`, `:srgb8-alpha8`, `:r8`, `:rg8`, `:rgba16f`, `:rgba32f`, `:r32f`, `:r32i`, ... The sRGB default is correct because the engine renders gamma-aware: the window framebuffer is sRGB-capable and `GL_FRAMEBUFFER_SRGB` is enabled at startup (hardware conversion, no performance cost) |
| `:mips?` | `true` for files, `false` for data | Generate the full mipmap chain at upload |
| `:flip?` | `true` for files | Flip rows at load: image files have a top-left origin, GL UVs a bottom-left one |
| `:swizzle` | `[:r :g :b :a]` | Channel remapping, from `:r :g :b :a :zero :one` (e.g. `[:r :r :r :one]` to read a grayscale as opaque gray) |

### Registration-time validation

Errors at `reg-texture`, immediately (missing or undecodable file,
`:pixels` count not matching dimensions and pixel format, unknown
format, both or neither source key). A texture is either fully
uploaded and valid, or not registered.

Storage is immutable (`glTextureStorage2D` + upload): replacing
content means replacing the texture under its id.

---

## Reading: reg-input on a sampler uniform

Sampler uniforms are opaque uniforms of the default block. The engine
introspects them like any input and routes them to the texture
machinery (they are excluded from plain-uniform handling):

```clojure
(reg-input "uAlbedo"
           (fn [db entity] (:skin entity))
           {:step :step/entity
            :sampler/mag-filter :linear
            :sampler/anisotropy 8})
```

- Same forms, same steps, same handler signature as every input.
- **The handler returns the id of a registered texture** (keyword).
  Anything else — including a unit number — is an error.
- The varname follows the same rule as everywhere: one name, one
  input, one meaning across programs. Sampler uniforms fan out like
  plain uniforms: per-program locations receive the unit index.

### Sampler options

All optional, fixed at registration (changing how an input reads means
re-registering it — same rule as every reg-input option):

| Key | Values | Default |
|---|---|---|
| `:sampler/mag-filter` | `:nearest`, `:linear` | `:linear` |
| `:sampler/min-filter` | `:nearest`, `:linear`, `:nearest-mipmap-nearest`, `:linear-mipmap-nearest`, `:nearest-mipmap-linear`, `:linear-mipmap-linear` | `:linear-mipmap-linear` when the bound texture has mips, `:linear` otherwise |
| `:sampler/wrap` | `:repeat`, `:mirrored-repeat`, `:clamp-to-edge`, `:clamp-to-border`, `:mirror-clamp` — one value, or `[s t]` | `:repeat` |
| `:sampler/border-color` | `[r g b a]`, for `:clamp-to-border` | `[0.0 0.0 0.0 0.0]` |
| `:sampler/anisotropy` | 1 to 16 | `1` |
| `:sampler/min-lod` `:sampler/max-lod` `:sampler/lod-bias` | floats | full range, bias `0.0` |

The engine builds one GL sampler object per distinct option map and
shares it across inputs. Units, `glBindTextureUnit`, `glBindSampler`
and the uniform write are engine plumbing.

### Registration order

`reg-texture`, `reg-input` and `reg-p` may be called in any order:

- A program declaring a sampler uniform with no matching input →
  **warning** at program registration; its unit is bound to the
  engine's fallback texture.
- `reg-input` on a sampler name unknown to every program → valid,
  silent.
- The handler returning the id of a texture not registered **yet** is
  **not an error**: the engine binds its built-in fallback texture (a
  magenta and black checkerboard, unmistakable on screen), warns
  once, and self-heals — registering the texture later invalidates
  the waiting bindings, which pick it up at the next step execution.
  Registration order is entirely free, as everywhere in the engine.

---

## Runtime semantics

- The handler runs at each transition of its `:step`, like any input.
- The returned id is checked (registered texture, type compatible
  with the sampler: a `sampler2D` needs a 2D texture) and the engine
  rebinds **only when the id differs from the last bound one** — the
  unchanged-value skip applied to texture ids. A static texture costs
  one bind, ever.
- Replacing a texture (`reg-texture` on its id) or relinking a
  program invalidates the concerned bindings; the engine rebinds and
  rewrites at the next step execution. Handlers never care.
- No texture problem ever stops the session: an unknown id or a
  sampler/texture type mismatch binds the fallback checkerboard and
  warns (throttled) — the scene keeps running with the problem
  visible on screen, and fixing the registration heals it live.

---

## Summary

```clojure
(reg-texture :zombie-skin {:file "textures/zombie.png"})

(reg-p :zombie {...})                          ; declares: uniform sampler2D uAlbedo;

(reg-input "uAlbedo" (fn [db entity] (:skin entity))
           {:step :step/entity
            :sampler/anisotropy 8})

(render :walker {:program :zombie :skin :zombie-skin ...})
```

| Piece | API | Returns / holds |
|---|---|---|
| Texture data | `reg-texture :id {source+options}` | GPU storage under `:id` |
| Read config | `:sampler/*` options of `reg-input` | deduplicated GL sampler objects |
| Choice per step | input handler | **a texture id, never a unit** |
| Unit, binds, uniform write | engine | invisible |
