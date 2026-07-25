#!/usr/bin/env python3
"""Convert a skinned glTF model to the Epiktetos skeletal EDN schema.

Usage: gltf2edn.py <model.gltf|model.glb> <output.edn>

Both the JSON form (.gltf + .bin) and the binary container (.glb,
embedded buffer and textures) are accepted.

Merges every skinned primitive (POSITION + JOINTS_0) of every mesh
into a single vertex/index list, and reads the first skin (joints,
inverseBindMatrices) plus every animation channel targeting a skin
joint with a LINEAR rotation/translation/scale sampler. Joints are
re-ordered parents first, as the schema requires; vertex joints,
inverse binds and animation targets are remapped accordingly. Each
vertex carries a :color, the baseColorFactor of its primitive's
material (white by default) — the flat-colored multi-material style
of low-poly asset packs comes through as per-vertex colors.

The product of the node transforms above the skeleton root (the
object stance in the source scene) is emitted as :root-transform when
it is not the identity.

When primitives carry TEXCOORD_0, each vertex gets a :uv in GL
convention (v flipped, matching textures loaded bottom-up) and the
first base color texture image found is copied next to the output
EDN as <output-stem>-albedo.<ext>, referenced by the model :texture
key.

Output schema (see ai-exercices/model_viewer.clj):
{:vertices      [{:position [x y z] :normal [x y z] :uv [u v]
                  :color [r g b] :joints [j0 j1 j2 j3]
                  :weights [w0 w1 w2 w3]} ...]
 :indices       [i0 i1 i2 ...]
 :skeleton      [{:name s :parent idx-or-nil :translation [x y z]
                  :rotation [x y z w] :scale [x y z]} ...]
 :inverse-binds [[m0 ... m15] ...]
 :animations    {name {:duration secs
                       :channels [{:joint idx :path :rotation
                                   :keyframes [[t value] ...]}]}}}

Only a summary is printed; the model data never goes to stdout.
"""

import base64
import json
import struct
import sys
from pathlib import Path

COMPONENT = {5120: ("b", 1), 5121: ("B", 1), 5122: ("h", 2),
             5123: ("H", 2), 5125: ("I", 4), 5126: ("f", 4)}
NCOMP = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def load_gltf(path):
    """Parses a .gltf (JSON) or .glb (binary container) model file.
    Returns (gltf dict, embedded binary chunk or None)."""
    data = path.read_bytes()
    if data[:7] == b"BLENDER" or path.suffix.lower() == ".blend":
        sys.exit(f"{path} is a .blend file; convert it with: "
                 f"python3 bin/blend2edn.py {path} <output.edn>")
    if data[:4] != b"glTF":
        try:
            return json.loads(data.decode("utf-8")), None
        except (UnicodeDecodeError, json.JSONDecodeError):
            sys.exit(f"{path} is neither glTF JSON nor a GLB container")
    _magic, _version, length = struct.unpack_from("<III", data, 0)
    offset, json_chunk, bin_chunk = 12, None, None
    while offset < length:
        chunk_length, chunk_type = struct.unpack_from("<II", data, offset)
        chunk = data[offset + 8:offset + 8 + chunk_length]
        if chunk_type == 0x4E4F534A:
            json_chunk = chunk
        elif chunk_type == 0x004E4942:
            bin_chunk = chunk
        offset += 8 + chunk_length
    return json.loads(json_chunk), bin_chunk


def load_buffers(gltf, base_dir, bin_chunk=None):
    buffers = []
    for buf in gltf["buffers"]:
        uri = buf.get("uri")
        if uri is None:
            buffers.append(bin_chunk)
        elif uri.startswith("data:"):
            buffers.append(base64.b64decode(uri.split(",", 1)[1]))
        else:
            buffers.append((base_dir / uri).read_bytes())
    return buffers


def read_accessor(gltf, buffers, idx):
    acc = gltf["accessors"][idx]
    view = gltf["bufferViews"][acc["bufferView"]]
    buf = buffers[view.get("buffer", 0)]
    fmt, csize = COMPONENT[acc["componentType"]]
    n = NCOMP[acc["type"]]
    stride = view.get("byteStride", csize * n)
    base = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    out = []
    for i in range(acc["count"]):
        vals = struct.unpack_from("<" + fmt * n, buf, base + i * stride)
        if acc.get("normalized"):
            div = {"B": 255.0, "H": 65535.0, "b": 127.0, "h": 32767.0}[fmt]
            vals = tuple(v / div for v in vals)
        out.append(vals if n > 1 else vals[0])
    return out


def base_color_image(gltf, buffers, prim, base_dir):
    """Raw bytes and file extension of the primitive's base color
    texture image. Returns (None, None) when the material has none."""
    if "material" not in prim:
        return None, None
    mat = gltf.get("materials", [{}])[prim["material"]]
    tex_info = mat.get("pbrMetallicRoughness", {}).get("baseColorTexture")
    if tex_info is None:
        return None, None
    image = gltf["images"][gltf["textures"][tex_info["index"]]["source"]]
    if "uri" in image:
        uri = image["uri"]
        if uri.startswith("data:"):
            data = base64.b64decode(uri.split(",", 1)[1])
            ext = ".png" if data.startswith(b"\x89PNG") else ".jpg"
        else:
            data = (base_dir / uri).read_bytes()
            ext = Path(uri).suffix or ".png"
    else:
        view = gltf["bufferViews"][image["bufferView"]]
        base = view.get("byteOffset", 0)
        data = buffers[view.get("buffer", 0)][base:base + view["byteLength"]]
        ext = ".png" if data.startswith(b"\x89PNG") else ".jpg"
    return data, ext


IDENTITY = [1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0]


def mat_mul(a, b):
    """Column-major mat4 product a * b."""
    return [sum(a[k * 4 + r] * b[c * 4 + k] for k in range(4))
            for c in range(4) for r in range(4)]


def node_matrix(node):
    """Column-major local transform of a glTF node (matrix or TRS)."""
    if "matrix" in node:
        return list(node["matrix"])
    tx, ty, tz = node.get("translation", [0, 0, 0])
    qx, qy, qz, qw = node.get("rotation", [0, 0, 0, 1])
    sx, sy, sz = node.get("scale", [1, 1, 1])
    m = [1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy + qz * qw), 2 * (qx * qz - qy * qw), 0.0,
         2 * (qx * qy - qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz + qx * qw), 0.0,
         2 * (qx * qz + qy * qw), 2 * (qy * qz - qx * qw), 1 - 2 * (qx * qx + qy * qy), 0.0,
         tx, ty, tz, 1.0]
    for c, s in enumerate((sx, sy, sz)):
        for r in range(3):
            m[c * 4 + r] *= s
    return m


def ancestors_matrix(nodes, parent_of, node_id):
    """Product of the local transforms of node_id's ancestors, scene
    root first. Returns a column-major mat4."""
    chain = []
    p = parent_of.get(node_id)
    while p is not None:
        chain.append(p)
        p = parent_of.get(p)
    m = IDENTITY
    for ni in reversed(chain):
        m = mat_mul(m, node_matrix(nodes[ni]))
    return m


def joint_order(gltf, joints):
    """Skin joint indices sorted parents first, and the node->parent map."""
    parent_of = {}
    for ni, node in enumerate(gltf["nodes"]):
        for child in node.get("children", []):
            parent_of[child] = ni
    joint_set = set(joints)

    def depth(node):
        d, p = 0, parent_of.get(node)
        while p is not None:
            if p in joint_set:
                d += 1
            p = parent_of.get(p)
        return d

    order = sorted(range(len(joints)), key=lambda i: depth(joints[i]))
    return order, parent_of


def build_skeleton(gltf, joints, order, parent_of, remap):
    joint_set = set(joints)
    skeleton = []
    for oi in order:
        node_id = joints[oi]
        node = gltf["nodes"][node_id]
        p = parent_of.get(node_id)
        while p is not None and p not in joint_set:
            p = parent_of.get(p)
        skeleton.append({
            ":name": node.get("name", f"joint{oi}"),
            ":parent": remap[joints.index(p)] if p is not None else None,
            ":translation": [round(v, 5) for v in node.get("translation", [0, 0, 0])],
            ":rotation": [round(v, 6) for v in node.get("rotation", [0, 0, 0, 1])],
            ":scale": [round(v, 5) for v in node.get("scale", [1, 1, 1])]})
    return skeleton


def build_animations(gltf, buffers, joints, remap):
    joint_set = set(joints)
    animations = {}
    skipped = 0
    for ai, anim in enumerate(gltf.get("animations", [])):
        channels, duration = [], 0.0
        for ch in anim["channels"]:
            node, path = ch["target"].get("node"), ch["target"]["path"]
            sampler = anim["samplers"][ch["sampler"]]
            if (node not in joint_set
                    or path not in ("rotation", "translation", "scale")
                    or sampler.get("interpolation", "LINEAR") != "LINEAR"):
                skipped += 1
                continue
            times = read_accessor(gltf, buffers, sampler["input"])
            values = read_accessor(gltf, buffers, sampler["output"])
            duration = max(duration, times[-1])
            digits = 6 if path == "rotation" else 5
            channels.append({
                ":joint": remap[joints.index(node)],
                ":path": ":" + path,
                ":keyframes": [[round(t, 5), [round(v, digits) for v in val]]
                               for t, val in zip(times, values)]})
        animations[anim.get("name", f"clip{ai}")] = {
            ":duration": round(duration, 5), ":channels": channels}
    for name in [n for n in animations
                 if "|" in n and n.split("|")[-1] in animations]:
        del animations[name]
    return animations, skipped


def edn(v):
    if v is None:
        return "nil"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, int):
        return str(v)
    if isinstance(v, float):
        return repr(v)
    if isinstance(v, str):
        return v if v.startswith(":") else json.dumps(v)
    if isinstance(v, (list, tuple)):
        return "[" + " ".join(edn(x) for x in v) + "]"
    if isinstance(v, dict):
        return "{" + " ".join(f"{edn(k)} {edn(x)}" for k, x in v.items()) + "}"
    raise TypeError(f"no EDN form for {type(v)}")


def main():
    gltf_path, out_path = Path(sys.argv[1]), Path(sys.argv[2])
    gltf, bin_chunk = load_gltf(gltf_path)
    buffers = load_buffers(gltf, gltf_path.parent, bin_chunk)

    prims = [p for mesh in gltf["meshes"] for p in mesh["primitives"]
             if "POSITION" in p["attributes"] and "JOINTS_0" in p["attributes"]]
    if not prims:
        sys.exit("no skinned primitive found")

    skin = gltf["skins"][0]
    joints = skin["joints"]
    order, parent_of = joint_order(gltf, joints)
    remap = {old: new for new, old in enumerate(order)}

    vertices, indices = [], []
    has_uvs, any_factor, tex_prim = False, False, None
    for prim in prims:
        attrs = prim["attributes"]
        positions = read_accessor(gltf, buffers, attrs["POSITION"])
        normals = (read_accessor(gltf, buffers, attrs["NORMAL"])
                   if "NORMAL" in attrs else None)
        joints0 = read_accessor(gltf, buffers, attrs["JOINTS_0"])
        weights0 = read_accessor(gltf, buffers, attrs["WEIGHTS_0"])
        uvs = (read_accessor(gltf, buffers, attrs["TEXCOORD_0"])
               if "TEXCOORD_0" in attrs else None)
        pbr = (gltf.get("materials", [{}])[prim["material"]]
               if "material" in prim else {}).get("pbrMetallicRoughness", {})
        factor = pbr.get("baseColorFactor", [1.0, 1.0, 1.0, 1.0])[:3]
        if pbr.get("baseColorTexture") is not None and tex_prim is None:
            tex_prim = prim
        if factor != [1.0, 1.0, 1.0]:
            any_factor = True
        if uvs is not None:
            has_uvs = True
        base = len(vertices)
        for i, (pos, js, ws) in enumerate(zip(positions, joints0, weights0)):
            vertex = {":position": [round(v, 5) for v in pos]}
            if normals is not None:
                vertex[":normal"] = [round(v, 4) for v in normals[i]]
            if uvs is not None:
                u, v = uvs[i]
                vertex[":uv"] = [round(u, 5), round(1.0 - v, 5)]
            vertex[":color"] = [round(c, 4) for c in factor]
            vertex[":joints"] = [remap[int(j)] for j in js]
            vertex[":weights"] = [round(float(w), 5) for w in ws]
            vertices.append(vertex)
        if "indices" in prim:
            indices += [base + int(i)
                        for i in read_accessor(gltf, buffers, prim["indices"])]
        else:
            indices += list(range(base, base + len(positions)))

    skeleton = build_skeleton(gltf, joints, order, parent_of, remap)
    ibms = read_accessor(gltf, buffers, skin["inverseBindMatrices"])
    inverse_binds = [[round(v, 6) for v in ibms[oi]] for oi in order]
    animations, skipped = build_animations(gltf, buffers, joints, remap)

    model = {":vertices": vertices,
             ":indices": indices,
             ":skeleton": skeleton,
             ":inverse-binds": inverse_binds,
             ":animations": animations}
    root_tf = ancestors_matrix(gltf["nodes"], parent_of, joints[order[0]])
    if any(abs(a - b) > 1e-6 for a, b in zip(root_tf, IDENTITY)):
        model[":root-transform"] = [round(v, 6) for v in root_tf]

    texture_name = None
    if has_uvs and tex_prim is not None:
        tex_bytes, tex_ext = base_color_image(gltf, buffers, tex_prim,
                                              gltf_path.parent)
        if tex_bytes is not None:
            texture_name = f"{out_path.stem}-albedo{tex_ext}"
            (out_path.parent / texture_name).write_bytes(tex_bytes)
            model[":texture"] = texture_name

    out_path.write_text(edn(model) + "\n")

    positions = [v[":position"] for v in vertices]
    mins = [min(p[i] for p in positions) for i in range(3)]
    maxs = [max(p[i] for p in positions) for i in range(3)]
    print(f"joints:   {len(skeleton)} "
          f"(roots: {[j[':name'] for j in skeleton if j[':parent'] is None]})")
    print(f"vertices: {len(vertices)}, triangles: {len(indices) // 3}, "
          f"primitives merged: {len(prims)}")
    print(f"colors:   {'material factors' if any_factor else 'white'}")
    print(f"uvs:      {'yes (GL convention, v flipped)' if has_uvs else 'none'}")
    print(f"texture:  {texture_name if texture_name else 'none'}")
    print(f"stance:   "
          f"{'root transform emitted' if ':root-transform' in model else 'identity'}")
    print(f"bounds:   min {[round(v, 1) for v in mins]} "
          f"max {[round(v, 1) for v in maxs]}")
    for name, clip in animations.items():
        print(f"clip:     {name!r} {clip[':duration']}s "
              f"({len(clip[':channels'])} channels)")
    if skipped:
        print(f"skipped:  {skipped} channels (non-joint target or non-LINEAR)")
    print(f"written:  {out_path} ({out_path.stat().st_size // 1024} KiB)")


if __name__ == "__main__":
    main()
