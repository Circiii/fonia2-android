"""Build an editable Blender scene from the mobile renderer's indexed mouth.

Run after preview.py style OUT --mesh:
    blender -b --factory-startup -P blender_study.py -- OUT/mesh OUT [--render]
"""
import argparse
from pathlib import Path
import struct
import sys

import bmesh
import bpy
from mathutils import Vector


STRIDE = 15
ASSETS = Path(__file__).resolve().parents[2] / 'mouth3d/src/main/resources/fonia/perioral'
MATERIAL_NAMES = ("Piele", "Buze", "Mucoasă", "Smalț", "Limbă")
POSE_NAMES = {"s": "S", "sh": "Ș", "z": "Z", "j": "J", "ts": "Ț", "ce": "CE", "ge": "GE", "r": "R", "l": "L"}


def read_mouth(path):
    raw = path.read_bytes()
    vertices, head_end, teeth_end, tongue_end = struct.unpack_from('>4I', raw)
    count = vertices * STRIDE
    data = struct.unpack_from(f'>{count}f', raw, 16)
    faces = struct.unpack_from(f'>{tongue_end}I', raw, 16 + count * 4)
    return data, faces, (head_end, teeth_end, tongue_end)


def world(data, index):
    x, y, z = data[index * STRIDE:index * STRIDE + 3]
    return x / 1000, -z / 1000, y / 1000


def material(name, roughness, scatter, grain_scale, grain_strength, lip=False):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nodes, links = mat.node_tree.nodes, mat.node_tree.links
    bsdf = next(n for n in nodes if n.type == 'BSDF_PRINCIPLED')
    bsdf.inputs['Roughness'].default_value = roughness
    bsdf.inputs['Subsurface Weight'].default_value = scatter
    bsdf.inputs['Subsurface Radius'].default_value = (1, 0.36, 0.21)
    bsdf.inputs['Subsurface Scale'].default_value = 0.0016
    bsdf.inputs['Specular IOR Level'].default_value = 0.32
    pigment = nodes.new('ShaderNodeVertexColor')
    pigment.layer_name = 'Pigment'
    links.new(pigment.outputs['Color'], bsdf.inputs['Base Color'])
    if grain_strength > 0:
        coords = nodes.new('ShaderNodeTexCoord')
        noise = nodes.new('ShaderNodeTexNoise')
        noise.inputs['Scale'].default_value = grain_scale
        noise.inputs['Detail'].default_value = 2.0
        surface = coords.outputs['Object']
        if lip:
            pores = nodes.new('ShaderNodeVectorMath')
            pores.operation = 'MULTIPLY'
            pores.inputs[1].default_value = (2.5, 0.7, 0.4)
            links.new(surface, pores.inputs[0])
            surface = pores.outputs['Vector']
        links.new(surface, noise.inputs['Vector'])
        bump = nodes.new('ShaderNodeBump')
        bump.inputs['Strength'].default_value = grain_strength
        bump.inputs['Distance'].default_value = 0.000065
        links.new(noise.outputs['Fac'], bump.inputs['Height'])
        links.new(bump.outputs['Normal'], bsdf.inputs['Normal'])
    return mat


def piece(name, source, poses, triangles, materials, kind, collection):
    # The mobile ring poles use coincident vertices. Weld them once for Blender and reuse the stable vertex map.
    unique, index_map, originals, faces, surface_ids, uv_faces = {}, {}, [], [], [], []
    for k in range(0, len(triangles), 3):
        src = triangles[k:k + 3]
        for i in src:
            if i in index_map:
                continue
            pos = tuple(round(x, 7) for x in world(source, i))
            if pos not in unique:
                unique[pos] = len(originals)
                originals.append(i)
            index_map[i] = unique[pos]
        face = tuple(index_map[i] for i in src)
        if len(set(face)) < 3:
            continue
        faces.append(face)
        if kind == 0:
            scanned = source[src[0] * STRIDE + 10] == 6
            moisture = sum(source[i * STRIDE + 11] for i in src) / 3
            surface_ids.append((1 if moisture > .22 else 0) if scanned else 2)
            uv_faces.append([(source[i * STRIDE + 13], 1 - source[i * STRIDE + 14]) for i in src] if scanned else None)
        else:
            surface_ids.append(kind)

    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata([world(source, i) for i in originals], [], faces)
    if kind == 0:
        uv = mesh.uv_layers.new(name='Surface')
        for polygon, coords in zip(mesh.polygons, uv_faces):
            # The lining and rear closure use untextured materials/hidden triangles. Give them a
            # non-degenerate local planar projection, even for an edge-on or nearly vertical triangle.
            area = 0 if coords is None else abs((coords[1][0]-coords[0][0])*(coords[2][1]-coords[0][1]) -
                                                (coords[1][1]-coords[0][1])*(coords[2][0]-coords[0][0]))
            if area < 1e-10:
                axis = max(range(3), key=lambda k: abs(polygon.normal[k]))
                axes = [k for k in range(3) if k != axis]
                coords = [tuple(.5 + mesh.vertices[i].co[k] * 4 for k in axes) for i in polygon.vertices]
            for li, coord in zip(polygon.loop_indices, coords):
                uv.data[li].uv = coord
    slots = {}
    for surface in sorted(set(surface_ids)):
        slots[surface] = len(mesh.materials)
        mesh.materials.append(materials[surface])
    pigment = mesh.color_attributes.new(name='Pigment', type='FLOAT_COLOR', domain='POINT')
    for j, i in enumerate(originals):
        offset = i * STRIDE + 6
        pigment.data[j].color_srgb = (*source[offset:offset + 3], 1)
    for polygon, surface in zip(mesh.polygons, surface_ids):
        polygon.material_index = slots[surface]
        polygon.use_smooth = True
    bm = bmesh.new()
    bm.from_mesh(mesh)
    bmesh.ops.recalc_face_normals(bm, faces=list(bm.faces))
    bm.to_mesh(mesh)
    bm.free()
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    collection.objects.link(obj)

    obj.shape_key_add(name='Repaus')
    for key, coords in poses.items():
        base = key.removesuffix('_closure')
        label = POSE_NAMES[base] + (' · contact' if key.endswith('_closure') else '')
        target = obj.shape_key_add(name=label, from_mix=False)
        target.data.foreach_set('co', [x for i in originals for x in world(coords, i)])
        target.value = 0
    return obj


def scan_material(mat, pigment, relief):
    nodes, links = mat.node_tree.nodes, mat.node_tree.links
    bsdf = next(n for n in nodes if n.type == 'BSDF_PRINCIPLED')
    colour = nodes.new('ShaderNodeTexImage')
    colour.image = pigment
    links.new(colour.outputs['Color'], bsdf.inputs['Base Color'])
    image = nodes.new('ShaderNodeTexImage')
    image.image = relief
    # Mobile's UV v grows down; a Blender UV grows up. Invert the tangent-space Y component.
    invert_y = nodes.new('ShaderNodeVectorMath')
    invert_y.operation = 'MULTIPLY_ADD'
    invert_y.inputs[1].default_value = (1, -1, 1)
    invert_y.inputs[2].default_value = (0, 1, 0)
    links.new(image.outputs['Color'], invert_y.inputs[0])
    normal = nodes.new('ShaderNodeNormalMap')
    normal.inputs['Strength'].default_value = .72
    links.new(invert_y.outputs['Vector'], normal.inputs['Color'])
    links.new(normal.outputs['Normal'], bsdf.inputs['Normal'])


def point_at(obj, location):
    obj.rotation_euler = (Vector(location) - obj.location).to_track_quat('-Z', 'Y').to_euler()


def area(collection, name, location, color, energy, size):
    data = bpy.data.lights.new(name, 'AREA')
    data.energy, data.color, data.shape, data.size = energy, color, 'DISK', size
    light = bpy.data.objects.new(name, data)
    collection.objects.link(light)
    light.location = location
    point_at(light, (0, -0.006, 0))


def pose_control(collection, objects):
    control = bpy.data.objects.new('Alege sunetul', None)
    control.empty_display_type = 'PLAIN_AXES'
    control.empty_display_size = .012
    control.location = (-.053, 0, -.040)
    collection.objects.link(control)
    keys = [key.name for key in objects[0].data.shape_keys.key_blocks]
    control['Sunet'] = keys.index('CE')
    control.id_properties_ui('Sunet').update(
        min=0, max=len(keys) - 1, description=' · '.join(f'{i}: {key}' for i, key in enumerate(keys)),
    )
    for obj in objects:
        for index, key in enumerate(obj.data.shape_keys.key_blocks):
            if index == 0:
                continue
            driver = key.driver_add('value').driver
            driver.type = 'SCRIPTED'
            variable = driver.variables.new()
            variable.name = 'sound'
            variable.type = 'SINGLE_PROP'
            variable.targets[0].id = control
            variable.targets[0].data_path = '["Sunet"]'
            driver.expression = f'1.0 if sound == {index} else 0.0'
    text = bpy.data.texts.new('Citește aici')
    text.write('Fonia · Gura articulatorie\n\nAlege sunetul > Object Properties > Custom Properties > Sunet\n\n')
    text.write('\n'.join(f'{i:2}. {key}' for i, key in enumerate(keys)))
    return control


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--render', action='store_true')
    args = parser.parse_args(sys.argv[sys.argv.index('--') + 1:])
    scene = bpy.context.scene
    for obj in tuple(scene.objects):
        bpy.data.objects.remove(obj, do_unlink=True)

    study = bpy.data.collections.new('Anatomie articulatorie')
    rig = bpy.data.collections.new('Studio')
    scene.collection.children.link(study)
    scene.collection.children.link(rig)
    scene.unit_settings.system = 'METRIC'

    source, faces, (head, teeth, tongue) = read_mouth(args.source / 'rest.mouth')
    poses = {p.stem: read_mouth(p)[0] for p in sorted(args.source.glob('*.mouth')) if p.stem != 'rest'}
    materials = [
        material(MATERIAL_NAMES[0], .49, .15, 0, 0),
        material(MATERIAL_NAMES[1], .35, .19, 0, 0),
        material(MATERIAL_NAMES[2], .35, .16, 1100, .07),
        material(MATERIAL_NAMES[3], .32, .13, 1400, .025),
        material(MATERIAL_NAMES[4], .37, .15, 1300, .20),
    ]
    pigment = bpy.data.images.load(str(ASSETS / 'pigment.jpg'))
    relief = bpy.data.images.load(str(ASSETS / 'relief.png'))
    relief.colorspace_settings.name = 'Non-Color'
    for img in (pigment, relief):
        img.pack()
    for mat in materials[:2]:
        scan_material(mat, pigment, relief)
    bpy.data.texts.new('Crédits · Credits').write((ASSETS / 'CREDITS.txt').read_text(encoding='utf-8'))
    objects = [
        piece('Față · buze · palat', source, poses, faces[:head], materials, 0, study),
        piece('Dantură · 28 coroane', source, poses, faces[head:teeth], materials, 3, study),
        piece('Limbă', source, poses, faces[teeth:tongue], materials, 4, study),
    ]
    control = pose_control(study, objects)
    scene.world.use_nodes = True
    world_shader = next(n for n in scene.world.node_tree.nodes if n.type == 'BACKGROUND')
    world_shader.inputs['Color'].default_value = (.82, .88, 1.0, 1)
    world_shader.inputs['Strength'].default_value = .23
    area(rig, 'Lumină principală', (-.13, -.21, .16), (1, .91, .83), 5.5, .24)
    area(rig, 'Umplere', (.14, -.15, .028), (.90, .95, 1), 2.5, .20)
    area(rig, 'Contur', (.14, .06, .13), (1, .85, .74), 3.5, .18)
    camera_data = bpy.data.cameras.new('Portret')
    camera = bpy.data.objects.new('Portret', camera_data)
    rig.objects.link(camera)
    camera_data.type = 'ORTHO'
    camera_data.ortho_scale = .093
    camera_data.clip_start = .03
    camera_data.clip_end = 2
    scene.camera = camera
    camera.location = (.165, -.25, .012)
    point_at(camera, (0, 0, .004))

    scene.render.engine = 'CYCLES'
    scene.cycles.samples = 32
    scene.cycles.use_denoising = True
    scene.render.resolution_x = 1100
    scene.render.resolution_y = 780
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = 'PNG'
    scene.render.film_transparent = True
    scene.view_settings.view_transform = 'AgX'
    bpy.context.view_layer.objects.active = control
    control.select_set(True)
    bpy.context.view_layer.update()
    for screen in bpy.data.screens:
        for panel in screen.areas:
            if panel.type == 'VIEW_3D':
                panel.spaces.active.region_3d.view_perspective = 'CAMERA'
                panel.spaces.active.shading.color_type = 'TEXTURE'

    args.output.mkdir(parents=True, exist_ok=True)
    bpy.ops.wm.save_as_mainfile(filepath=str((args.output / 'Fonia - Gura articulatorie.blend').resolve()))
    print([(o.name, len(o.data.vertices), len(o.data.polygons)) for o in objects])
    if args.render:
        scene.render.filepath = str((args.output / 'ce-studio.png').resolve())
        bpy.ops.render.render(write_still=True)


if __name__ == '__main__':
    main()
