"""Converts low-poly OBJ gun meshes into Minecraft box models.

The source meshes are Quaternius' "Ultimate Gun Pack" (CC0, public domain). Minecraft item models
can only use boxes, so each mesh is voxelised: the surface is rasterised into a grid and columns are
filled across the gun's width. Output is a small JSON per gun (the voxel grid) committed under
tools/art/voxguns/,
so building the resource pack never needs the original download. The voxels keep their source
material; gunvox.py repaints them, splits off moving parts and merges them into textured boxes.

    python3 tools/art/meshvox.py <OBJ folder> sheet      # contact sheet of every model
    python3 tools/art/meshvox.py <OBJ folder> convert    # write tools/art/voxguns/*.json
"""
import json
import math
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'voxguns')


def srgb(c):
    """Blender stores Kd in linear light; Minecraft textures are sRGB."""
    c = max(0.0, min(1.0, c))
    v = 12.92 * c if c <= 0.0031308 else 1.055 * c ** (1 / 2.4) - 0.055
    return int(round(min(1.0, v * 1.18 + 0.03) * 255))


def load_obj(path):
    folder = os.path.dirname(path)
    verts, tris, mats, colors = [], [], [], {}
    current = None
    with open(path, encoding='utf-8', errors='ignore') as f:
        for line in f:
            parts = line.split()
            if not parts:
                continue
            if parts[0] == 'mtllib':
                mtl = os.path.join(folder, ' '.join(parts[1:]))
                if os.path.exists(mtl):
                    name = None
                    with open(mtl, encoding='utf-8', errors='ignore') as m:
                        for ml in m:
                            mp = ml.split()
                            if mp and mp[0] == 'newmtl':
                                name = ' '.join(mp[1:])
                            elif mp and mp[0] == 'Kd' and name:
                                colors[name] = tuple(srgb(float(x)) for x in mp[1:4])
            elif parts[0] == 'v':
                verts.append(tuple(float(x) for x in parts[1:4]))
            elif parts[0] == 'usemtl':
                current = ' '.join(parts[1:])
            elif parts[0] == 'f':
                idx = [int(p.split('/')[0]) for p in parts[1:]]
                idx = [i - 1 if i > 0 else len(verts) + i for i in idx]
                for k in range(1, len(idx) - 1):
                    tris.append((idx[0], idx[k], idx[k + 1]))
                    mats.append(current)
    return verts, tris, mats, colors


def bounds(verts):
    return [(min(v[i] for v in verts), max(v[i] for v in verts)) for i in range(3)]


def side_view(path, size=(420, 160)):
    """Side render (x right, y up), nearest-first painter, for identifying models."""
    verts, tris, mats, colors = load_obj(path)
    (x0, x1), (y0, y1), _ = bounds(verts)
    w, h = size
    s = min((w - 20) / max(1e-6, x1 - x0), (h - 30) / max(1e-6, y1 - y0))
    img = Image.new('RGB', size, (235, 235, 230))
    d = ImageDraw.Draw(img)
    order = sorted(range(len(tris)), key=lambda t: sum(verts[i][2] for i in tris[t]))
    for t in order:
        pts = [(10 + (verts[i][0] - x0) * s, h - 10 - (verts[i][1] - y0) * s) for i in tris[t]]
        d.polygon(pts, fill=colors.get(mats[t], (200, 0, 200)))
    d.text((4, 2), os.path.basename(path)[:-4], fill=(0, 0, 0))
    return img


def contact_sheet(folder, out):
    names = sorted(n for n in os.listdir(folder) if n.endswith('.obj'))
    cols = 4
    tiles = [side_view(os.path.join(folder, n)) for n in names]
    sheet = Image.new('RGB', (cols * 420, ((len(tiles) + cols - 1) // cols) * 160), (255, 255, 255))
    for i, tile in enumerate(tiles):
        sheet.paste(tile, ((i % cols) * 420, (i // cols) * 160))
    sheet.save(out)
    print(out, len(names))


# ---------------------------------------------------------------- voxelisation

def tri_box_overlap(c, half, a, b, cc):
    """Akenine-Moller separating-axis test: does triangle (a, b, cc) touch the box at c with half size?"""
    v = [tuple(p[i] - c[i] for i in range(3)) for p in (a, b, cc)]
    e = [tuple(v[(k + 1) % 3][i] - v[k][i] for i in range(3)) for k in range(3)]
    for i in range(3):
        if min(p[i] for p in v) > half or max(p[i] for p in v) < -half:
            return False
    n = (e[0][1] * e[1][2] - e[0][2] * e[1][1], e[0][2] * e[1][0] - e[0][0] * e[1][2], e[0][0] * e[1][1] - e[0][1] * e[1][0])
    r = half * (abs(n[0]) + abs(n[1]) + abs(n[2]))
    dot = n[0] * v[0][0] + n[1] * v[0][1] + n[2] * v[0][2]
    if abs(dot) > r:
        return False
    for ed in e:
        for axis in ((0, -ed[2], ed[1]), (ed[2], 0, -ed[0]), (-ed[1], ed[0], 0)):
            p = [axis[0] * q[0] + axis[1] * q[1] + axis[2] * q[2] for q in v]
            rr = half * (abs(axis[0]) + abs(axis[1]) + abs(axis[2]))
            if min(p) > rr or max(p) < -rr:
                return False
    return True


def voxelise(verts, tris, mats, voxel):
    """Surface voxels (with material) on a grid of edge {@code voxel}, in mesh units."""
    (x0, _), (y0, _), (z0, _) = bounds(verts)
    grid = {}
    half = voxel / 2.0
    for t, (ia, ib, ic) in enumerate(tris):
        a, b, c = verts[ia], verts[ib], verts[ic]
        lo = [int(math.floor((min(a[i], b[i], c[i]) - (x0, y0, z0)[i]) / voxel)) for i in range(3)]
        hi = [int(math.floor((max(a[i], b[i], c[i]) - (x0, y0, z0)[i]) / voxel)) for i in range(3)]
        for gx in range(lo[0], hi[0] + 1):
            for gy in range(lo[1], hi[1] + 1):
                for gz in range(lo[2], hi[2] + 1):
                    centre = (x0 + (gx + 0.5) * voxel, y0 + (gy + 0.5) * voxel, z0 + (gz + 0.5) * voxel)
                    if tri_box_overlap(centre, half * 1.0001, a, b, c):
                        grid.setdefault((gx, gy, gz), mats[t])
    return grid


def fill_width(grid):
    """Fill each x/y column between its outermost surface voxels so the gun is solid across its width."""
    columns = {}
    for (x, y, z), m in grid.items():
        columns.setdefault((x, y), []).append((z, m))
    solid = dict(grid)
    for (x, y), entries in columns.items():
        zs = sorted(entries)
        for z in range(zs[0][0], zs[-1][0] + 1):
            if (x, y, z) not in solid:
                nearest = min(zs, key=lambda e: abs(e[0] - z))
                solid[(x, y, z)] = nearest[1]
    return solid


# ---------------------------------------------------------------- conversion table
# Ours -> (source model, target length in model units (16 = one block), flip barrel, voxel size in model units).
# Source models point their muzzle toward -X; flip=True mirrors that to +X (our convention).
MAPPING = {
    'p18': ('Pistol_1', 10.5, False, 0.3),
    'd50': ('Pistol_6', 12.5, False, 0.3),
    'sv9': ('SubmachineGun_2', 20.0, False, 0.4),
    'v57': ('SubmachineGun_5', 24.0, False, 0.4),
    'k47': ('AssaultRifle_2', 31.0, False, 0.4),
    'm4c': ('AssaultRifle2_1', 30.0, False, 0.4),
    'scr': ('Bullpup_1', 28.0, False, 0.4),
    'kl7': ('AssaultRifle_5', 33.0, False, 0.4),
    'r12': ('Shotgun_2', 32.0, False, 0.4),
    'dm14': ('SniperRifle_2', 35.0, False, 0.4),
    'awr': ('SniperRifle_3', 37.0, False, 0.4),
    # Riflescopes for the attachment system (mounted on top rails).
    'optic_4x': ('Accessories/Scope_1', 9.5, False, 0.2),
    'optic_8x': ('Accessories/Scope_3', 11.5, False, 0.2),
}


def convert(folder, gid, source, length, flip, voxel):
    """Writes the solid voxel grid (with source material names); gunvox.py paints and boxes it at build time."""
    verts, tris, mats, colors = load_obj(os.path.join(folder, source + '.obj'))
    (x0, x1), (y0, y1), (z0, z1) = bounds(verts)
    scale = length / (x1 - x0)
    cx, cy, cz = (x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2
    # Model units, centred on the block centre (8, 8, 8); barrel along +X, top +Y.
    mv = [((-(v[0] - cx) if flip else (v[0] - cx)) * scale + 8, (v[1] - cy) * scale + 8, (v[2] - cz) * scale + 8) for v in verts]
    if flip:
        tris = [(a, c, b) for a, b, c in tris]
    solid = fill_width(voxelise(mv, tris, mats, voxel))
    (bx0, _), (by0, _), (bz0, _) = bounds(mv)
    names = sorted({m for m in solid.values()})
    data = {'source': source + ' (Quaternius Ultimate Gun Pack, CC0)', 'voxel': voxel,
            'origin': [round(bx0, 4), round(by0, 4), round(bz0, 4)],
            'materials': {n: '#%02X%02X%02X' % colors.get(n, (128, 128, 128)) for n in names},
            'voxels': [[x, y, z, names.index(m)] for (x, y, z), m in sorted(solid.items())]}
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, gid + '.json'), 'w', encoding='utf-8') as f:
        json.dump(data, f, separators=(',', ':'))
    print('%-8s <- %-20s %5d voxels, materials %s' % (gid, source, len(solid), ', '.join(names)))


def main():
    folder, mode = sys.argv[1], sys.argv[2]
    if mode == 'sheet':
        contact_sheet(folder, sys.argv[3] if len(sys.argv) > 3 else '/tmp/gun_sheet.png')
    else:
        only = sys.argv[3:] or list(MAPPING)
        for gid in only:
            source, length, flip, voxel = MAPPING[gid]
            convert(folder, gid, source, length, flip, voxel)


if __name__ == '__main__':
    main()
