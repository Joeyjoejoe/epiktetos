#!/usr/bin/env python3
"""Convert a model file to the Epiktetos skeletal EDN schema through
a headless Blender.

Usage: blend2edn.py <model.blend|model.glb|model.gltf> <output.edn>

Drives a headless Blender to (re)export the model as glTF Separate
(meshes, skin, all animation actions, modifiers applied, materials
rewired so the base color texture or flat color survives, non-PNG
textures converted), then runs gltf2edn.py on the result. Plain .glb/.gltf
files can also go straight through gltf2edn.py; the Blender pass is
useful when the source needs fixing on the way. Requires blender on
the PATH.
"""

import subprocess
import sys
import tempfile
from pathlib import Path

EXPORT_SCRIPT = """
import bpy
import json
import os
import sys
import urllib.parse

args = sys.argv[sys.argv.index("--") + 1:]
out_dir = args[0]
gltf_path = os.path.join(out_dir, "model.gltf")

if len(args) > 1:
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=args[1])


def ensure_principled(mat):
    '''Rewires the material onto a Principled BSDF, the only shader
    the glTF exporter reads: wires the image texture to its base
    color, or copies the flat color of a non-Principled shader
    (Diffuse BSDF and friends) that would otherwise export as plain
    white. The .blend is never saved.'''
    if not mat.use_nodes:
        return
    nodes = mat.node_tree.nodes
    links = mat.node_tree.links
    image = next((n for n in nodes if n.type == "TEX_IMAGE" and n.image), None)
    principled = next((n for n in nodes if n.type == "BSDF_PRINCIPLED"), None)
    if principled is not None and (image is None
                                   or principled.inputs["Base Color"].links):
        return
    if principled is None:
        source = next((n for n in nodes
                       if ("BSDF" in n.type or n.type == "EMISSION")
                       and "Color" in n.inputs), None)
        principled = nodes.new("ShaderNodeBsdfPrincipled")
        if source is not None:
            principled.inputs["Base Color"].default_value = (
                tuple(source.inputs["Color"].default_value))
    if image is not None:
        links.new(image.outputs["Color"], principled.inputs["Base Color"])
    output = next((n for n in nodes if n.type == "OUTPUT_MATERIAL"), None)
    if output is not None:
        links.new(principled.outputs["BSDF"], output.inputs["Surface"])
        print("rewired material for export:", mat.name)


for mat in bpy.data.materials:
    ensure_principled(mat)

bpy.ops.export_scene.gltf(filepath=gltf_path,
                          export_format="GLTF_SEPARATE",
                          export_apply=True,
                          export_animations=True,
                          export_skins=True)

with open(gltf_path) as f:
    doc = json.load(f)

for image in doc.get("images", []):
    uri = image.get("uri")
    if not uri or uri.startswith("data:") or uri.lower().endswith(".png"):
        continue
    src = os.path.join(out_dir, urllib.parse.unquote(uri))
    dst = os.path.splitext(src)[0] + ".png"
    img = bpy.data.images.load(src)
    img.filepath_raw = dst
    img.file_format = "PNG"
    img.save()
    image["uri"] = os.path.basename(dst)
    image.pop("mimeType", None)
    print("converted texture to PNG:", os.path.basename(dst))

with open(gltf_path, "w") as f:
    json.dump(doc, f)
"""


def main():
    model_path, out_path = Path(sys.argv[1]), Path(sys.argv[2])
    with tempfile.TemporaryDirectory() as tmp:
        script = Path(tmp) / "export.py"
        script.write_text(EXPORT_SCRIPT)
        command = ["blender", "-b"]
        chunks = [tmp]
        if model_path.suffix.lower() in (".glb", ".gltf"):
            chunks.append(str(model_path.resolve()))
        else:
            command.append(str(model_path))
        command += ["--python", str(script), "--", *chunks]
        result = subprocess.run(command, capture_output=True, text=True)
        for line in result.stdout.splitlines():
            if line.startswith(("converted texture", "rewired material")):
                print(line)
        if result.returncode != 0 or not (Path(tmp) / "model.gltf").exists():
            print(result.stdout)
            print(result.stderr, file=sys.stderr)
            sys.exit("blender export failed")
        subprocess.run(
            [sys.executable, str(Path(__file__).parent / "gltf2edn.py"),
             str(Path(tmp) / "model.gltf"), str(out_path)],
            check=True)


if __name__ == "__main__":
    main()
