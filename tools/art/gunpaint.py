"""Paints the primitive guns (meshprim.py): one texture per gun, a patch per visible box face.

A face's patch has as many texels as its size times the face's density: the left side (north, the
one the camera sees from the hip) and the top get the most, the right side and the underside less.
Each texel is the finish's colour (steel, polymer, laminated wood, bakelite...) with its grain, times
ambient occlusion baked from the gun's own boxes (RAYS rays over the hemisphere, up to AO_RANGE
units: creases, the magazine well and the gap under the handguard darken), and the true outline
edges catch one texel of light. Faces are not darkened by which way they face: the client already
lights each face by its direction.

The finishes follow real guns: blued or parkerised steel #1E2022..#2B2E31, black polymer #222325, FDE
#8B7A5E, an AK's laminated wood #4A2618..#5E3220 and its bakelite magazine #5A2414.
"""
import math

import numpy as np
from PIL import Image

# face -> (outward normal, (u axis, sign), (v axis, sign)): the client's default UV layout (gunvox.FACES).
FACES = {
    'up': ((0, 1, 0), (0, 1), (2, 1)),
    'down': ((0, -1, 0), (0, 1), (2, -1)),
    'north': ((0, 0, -1), (0, -1), (1, -1)),
    'south': ((0, 0, 1), (0, 1), (1, -1)),
    'west': ((-1, 0, 0), (2, 1), (1, -1)),
    'east': ((1, 0, 0), (2, -1), (1, -1)),
}
# Texels per model unit by face: the left side and the top are what the first-person camera sees.
DENSITY = {'north': 14, 'up': 14, 'west': 10, 'south': 8, 'east': 8, 'down': 6}
RAYS = 24
AO_RANGE = 1.6
AO_STRENGTH = 0.62
PAD = 2

# name -> (darkest, lightest, finish): a low-frequency blend between the two, then the finish's grain.
MATERIALS = {
    'steel': ('#1E2022', '#2B2E31', 'metal'),
    'alu': ('#222427', '#2E3134', 'anodised'),
    'bare': ('#4A4E53', '#5C6166', 'metal'),
    'polymer': ('#1F2022', '#27282B', 'polymer'),
    'rubber': ('#18181A', '#1E1E20', 'polymer'),
    'fde': ('#82725A', '#94836A', 'polymer'),
    'od': ('#474E35', '#555D40', 'polymer'),
    'ak_wood': ('#4A2618', '#5E3220', 'laminate'),
    'walnut': ('#3E2416', '#55331F', 'wood'),
    'bakelite': ('#541F11', '#622A18', 'gloss'),
    'stainless': ('#8C9196', '#A3A8AD', 'metal'),
    'brass': ('#9C7A38', '#B8944A', 'metal'),
    'glass': ('#1B2A38', '#2A4258', 'glass'),
    'red': ('#B02A22', '#C8382C', 'gloss'),
    'dot': ('#C9C7BE', '#E4E2D8', 'gloss'),     # a sight's white dot
}
EDGE = {'metal': 1.55, 'anodised': 1.45, 'polymer': 1.3, 'laminate': 1.22, 'wood': 1.22, 'gloss': 1.35, 'glass': 1.5}


def _hex(c):
    return np.array([int(c[i:i + 2], 16) for i in (1, 3, 5)], dtype=float)


def _noise(p, scale, seed):
    """Smooth value noise at 3D points (N x 3), about one bump per `scale` units, in -1..1."""
    q = p / scale + seed * 17.13
    i = np.floor(q)
    f = q - i
    f = f * f * (3 - 2 * f)

    def h(dx, dy, dz):
        n = (i[:, 0] + dx) * 73856093 + (i[:, 1] + dy) * 19349663 + (i[:, 2] + dz) * 83492791 + seed * 2654435761
        n = np.mod(n, 2147483647)
        return np.mod(np.sin(n * 0.000123 + seed) * 43758.5453, 1.0) * 2 - 1
    out = 0
    for dx in (0, 1):
        for dy in (0, 1):
            for dz in (0, 1):
                w = (f[:, 0] if dx else 1 - f[:, 0]) * (f[:, 1] if dy else 1 - f[:, 1]) * (f[:, 2] if dz else 1 - f[:, 2])
                out = out + w * h(dx, dy, dz)
    return out


def _grain(finish, local, world):
    """Brightness factor per texel from the finish: local is the point in the box's own frame (x along it)."""
    n = len(local)
    if finish == 'metal':
        # Faint streaks along the part, as on blued or parkerised steel.
        return 1 + 0.05 * _noise(local * [0.08, 3.0, 3.0], 1.0, 3) + 0.03 * _noise(world, 0.25, 7)
    if finish == 'anodised':
        return 1 + 0.03 * _noise(world, 0.4, 5)
    if finish == 'polymer':
        rng = np.random.default_rng(int(abs(world[:, :2].sum()) * 1000) % 2 ** 31 if n else 0)
        return 1 + 0.05 * _noise(world, 0.12, 11) + 0.035 * rng.standard_normal(n).clip(-2, 2) / 2
    if finish == 'laminate':
        # Laminated birch: bands of slightly different stain along the part, wavy with the grain.
        y = local[:, 1] + 0.18 * np.sin(local[:, 0] * 0.9) + 0.05 * _noise(local, 0.6, 2)
        bands = np.sin(y * 9.0) * 0.5 + 0.5
        return 0.9 + 0.16 * bands + 0.05 * _noise(local * [0.15, 2.5, 2.5], 1.0, 9)
    if finish == 'wood':
        y = local[:, 1] + 0.25 * _noise(local * [0.3, 1, 1], 1.0, 4)
        return 0.92 + 0.12 * (np.sin(y * 14.0) * 0.5 + 0.5) + 0.04 * _noise(local, 0.3, 6)
    if finish == 'gloss':
        return 1 + 0.06 * _noise(world, 0.7, 13)
    if finish == 'glass':
        return 1 + 0.25 * (np.abs(np.sin(local[:, 0] * 2.0 + local[:, 1] * 3.0)) > 0.92)
    return np.ones(n)


def _rays(k=RAYS):
    """Fixed, cosine-weighted directions over the hemisphere around +Z (Fibonacci spiral)."""
    out = []
    golden = math.pi * (3 - math.sqrt(5))
    for i in range(k):
        r = math.sqrt((i + 0.5) / k)
        a = i * golden
        out.append((r * math.cos(a), r * math.sin(a), math.sqrt(max(0.0, 1 - r * r))))
    return np.array(out)


RAY_DIRS = _rays()


def _basis(n):
    n = np.asarray(n, float)
    t = np.cross(n, [0, 1, 0] if abs(n[1]) < 0.9 else [1, 0, 0])
    t /= np.linalg.norm(t)
    return t, np.cross(n, t), n


def occlusion(points, normal, prims, centre, radius):
    """Share of hemisphere rays from each point (N x 3) that hit another box within AO_RANGE, weighted
    toward the near hits."""
    t, b, n = _basis(normal)
    dirs = RAY_DIRS @ np.array([t, b, n])            # K x 3, world
    origins = points + normal * 0.01
    near = [p for p in prims if np.linalg.norm(p.centre - centre) <= radius + np.linalg.norm(p.half) + AO_RANGE]
    occ = np.zeros(len(points))
    if not near:
        return occ
    for start in range(0, len(points), 1500):
        o = origins[start:start + 1500]
        best = np.full((len(o), len(dirs)), np.inf)
        for p in near:
            m = p.matrix()
            lo = (o - p.centre) @ m                  # N x 3
            ld = dirs @ m                            # K x 3
            ld = np.where(np.abs(ld) < 1e-9, 1e-9, ld)
            t1 = (-p.half[None, None, :] - lo[:, None, :]) / ld[None, :, :]
            t2 = (p.half[None, None, :] - lo[:, None, :]) / ld[None, :, :]
            tmin = np.minimum(t1, t2).max(2)
            tmax = np.maximum(t1, t2).min(2)
            hit = (tmax >= np.maximum(tmin, 0)) & (tmin <= AO_RANGE)
            dist = np.where(hit, np.maximum(tmin, 0), np.inf)
            best = np.minimum(best, dist)
        w = np.where(np.isfinite(best), 1 - (best / AO_RANGE) ** 0.7, 0)
        occ[start:start + 1500] = w.mean(1)
    return occ


def face_frame(p, face):
    """(fixed axis, fixed value in the box's frame, u axis, u sign, v axis, v sign, width, height) of a face."""
    normal, (ua, us), (va, vs) = FACES[face]
    axis = next(i for i in range(3) if normal[i])
    return axis, normal[axis] * p.half[axis], ua, us, va, vs, 2 * p.half[ua], 2 * p.half[va]


def face_points(p, face, cols, rows, inset=0.5):
    """Box-frame points of a face's texel grid (rows x cols, in UV order) and the same in model space."""
    axis, fixed, ua, us, va, vs, w, h = face_frame(p, face)
    i = (np.arange(cols) + inset) / cols
    j = (np.arange(rows) + inset) / rows
    ii, jj = np.meshgrid(i, j)
    local = np.zeros((rows, cols, 3))
    local[..., axis] = fixed
    local[..., ua] = (ii - 0.5) * w * us
    local[..., va] = (jj - 0.5) * h * vs
    world = local.reshape(-1, 3) @ p.matrix().T + p.centre
    return local.reshape(-1, 3), world


def visible_faces(prims):
    """Per box, the faces not wholly covered by other boxes (nine points over the face, nudged out along
    its normal, all inside another box)."""
    import meshprim
    out = []
    for k, p in enumerate(prims):
        faces = []
        for face, (normal, _, _) in FACES.items():
            local, _ = face_points(p, face, 3, 3)
            world = (local + np.array(normal) * 0.02) @ p.matrix().T + p.centre
            if not meshprim.inside_any(world, prims, skip=k).all():
                faces.append(face)
        out.append(faces)
    return out


def _edges(p, face, cols, rows, prims, k):
    """1 where a texel is on the face's border and the surface turns away there (an outline edge)."""
    axis, fixed, ua, us, va, vs, w, h = face_frame(p, face)
    mask = np.zeros((rows, cols))
    normal = np.array(FACES[face][0], float)
    for side in range(4):
        if side < 2:
            idx = [(j, 0 if side == 0 else cols - 1) for j in range(rows)]
        else:
            idx = [(0 if side == 2 else rows - 1, i) for i in range(cols)]
        pts = []
        for j, i in idx:
            local = np.zeros(3)
            local[axis] = fixed - normal[axis] * 0.03
            u = ((i + 0.5) / cols - 0.5) * w * us
            v = ((j + 0.5) / rows - 0.5) * h * vs
            # One texel past the border, in the face's plane.
            if side == 0:
                u = -0.5 * w * us - us * 0.6 / max(1, cols) * w
            elif side == 1:
                u = 0.5 * w * us + us * 0.6 / max(1, cols) * w
            elif side == 2:
                v = -0.5 * h * vs - vs * 0.6 / max(1, rows) * h
            else:
                v = 0.5 * h * vs + vs * 0.6 / max(1, rows) * h
            local[ua], local[va] = u, v
            pts.append(local @ p.matrix().T + p.centre)
        open_ = ~_inside(np.array(pts), prims, k)
        for (j, i), o in zip(idx, open_):
            if o:
                mask[j, i] = 1
    return mask


def _inside(points, prims, skip):
    import meshprim
    return meshprim.inside_any(points, prims, skip=skip)


def paint_face(p, face, cols, rows, prims, k, samples=None):
    """RGBA texels (rows x cols x 4) of one face. `samples`, a list, gets (material, occlusion, rgb) of
    every texel for stats()."""
    dark, light, finish = MATERIALS[p.mat]
    local, world = face_points(p, face, cols, rows)
    normal = np.array(FACES[face][0], float) @ p.matrix().T
    blend = (_noise(world, 2.5, 1) * 0.5 + 0.5)[:, None]
    base = _hex(dark) * (1 - blend) + _hex(light) * blend
    shade = _grain(finish, local, world)
    occ = occlusion(world, normal, prims, world.mean(0), float(np.ptp(world, 0).max()) / 2 + 0.5)
    shade = shade * (1 - AO_STRENGTH * occ)
    edge = _edges(p, face, cols, rows, prims, k).reshape(-1)
    shade = shade * np.where(edge > 0, EDGE[finish], 1.0)
    rgb = np.clip(base * shade[:, None], 0, 255)
    if samples is not None:
        samples.append((p.mat, occ, rgb))
    out = np.concatenate([rgb, np.full((len(rgb), 1), 255.0)], 1)
    return out.reshape(rows, cols, 4).astype(np.uint8)


def stats(samples):
    """Per material: texels, mean luminance and saturation (0-1), and the mean luminance of creases
    (occlusion over 0.3) against open faces (under 0.05)."""
    out = {}
    for mat in sorted({m for m, _, _ in samples}):
        occ = np.concatenate([o for m, o, _ in samples if m == mat])
        rgb = np.concatenate([c for m, _, c in samples if m == mat]) / 255.0
        lum = rgb @ np.array([0.2126, 0.7152, 0.0722])
        sat = (rgb.max(1) - rgb.min(1)) / np.maximum(rgb.max(1), 1e-6)
        crease, open_ = lum[occ > 0.3], lum[occ < 0.05]
        out[mat] = {'texels': int(len(lum)), 'lum': float(lum.mean()), 'sat': float(sat.mean()),
                    'crease': float(crease.mean()) if len(crease) else None,
                    'open': float(open_.mean()) if len(open_) else None}
    return out


def target_lum(mat):
    """The luminance range a material's texels should average in: its darkest and lightest base colour,
    allowing for occlusion below and edge light above."""
    dark, light, _ = MATERIALS[mat]
    w = np.array([0.2126, 0.7152, 0.0722])
    return float(_hex(dark) @ w / 255 * 0.7), float(_hex(light) @ w / 255 * 1.15)


class Atlas:
    """Shelf-packed patches with a PAD-texel border copied from their edges (no bleeding at mip levels)."""

    def __init__(self):
        self.patches = []

    def add(self, pixels):
        self.patches.append([pixels, None])
        return self.patches[-1]

    def pack(self):
        size = 64
        while True:
            x = y = shelf = 0
            ok = True
            for patch in sorted(self.patches, key=lambda p: -p[0].shape[0]):
                h, w = patch[0].shape[:2]
                w2, h2 = w + 2 * PAD, h + 2 * PAD
                if x + w2 > size:
                    x, y, shelf = 0, y + shelf, 0
                if x + w2 > size or y + h2 > size:
                    ok = False
                    break
                patch[1] = (x + PAD, y + PAD)
                x += w2
                shelf = max(shelf, h2)
            if ok:
                break
            size *= 2
        img = np.zeros((size, size, 4), np.uint8)
        for pixels, (ax, ay) in self.patches:
            padded = np.pad(pixels, ((PAD, PAD), (PAD, PAD), (0, 0)), mode='edge')
            h, w = padded.shape[:2]
            img[ay - PAD:ay - PAD + h, ax - PAD:ax - PAD + w] = padded
        self.size = size
        return Image.fromarray(img, 'RGBA')

    def uv(self, patch):
        pixels, (ax, ay) = patch
        h, w = pixels.shape[:2]
        s = 16.0 / self.size
        return [round(ax * s, 5), round(ay * s, 5), round((ax + w) * s, 5), round((ay + h) * s, 5)]


def paint(prims, levels):
    """Texture for a gun and its element lists per level of detail.

    prims: the fine boxes (AO is baked against them). levels: {name: boxes}, each box of a coarser
    level a copy of a fine one (Prim.piece/tag/centre say which), sharing that box's patches."""
    fine_faces = visible_faces(prims)
    need = {}
    for k, faces in enumerate(fine_faces):
        for face in faces:
            need[(k, face)] = True
    index = {id(p): k for k, p in enumerate(prims)}
    level_faces = {}
    for name, boxes in levels.items():
        vis = visible_faces(boxes)
        level_faces[name] = vis
        for box, faces in zip(boxes, vis):
            k = _source(box, prims, index)
            for face in faces:
                need[(k, face)] = True
    atlas = Atlas()
    patches = {}
    samples = []
    for (k, face) in sorted(need):
        p = prims[k]
        _, _, ua, _, va, _, w, h = face_frame(p, face)
        cols = max(1, int(round(w * DENSITY[face])))
        rows = max(1, int(round(h * DENSITY[face])))
        patches[(k, face)] = atlas.add(paint_face(p, face, cols, rows, prims, k, samples))
    texture = atlas.pack()
    texture.info['stats'] = stats(samples)
    out = {}
    for name, boxes in levels.items():
        elements = []
        for box, faces in zip(boxes, level_faces[name]):
            if not faces:
                continue
            k = _source(box, prims, index)
            e = box.element()
            e['faces'] = {face: {'uv': atlas.uv(patches[(k, face)]), 'texture': '#p'} for face in faces}
            elements.append((box, e))
        out[name] = elements
    return texture, out


def _source(box, prims, index):
    """The fine box a level's box was made from."""
    if id(box) in index:
        return index[id(box)]
    best = min(range(len(prims)), key=lambda k: (prims[k].piece != box.piece or prims[k].tag != box.tag,
                                                    float(np.linalg.norm(prims[k].centre - box.centre)),
                                                    abs(prims[k].rot[0] - box.rot[0])))
    return best


def side_image(elements, texture, px=8):
    """Side view (x right, y up) of textured elements seen from +Z, for inventory icons."""
    import fpview
    tex = np.array(texture.convert('RGBA'))
    size = tex.shape[0]
    pts_all = []
    quads = []
    for e in elements:
        for name, corners, face in fpview.faces_of(e):
            c = np.array(corners) * 16.0
            quads.append((c, face, fpview.lit_face(e, name)))
            pts_all.append(c)
    allp = np.vstack(pts_all)
    x0, y0 = allp[:, 0].min(), allp[:, 1].min()
    x1, y1 = allp[:, 0].max(), allp[:, 1].max()
    w, h = int(math.ceil((x1 - x0) * px)) + 2, int(math.ceil((y1 - y0) * px)) + 2
    img = np.zeros((h, w, 4), np.uint8)
    depth = np.full((h, w), -1e9)
    shade = {'up': 1.0, 'down': 0.5, 'north': 0.8, 'south': 0.8, 'east': 0.6, 'west': 0.6}
    for c, face, lit in quads:
        # Faces seen from +Z: the quad's corners in screen space, texture sampled by inverse bilinear.
        u0, v0, u1, v1 = face['uv']
        uvs = np.array([(u0, v0), (u1, v0), (u1, v1), (u0, v1)]) / 16.0 * size
        steps = 3 * int(max(np.ptp(uvs[:, 0]), np.ptp(uvs[:, 1]), np.ptp(c[:, 0]) * px, np.ptp(c[:, 1]) * px) + 1)
        a = np.linspace(0, 1, steps)
        aa, bb = np.meshgrid(a, a)
        aa, bb = aa.ravel(), bb.ravel()
        p = (c[0] * ((1 - aa) * (1 - bb))[:, None] + c[1] * (aa * (1 - bb))[:, None]
             + c[2] * (aa * bb)[:, None] + c[3] * ((1 - aa) * bb)[:, None])
        t = (uvs[0] * ((1 - aa) * (1 - bb))[:, None] + uvs[1] * (aa * (1 - bb))[:, None]
             + uvs[2] * (aa * bb)[:, None] + uvs[3] * ((1 - aa) * bb)[:, None])
        sx = ((p[:, 0] - x0) * px).astype(int).clip(0, w - 1)
        sy = ((y1 - p[:, 1]) * px).astype(int).clip(0, h - 1)
        tx = t[:, 0].astype(int).clip(0, size - 1)
        ty = t[:, 1].astype(int).clip(0, size - 1)
        z = p[:, 2]
        col = tex[ty, tx].astype(float)
        col[:, :3] *= shade[lit]
        order = np.argsort(z)
        for idx in np.array_split(order, max(1, len(order) // 4096 + 1)):
            closer = z[idx] > depth[sy[idx], sx[idx]]
            sel = idx[closer]
            depth[sy[sel], sx[sel]] = z[sel]
            img[sy[sel], sx[sel]] = col[sel].astype(np.uint8)
    return Image.fromarray(img, 'RGBA')
