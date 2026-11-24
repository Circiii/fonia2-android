"""Bake the MakeHuman face and dental references for the mobile renderer.

Run with Blender in background mode:
  blender -b --factory-startup -P prepare_anatomy.py -- base.obj teeth_base.obj

Sources and the coordinate registration are documented in mouth3d/README.md.
"""
import base64
from collections import defaultdict
from pathlib import Path
import struct
import sys
import textwrap

import bmesh
import bpy
from mathutils import Vector
from mathutils.bvhtree import BVHTree


ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / 'mouth3d/src/main/kotlin/tech/fonia/mouth3d/style/AnatomyReferenceData.kt'


def read_obj(path, only_body=False):
    points, faces = [], []
    body = False
    for line in path.read_text(encoding='utf-8').splitlines():
        fields = line.split()
        if not fields:
            continue
        if fields[0] == 'v':
            points.append(tuple(float(v) * 100 for v in fields[1:4]))
        elif fields[0] == 'g':
            body = fields[1] == 'body'
        elif fields[0] == 'f' and (body or not only_body):
            faces.append(tuple(int(v.split('/')[0]) - 1 for v in fields[1:]))
    return points, faces


def face_samples(path):
    points, faces = read_obj(path, only_body=True)
    face = BVHTree.FromPolygons(points, [f for f in faces if all(598 < points[i][1] < 744 for i in f)])
    values = []
    for y in range(-44, 45, 2):
        for x in range(-52, 53, 2):
            sy = 660 + y * (1.15 if y > 0 else 1.05)
            rays = []
            corner = abs(x) > 18 and 653 < sy < 669
            dy_filter = 4 if corner else 2
            for dx, dy, weight in ((0, 0, 2), (-2, 0, 1), (2, 0, 1), (0, dy_filter, 2), (0, -dy_filter, 2)):
                hit, *_ = face.ray_cast(Vector((x + dx, sy + dy, 250)), Vector((0, 0, -1)))
                if hit is not None:
                    rays.extend([max(-42, min(18, (hit.z - 151) * 0.96 + 2.2))] * weight)
            depth = sum(rays) / len(rays) if rays else -42
            values.append(round(depth * 100))
    return struct.pack(f'<{len(values)}h', *values)


def component_faces(faces):
    incident = defaultdict(list)
    for i, face in enumerate(faces):
        for v in face:
            incident[v].append(i)
    remaining = set(range(len(faces)))
    while remaining:
        first = min(remaining)
        remaining.remove(first)
        stack, component = [first], []
        while stack:
            face = faces[stack.pop()]
            component.append(face)
            for v in face:
                for i in incident[v]:
                    if i in remaining:
                        remaining.remove(i)
                        stack.append(i)
        yield component


def tooth_data(path):
    points, faces = read_obj(path)
    quadrants = defaultdict(list)
    for shell in component_faces(faces):
        ids = sorted({v for face in shell for v in face})
        if len(ids) != 63:  # The separate source gum shell is replaced by the mobile cavity.
            continue
        x, y, z = [sum(points[i][a] for i in ids) / len(ids) for a in range(3)]
        upper = y - 0.22 * (131.9 - z) > 622
        quadrants[upper, x > 0].append((z, ids, shell))

    output = bytearray()
    for upper in (True, False):
        for right in (False, True):
            quadrant = sorted(quadrants[upper, right], reverse=True)
            if len(quadrant) != 8:
                raise ValueError('Expected eight source teeth per quadrant')
            for depth, ids, shell in quadrant[:7]:
                mesh = bmesh.new()
                verts = {}
                for i in ids:
                    x, y, z = points[i]
                    # Register the two arches to Fonia's upper incisal edge and retain the overjet.
                    # Broaden the four front teeth without crowding the posterior cavity walls.
                    front = max(0, min(1, (depth - 104) / 23))
                    # Register the occlusal plane to the teaching camera and jaw pivot.
                    height = (y - 620.9 + 0.19 * (z - 131.9)) * 0.92
                    verts[i] = mesh.verts.new((x * (1.08 + 0.15 * front), height - (0 if upper else 2.9), z - 130.4))
                for face in shell:
                    mesh.faces.new([verts[i] for i in face])
                boundary = [edge for edge in mesh.edges if edge.is_boundary]
                if boundary:
                    bmesh.ops.holes_fill(mesh, edges=boundary)
                # Subdivide the original quad crown before triangulation. This rounds the line
                # angles and individual incisal edges, which are visible at phone close-up scale.
                data = bpy.data.meshes.new("Crown")
                mesh.to_mesh(data)
                obj = bpy.data.objects.new("Crown", data)
                bpy.context.scene.collection.objects.link(obj)
                mod = obj.modifiers.new("Enamel", 'SUBSURF')
                mod.levels = 1
                evaluated = obj.evaluated_get(bpy.context.evaluated_depsgraph_get())
                refined = evaluated.to_mesh()
                mesh.clear()
                mesh.from_mesh(refined)
                evaluated.to_mesh_clear()
                bpy.data.objects.remove(obj, do_unlink=True)
                bpy.data.meshes.remove(data)
                bmesh.ops.triangulate(mesh, faces=list(mesh.faces))
                bmesh.ops.recalc_face_normals(mesh, faces=list(mesh.faces))
                mesh.normal_update()
                mesh.verts.index_update()
                if any(not edge.is_manifold for edge in mesh.edges) or mesh.calc_volume(signed=True) <= 0:
                    raise ValueError('Expected a closed, outward wound crown')

                output.extend(struct.pack('<BHH', 1 if upper else 0, len(mesh.verts), len(mesh.faces)))
                ys = [v.co.y for v in mesh.verts]
                lo, span = min(ys), max(ys) - min(ys)
                for v in mesh.verts:
                    neck = (v.co.y - lo) / span
                    tint = round((neck if upper else 1 - neck) * 255)
                    output.extend(struct.pack('<3h3bB', *(round(c * 100) for c in v.co), *(round(c * 127) for c in v.normal), tint))
                for face in mesh.faces:
                    output.extend(struct.pack('<3H', *(v.index for v in face.verts)))
                mesh.free()
    return output


def constant(name, raw):
    lines = textwrap.wrap(base64.b64encode(raw).decode('ascii'), 120)
    encoded = '\n'.join('        ' + line for line in lines)
    return f'    val {name} = Base64.getMimeDecoder().decode(\"\"\"\n{encoded}\n    \"\"\")'


def main():
    face, teeth = map(Path, sys.argv[sys.argv.index('--') + 1:])
    skin = face_samples(face)
    crowns = tooth_data(teeth)
    TARGET.write_text('\n'.join((
        'package tech.fonia.mouth3d.style', '', 'import java.util.Base64', '',
        '// Generated by tools/mouth3d/prepare_anatomy.py from MakeHuman CC0 assets.',
        'internal object AnatomyReferenceData {', constant('face', skin), '}', '',
    )), encoding='utf-8', newline='\n')
    (ROOT / 'mouth3d/src/main/resources/fonia/perioral/crowns.bin').write_bytes(crowns)
    print(f'{len(skin)} bytes of facial depths, {len(crowns)} bytes of crowns')


if __name__ == '__main__':
    main()
