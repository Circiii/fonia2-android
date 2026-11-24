"""Fit the CC BY 3.0 Infinite head scan to Fonia's incisal coordinate system.

Inputs and pinned download URLs are in mouth3d/README.md. Run with CPython,
numpy, scipy and OpenCV. The Android app only reads the compact baked assets.
"""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import struct

import cv2
import numpy as np
from scipy.sparse import coo_matrix
from scipy.sparse.csgraph import connected_components, dijkstra

OUTPUT = Path(__file__).resolve().parents[2] / 'mouth3d/src/main/resources/fonia/perioral'
SOURCE_HASHES = {
    'scan.glb': '402b8a8ac9f03232e6d64b5962929703a069daf99d3c49ac8eb0e48bedc9c576',
    'albedo.jpg': '4fc7297835e2081cb914d1a8507fc837687102eed7bd3c6bed25b72960a09e08',
    'height.jpg': '937dea3ab1adea4624c9743ea0653fcb022235e6dcdebfb6e739b93f9d2a5cef',
}


def read_scan(path):
    raw = path.read_bytes()
    length = struct.unpack_from('<I', raw, 12)[0]
    doc = json.loads(raw[20:20 + length])
    binary = memoryview(raw)[28 + length:]

    def array(index):
        desc = doc['accessors'][index]
        view = doc['bufferViews'][desc['bufferView']]
        size = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3}[desc['type']]
        dtype = {5123: '<u2', 5126: '<f4'}[desc['componentType']]
        return np.frombuffer(binary, dtype, desc['count'] * size, view.get('byteOffset', 0)).reshape(-1, size)

    return array(1).astype(float), array(3).astype(float), array(0).reshape(-1, 3).astype(int)


def edges_of(faces):
    incident = defaultdict(list)
    for i, face in enumerate(faces):
        for a, b in zip(face, np.roll(face, -1)):
            incident[tuple(sorted((a, b)))].append(i)
    return incident


def adjacency(edges, weights, total):
    ends = np.array(edges).T
    half = coo_matrix((weights, ends), shape=(total, total)).tocsr()
    return half + half.T


def lip_path(anchors, uv, edges):
    """Follow actual scan edges on the moist surface, just behind the visible lip roll."""
    pairs = np.array(edges)
    mid = uv[pairs].mean(axis=1)
    duv = np.linalg.norm(uv[pairs[:, 0]] - uv[pairs[:, 1]], axis=1)
    order = sorted(anchors, key=lambda i: uv[i, 0])
    target = np.interp(mid[:, 0], uv[order, 0], uv[order, 1])
    weight = np.maximum(duv, 1e-8) * (1 + (np.abs(mid[:, 1] - target) * 950) ** 2)
    graph = adjacency(edges, weight, len(uv))
    path = [anchors[0]]
    for start, end in zip(anchors, anchors[1:]):
        _, prev = dijkstra(graph, indices=start, return_predecessors=True)
        backwards = [end]
        while backwards[-1] != start:
            backwards.append(prev[backwards[-1]])
        path.extend(reversed(backwards[:-1]))
    return path


def cut_face(xyz, uv, faces):
    edges = list(edges_of(faces))
    # Right commissure -> upper wet line -> left commissure -> lower wet line -> right.
    upper = lip_path([2580, 2592, 2609, 2470, 2467, 7152, 7316, 7267, 7260], uv, edges)
    lower = lip_path([7260, 7386, 7406, 7534, 2884, 2879, 2752, 2714, 2580], uv, edges)
    loop = upper + lower[1:-1]
    if len(set(loop)) != len(loop):
        raise ValueError(f'Lip perimeter doubles back: {loop}')
    cut = {tuple(sorted(ab)) for ab in zip(loop, np.roll(loop, -1))}
    _, weld = np.unique(xyz.round(6), axis=0, return_inverse=True)
    cut = {tuple(sorted((weld[a], weld[b]))) for a, b in cut}
    pairs = [fs for edge, fs in edges_of(weld[faces]).items() if edge not in cut and len(fs) == 2]
    _, groups = connected_components(adjacency(pairs, np.ones(len(pairs)), len(faces)), directed=False)
    assert len(set(groups)) == 2, 'The perimeter must divide the scan into the face and the old oral pouch'
    exterior = faces[groups == np.bincount(groups).argmax()]
    # Face axes from the cheilions. Preserve small asymmetries without tilting the teeth.
    right, left = xyz[upper[0]], xyz[upper[-1]]
    axis_x = (right - left) / np.linalg.norm(right - left)
    axis_z = np.cross(axis_x, [0, 1, 0])
    axis_z /= np.linalg.norm(axis_z)
    basis = np.stack((axis_x, np.cross(axis_z, axis_x), axis_z), axis=1)
    scale = 48 / np.linalg.norm(right - left)
    lip_center = (xyz[2461] + xyz[2882]) / 2
    xyz = (xyz - lip_center) @ basis * scale + [0, -0.95, 8.0]
    xyz[:, 0] -= (xyz[upper[0], 0] + xyz[upper[-1], 0]) / 2
    centre = xyz[exterior].mean(axis=1)
    # Keep a generous continuous lower-face patch, then close its hidden rear surface.
    keep = (centre[:, 0] / 84) ** 2 + ((centre[:, 1] + 10) / 62) ** 2 < 1
    keep &= centre[:, 2] > -65
    exterior = exterior[keep]
    ids, inverse = np.unique(exterior, return_inverse=True)
    remap = dict(zip(ids, range(len(ids))))
    faces = inverse.reshape(-1, 3)
    loop = np.array([remap[i] for i in loop])
    return xyz[ids], uv[ids], faces, loop, len(upper)


def boundary_rings(faces):
    edges = edges_of(faces)
    neighbours = defaultdict(set)
    for (a, b), inc in edges.items():
        if len(inc) == 1:
            neighbours[a].add(b)
            neighbours[b].add(a)
    assert all(len(adj) == 2 for adj in neighbours.values()), 'Patch has a pinched boundary'
    rings = []
    while neighbours:
        start = min(neighbours)
        ring, prev, v = [], -1, start
        while True:
            ring.append(v)
            adj = neighbours.pop(v)
            nxt = min(adj - {prev})
            if nxt == start:
                break
            prev, v = v, nxt
        rings.append(ring)
    return rings


def weights(xyz, faces, rim, upper_count):
    edges = list(edges_of(faces))
    lengths = np.linalg.norm(xyz[np.array(edges)[:, 1]] - xyz[np.array(edges)[:, 0]], axis=1)
    graph = adjacency(edges, lengths, len(xyz))
    upper = dijkstra(graph, indices=rim[1:upper_count - 1], min_only=True)
    lower = dijkstra(graph, indices=rim[upper_count:], min_only=True)
    nearest = np.minimum(upper, lower)
    x, y, z = xyz.T
    # Geodesics distinguish surfaces that touch in world space but move independently.
    upper_share = np.clip(.5 + (lower - upper) / 10, 0, 1)
    upper_share = upper_share ** 2 * (3 - 2 * upper_share)
    x_norm = np.clip(np.abs(x) / 24, 0, 1)
    middle = np.maximum(0, 1 - x_norm ** 2) ** .68
    up = upper_share * np.exp(-(upper / 13) ** 2) * middle
    down = (1 - upper_share) * np.exp(-(lower / 19) ** 2) * middle
    width = np.exp(-(nearest / 20) ** 2)
    pucker = np.exp(-(nearest / 14) ** 2) * (.44 + .56 * middle)
    jaw = np.clip((-y - 5) / 25, 0, 1)
    jaw = jaw ** 2 * (3 - 2 * jaw) * np.clip((z + 70) / 40, 0, 1) * (1 - down)
    wet = np.exp(-(nearest / 6.8) ** 2) * (.48 + .52 * middle)
    return np.stack((up, down, width, pucker, jaw, wet), axis=1)


def refine(xyz, uv, faces, rim):
    """One Loop subdivision before rigging. Smooth UVs and the lip edge with the same stencil."""
    data = np.column_stack((xyz, uv))
    smooth = data.copy()
    incident = edges_of(faces)
    adjacency = defaultdict(list)
    boundary = defaultdict(list)
    new_edges, mid = [], {}
    for (a, b), tris in incident.items():
        adjacency[a].append(b)
        adjacency[b].append(a)
        centre = .5 * (data[a] + data[b])
        if len(tris) == 1:
            boundary[a].append(b)
            boundary[b].append(a)
        else:
            opposite = [next(i for i in faces[t] if i != a and i != b) for t in tris]
            centre = .75 * centre + .125 * data[opposite].sum(axis=0)
        mid[a, b] = mid[b, a] = len(data) + len(new_edges)
        new_edges.append(centre)
    for i, adj in adjacency.items():
        if i in boundary:
            smooth[i] = .75 * data[i] + .125 * data[boundary[i]].sum(axis=0)
        else:
            mix = .5625 if len(adj) == 3 else .375
            smooth[i] = (1 - mix) * data[i] + mix * data[adj].mean(axis=0)
    result = np.vstack((smooth, new_edges))
    tris = []
    for a, b, c in faces:
        ab, bc, ca = mid[a, b], mid[b, c], mid[c, a]
        tris.extend(((a, ab, ca), (b, bc, ab), (c, ca, bc), (ab, bc, ca)))
    refined_rim = [v for a, b in zip(rim, np.roll(rim, -1)) for v in (a, mid[a, b])]
    return result[:, :3], result[:, 3:], np.array(tris), np.array(refined_rim)


def bake(source):
    xyz, uv, faces = read_scan(source / 'scan.glb')
    xyz, uv, faces, rim, upper_count = cut_face(xyz, uv, faces)
    xyz, uv, faces, rim = refine(xyz, uv, faces, rim)
    upper_count = 2 * upper_count - 1
    rings = boundary_rings(faces)
    assert len(rings) == 2 and any(set(r) == set(rim) for r in rings)
    back = np.array(next(r for r in rings if set(r) != set(rim)))
    # Keep winding consistent with the edge being stitched.
    directed = {(a, b) for face in faces for a, b in zip(face, np.roll(face, -1))}
    if (back[0], back[1]) in directed:
        back = back[::-1]
    motion = weights(xyz, faces, rim, upper_count)
    rest = np.column_stack((xyz, uv, motion))
    rear = rest[back].copy()
    rear[:, :2] *= .82
    rear[:, 2] = np.minimum(rear[:, 2] - 14, -81)
    rear[:, 5:] = 0
    ids = np.arange(len(back)) + len(rest)
    tip = len(rest) + len(rear)
    bridges = []
    for a, b, c, d in zip(back, np.roll(back, -1), ids, np.roll(ids, -1)):
        bridges.extend(((a, b, c), (b, d, c), (c, d, tip)))
    vertices = np.vstack((rest, rear, [0, 0, -101, .5, .47, 0, 0, 0, 0, 0, 0]))
    # The 2048x1536 crop preserves the original 4096-wide source's texel density.
    vertices[:, 3] = np.clip((vertices[:, 3] * 4096 - 1024) / 2048, 0, 1)
    vertices[:, 4] = np.clip(((1 - vertices[:, 4]) * 4096 - 1104) / 1536, 0, 1)
    faces = np.vstack((faces, bridges))
    with (OUTPUT / 'surface.bin').open('wb') as out:
        out.write(struct.pack('<4s4I', b'FON1', len(vertices), len(faces), len(rim), upper_count))
        out.write(vertices.astype('<f4').tobytes())
        out.write(faces.astype('<u2').tobytes())
        out.write(rim.astype('<u2').tobytes())
    return len(vertices), len(faces), len(rim)


def textures(source):
    crop = np.s_[1104:2640, 1024:3072]
    rgb = cv2.imread(str(source / 'albedo.jpg'))[crop]
    cv2.imwrite(str(OUTPUT / 'pigment.jpg'), rgb, [cv2.IMWRITE_JPEG_QUALITY, 96])
    height = cv2.imread(str(source / 'height.jpg'), cv2.IMREAD_GRAYSCALE)[crop].astype('f') / 255
    relief = height - cv2.GaussianBlur(height, (0, 0), 4.0)
    dx = cv2.Sobel(relief, -1, 1, 0, ksize=3) * .75
    dy = cv2.Sobel(relief, -1, 0, 1, ksize=3) * .75
    normals = np.dstack((-dx, -dy, np.ones_like(dx)))
    normals /= np.linalg.norm(normals, axis=2, keepdims=True)
    normal_rgb = np.round((normals * .5 + .5) * 255).astype('uint8')
    cv2.imwrite(str(OUTPUT / 'relief.png'), normal_rgb[:, :, ::-1], [cv2.IMWRITE_PNG_COMPRESSION, 9])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    source = parser.parse_args().source
    for name, digest in SOURCE_HASHES.items():
        if hashlib.sha256((source / name).read_bytes()).hexdigest() != digest:
            raise ValueError(f'Unexpected source revision: {name}')
    OUTPUT.mkdir(parents=True, exist_ok=True)
    print('Scan vertices, triangles, aperture vertices:', bake(source))
    textures(source)
