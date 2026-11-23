"""Builds gun models from the Quaternius meshes as rotated primitives instead of voxels.

The voxel guns (meshvox.py, gunvox.py) fill every column of the mesh across its width, so every
part ends up a flat slab of one thickness with stepped edges. Here each connected piece of the mesh
(a magazine, a grip, a barrel) is fitted with a few oriented boxes: its side silhouette is covered
by minimum-area rectangles, split along their long axis until each one is at least FILL full, and
each rectangle is extruded to the piece's own width there. Rectangles may lie at any angle about the
barrel's side axis (the 1.21.11 client takes any angle and x/y/z Euler rotations; see fpview.py), so
a curved magazine is a few tilted boxes, not a staircase.

A gun's tools/art/guns/<id>.yml says what the mesh cannot: the real width of each part (an AK's
receiver 1.2 units, its handguard 1.8, its barrel 0.57 and round), which parts move in the reload
(mag, pump), the finish of each part, and hand-placed details the low-poly mesh lacks (front and
rear sights, the charging handle, the selector, the ejection port, the muzzle device). See
guns/k47.yml for every key.

Three levels of detail come out of one gun, each within the budget gunbudget.py checks:
  fine  first person at rest, aiming and kicking (<= 250 elements, <= 1000 faces)
  mid   the reload and draw frames, which bake a copy per frame (details smaller than MID_DETAIL
        dropped, <= 450 faces)
  low   third person, the ground, item frames and the AI soldiers (the largest pieces, <= 80
        elements and <= 350 faces, 1.7 times as wide so the gun still reads in another's hands)
All three share one texture, painted by gunpaint.py.

    python3 tools/art/meshprim.py k47 sheet out.png     # the mesh's pieces and the fitted boxes, side on
    python3 tools/art/meshprim.py k47 stats             # elements and faces per level of detail
"""
import math
import os
import sys

import numpy as np
import yaml
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, 'src', 'quaternius')
SPECS = os.path.join(HERE, 'guns')

FILL = 0.86           # a fitted rectangle must be at least this much mesh, or it is split
CELL = 0.04           # silhouette raster, model units
MAX_DEPTH = 5         # splits of one piece (up to 32 boxes)
MIN_AREA = 0.04       # rectangles smaller than this (unit^2) are dropped unless the piece is that small
SNAP = 2.5            # degrees: rectangles this close to level are made level
MID_DETAIL = 0.12     # unit^3: details smaller than this are left out of the mid model
LOW_ELEMENTS = 80
LOW_WIDEN = 1.7
BUDGET = {'fine': (250, 1000), 'mid': (250, 450), 'low': (LOW_ELEMENTS, 350)}


def has_spec(gid):
    return os.path.exists(os.path.join(SPECS, gid + '.yml'))


_SPEC = {}


def spec(gid):
    if gid not in _SPEC:
        with open(os.path.join(SPECS, gid + '.yml'), encoding='utf-8') as f:
            _SPEC[gid] = yaml.safe_load(f)
    return _SPEC[gid]


# ---------------------------------------------------------------- the source mesh

def load_mesh(gid):
    """Vertices in model units (barrel along +X, top +Y, centred on 8, 8, 8), triangles and their materials."""
    import meshvox
    s = spec(gid)
    verts, tris, mats, _ = meshvox.load_obj(os.path.join(SRC, s['source'] + '.obj'))
    v = np.array(verts, dtype=float)
    lo, hi = v.min(0), v.max(0)
    scale = s['length'] / (hi[0] - lo[0])
    centre = (lo + hi) / 2
    v = (v - centre) * scale + 8.0
    if s.get('flip'):
        v[:, 0] = 16.0 - v[:, 0]
        tris = [(a, c, b) for a, b, c in tris]
    return v, np.array(tris, dtype=int), list(mats)


def pieces(verts, tris):
    """Connected pieces of the mesh (vertices at the same place count as joined): lists of triangle indices,
    largest first."""
    parent = list(range(len(verts)))

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a
    seen = {}
    for i, p in enumerate(np.round(verts, 4)):
        key = tuple(p)
        if key in seen:
            parent[find(i)] = find(seen[key])
        else:
            seen[key] = i
    for a, b, c in tris:
        parent[find(a)] = find(b)
        parent[find(b)] = find(c)
    groups = {}
    for t, (a, _, _) in enumerate(tris):
        groups.setdefault(find(a), []).append(t)
    out = sorted(groups.values(), key=lambda g: (-len(g), g[0]))
    return [np.array(g) for g in out]


def silhouette(verts, tris, group):
    """Cell centres (N x 2, model units) of the piece's side silhouette (x right, y up)."""
    pts = verts[tris[group]][:, :, :2]
    lo = pts.reshape(-1, 2).min(0) - CELL
    hi = pts.reshape(-1, 2).max(0) + CELL
    w, h = (int(math.ceil((hi - lo)[i] / CELL)) + 1 for i in range(2))
    img = Image.new('1', (w, h), 0)
    d = ImageDraw.Draw(img)
    for tri in pts:
        q = [((x - lo[0]) / CELL, (y - lo[1]) / CELL) for x, y in tri]
        d.polygon(q, fill=1)
    mask = np.array(img, dtype=bool)
    ys, xs = np.nonzero(mask)
    return np.stack([lo[0] + (xs + 0.5) * CELL, lo[1] + (ys + 0.5) * CELL], 1)


# ---------------------------------------------------------------- fitting

def min_rect(points):
    """Smallest-area rectangle around 2D points: (cx, cy, angle in degrees, half length, half height).

    The best rectangle has a side along an edge of the convex hull; the angle is that of its long
    side, folded into (-90, 90], and snapped to level within SNAP degrees."""
    from scipy.spatial import ConvexHull, QhullError
    try:
        hull = points[ConvexHull(points).vertices]
    except (QhullError, ValueError):
        hull = points
    best = None
    edges = np.diff(np.vstack([hull, hull[:1]]), axis=0)
    angles = {0.0} | {round(math.degrees(math.atan2(e[1], e[0])) % 180.0, 3) for e in edges if np.hypot(*e) > 1e-9}
    for a in angles:
        r = math.radians(a)
        c, s = math.cos(r), math.sin(r)
        u = hull @ np.array([c, s])
        v = hull @ np.array([-s, c])
        area = (u.max() - u.min()) * (v.max() - v.min())
        if best is None or area < best[0] - 1e-9:
            best = (area, a, u.min(), u.max(), v.min(), v.max())
    _, a, u0, u1, v0, v1 = best
    r = math.radians(a)
    uc, vc = (u0 + u1) / 2, (v0 + v1) / 2
    cx, cy = uc * math.cos(r) - vc * math.sin(r), uc * math.sin(r) + vc * math.cos(r)
    hu, hv = (u1 - u0) / 2 + CELL / 2, (v1 - v0) / 2 + CELL / 2
    if hu < hv:  # long side first
        a += 90.0
        hu, hv = hv, hu
    a = (a + 90.0) % 180.0 - 90.0
    if abs(a) < SNAP or abs(abs(a) - 90) < SNAP:
        # Level (or upright): refit along the axes so the box hugs the points.
        upright = abs(a) > 45
        xs, ys = points[:, 0], points[:, 1]
        cx, cy = (xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2
        hx, hy = (xs.max() - xs.min()) / 2 + CELL / 2, (ys.max() - ys.min()) / 2 + CELL / 2
        return (cx, cy, 90.0, hy, hx) if upright and hy > hx else (cx, cy, 0.0, hx, hy)
    return (cx, cy, round(a, 2), hu, hv)


def _area(rect):
    return 4 * rect[3] * rect[4]


def decompose(points, depth=0, fill=FILL, max_depth=MAX_DEPTH):
    """Covers the points with rectangles, splitting along the long side until each is `fill` full."""
    rect = min_rect(points)
    full = len(points) * CELL * CELL / max(1e-9, _area(rect))
    if full >= fill or depth >= max_depth or len(points) < 12:
        return [rect]
    cx, cy, a, hu, hv = rect
    r = math.radians(a)
    best = None
    # Cut across the long side first; across the short side too, for a piece with a slanted edge.
    for axis, half in ((np.array([math.cos(r), math.sin(r)]), hu), (np.array([-math.sin(r), math.cos(r)]), hv)):
        u = (points - [cx, cy]) @ axis
        for f in np.linspace(0.15, 0.85, 15):
            cut = -half + 2 * half * f
            left, right = points[u < cut], points[u >= cut]
            if len(left) < 6 or len(right) < 6:
                continue
            total = _area(min_rect(left)) + _area(min_rect(right))
            if best is None or total < best[0]:
                best = (total, left, right)
    if best is None or best[0] > _area(rect) * 0.97:
        return [rect]
    return decompose(best[1], depth + 1, fill, max_depth) + decompose(best[2], depth + 1, fill, max_depth)


def _inside_rect(xy, rect, margin=0.0):
    cx, cy, a, hu, hv = rect
    r = math.radians(a)
    d = xy - [cx, cy]
    u = d @ np.array([math.cos(r), math.sin(r)])
    v = d @ np.array([-math.sin(r), math.cos(r)])
    return (np.abs(u) <= hu + margin) & (np.abs(v) <= hv + margin)


def z_extent(verts, tris, group, rect):
    """The piece's width where the rectangle is: the z range of the triangles that reach into it."""
    tv = verts[tris[group]]
    centres = tv.mean(1)[:, :2]
    hit = _inside_rect(centres, rect, 0.1)
    if not hit.any():
        hit = np.ones(len(tv), dtype=bool)
    z = tv[hit][:, :, 2]
    return float(z.min()), float(z.max())


def majority(mats, group):
    counts = {}
    for t in group:
        counts[mats[t]] = counts.get(mats[t], 0) + 1
    return max(counts, key=counts.get)


# ---------------------------------------------------------------- primitives

class Prim:
    """One box: centre, half sizes along its own axes, rotation (x, y, z degrees, applied X then Y then Z
    as the client does), finish, moving part and where it came from."""

    def __init__(self, centre, half, rot=(0.0, 0.0, 0.0), mat='steel', part='body', tag='', detail=False, piece=-1):
        self.centre = np.array(centre, dtype=float)
        self.half = np.array(half, dtype=float)
        self.rot = tuple(float(r) for r in rot)
        self.mat = mat
        self.part = part
        self.tag = tag
        self.detail = detail
        self.piece = piece

    def matrix(self):
        x, y, z = (math.radians(r) for r in self.rot)
        rx = np.array([[1, 0, 0], [0, math.cos(x), -math.sin(x)], [0, math.sin(x), math.cos(x)]])
        ry = np.array([[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]])
        rz = np.array([[math.cos(z), -math.sin(z), 0], [math.sin(z), math.cos(z), 0], [0, 0, 1]])
        return rz @ ry @ rx

    def volume(self):
        return float(np.prod(self.half * 2))

    def corners(self):
        signs = np.array([[sx, sy, sz] for sx in (-1, 1) for sy in (-1, 1) for sz in (-1, 1)])
        return (signs * self.half) @ self.matrix().T + self.centre

    def copy(self, **changes):
        p = Prim(self.centre.copy(), self.half.copy(), self.rot, self.mat, self.part, self.tag, self.detail, self.piece)
        for k, v in changes.items():
            setattr(p, k, v)
        return p

    def element(self):
        """The model element without faces: from/to around the centre, rotated about the centre."""
        lo, hi = self.centre - self.half, self.centre + self.half
        e = {'from': [round(float(v), 4) for v in lo], 'to': [round(float(v), 4) for v in hi]}
        turned = [(axis, angle) for axis, angle in zip('xyz', self.rot) if abs(angle) > 1e-6]
        origin = [round(float(v), 4) for v in self.centre]
        if len(turned) == 1:
            e['rotation'] = {'origin': origin, 'axis': turned[0][0], 'angle': round(turned[0][1], 3)}
        elif turned:
            e['rotation'] = {'origin': origin, 'x': round(self.rot[0], 3), 'y': round(self.rot[1], 3), 'z': round(self.rot[2], 3)}
        return e


TAN_22 = math.tan(math.radians(22.5))


def _round(p, sides):
    """A round part (barrel, gas tube, muzzle device) as an eight-sided prism: four strips about its long
    axis, at 0, 90, 45 and -45 degrees, each as long as the part and 2 tan(22.5) of its width across. Their
    union is exactly the octagon (every point of it lies in the strip facing its nearest side), and the
    strips' inner faces are all covered, so only the eight sides and the ends are drawn. A part taller
    than wide gets an oval octagon. The ends are staggered by a hair so no two caps share a plane. The
    spin comes first, then the part's own tilt about z."""
    if sides < 8:
        return [p]
    length, ry, rz = p.half
    rd = math.sqrt((ry * ry + rz * rz) / 2)
    strips = [((length, ry, rz * TAN_22), 0.0), ((length - 0.004, ry * TAN_22, rz), 0.0),
              ((length - 0.008, rd, rd * TAN_22), 45.0), ((length - 0.012, rd, rd * TAN_22), -45.0)]
    out = []
    for half, spin in strips:
        q = p.copy(rot=(spin, p.rot[1], p.rot[2]))
        q.half = np.array(half)
        out.append(q)
    return out


def _pick(rule, shapes):
    """The mesh pieces a spec rule names: `piece` (an index or a list, as `meshprim.py <id> sheet` numbers
    them), or `at` (a side-view point on the piece; the smallest piece there wins)."""
    if 'piece' in rule:
        want = rule['piece'] if isinstance(rule['piece'], list) else [rule['piece']]
        return [i for i in range(len(shapes)) if i in want]
    if 'at' in rule:
        x, y = rule['at']
        hits = [i for i, pts in enumerate(shapes) if (np.abs(pts - [x, y]).max(1) <= CELL).any()]
        return [min(hits, key=lambda i: len(shapes[i]))] if hits else []
    return []


def _zone_mat(zones, x, y, mat, source):
    for zone in zones or []:
        only = zone.get('only')
        if only and source not in only:
            continue
        from gunvox import _inside
        if _inside(zone['poly'], x, y):
            mat = zone['mat']
    return mat


_PRIMS = {}


def fit(gid):
    """The gun's primitives: the fitted mesh pieces with the spec's rules applied, plus its hand-placed parts."""
    if gid in _PRIMS:
        return _PRIMS[gid]
    s = spec(gid)
    verts, tris, mats = load_mesh(gid)
    groups = pieces(verts, tris)
    shapes = [silhouette(verts, tris, g) for g in groups]
    rules = {}
    for rule in s.get('pieces', []):
        picked = _pick(rule, shapes)
        if not picked:
            raise ValueError('%s: no mesh piece for %s' % (gid, rule))
        for i in picked:
            rules.setdefault(i, {}).update(rule)
    rects = {}
    for i, pts in enumerate(shapes):
        rule = rules.get(i, {})
        if rule.get('skip'):
            continue
        rs = decompose(pts, fill=rule.get('fill', s.get('fill', FILL)), max_depth=rule.get('depth', MAX_DEPTH))
        big = [r for r in rs if _area(r) >= MIN_AREA]
        rects[i] = big or [max(rs, key=_area)]
    paint = s.get('paint', {})
    prims = []
    for i, g in enumerate(groups):
        rule = rules.get(i, {})
        if rule.get('skip'):
            continue
        source = majority(mats, g)
        zc = float(verts[tris[g]][:, :, 2].mean())
        for k, r in enumerate(rects[i]):
            z0, z1 = z_extent(verts, tris, g, r)
            # A `boxes` rule refines one fitted box of a piece: the one around its point.
            rule = dict(rules.get(i, {}))
            for box in s.get('boxes', []):
                if box['at'] and _inside_rect(np.array([box['at']]), r)[0] and i in _pick(box, shapes):
                    rule.update({k2: v for k2, v in box.items() if k2 != 'at'})
            if rule.get('skip'):
                continue
            width = rule.get('width')
            half_z = width / 2 if width else max(0.08, (z1 - z0) / 2) * rule.get('widen', s.get('widen', 1.0))
            cx, cy, a, hu, hv = r
            if rule.get('round'):
                half_z = width / 2 if width else hv
                hv = rule['height'] / 2 if 'height' in rule else half_z
            mat = rule.get('mat') or _zone_mat(s.get('zones'), cx, cy, paint.get(source, 'steel'), source)
            # A hair of width per box of one piece: boxes that overlap never share a side plane.
            half_z -= 0.004 * (k % 3)
            p = Prim((cx, cy, 8.0 if rule.get('centre', True) else zc), (hu, hv, half_z), (0.0, 0.0, a), mat,
                     rule.get('part', 'body'), rule.get('tag', ''), rule.get('detail', False), i)
            prims += _round(p, rule.get('round', 0))
    for extra in s.get('parts', []):
        prims += _round(_extra(extra), extra.get('round', 0))
    _PRIMS[gid] = prims
    return prims


def _extra(e):
    """A hand-placed box: from/to (model units) or centre/size, an optional tilt (z, or x/y/z)."""
    if 'from' in e:
        lo, hi = np.array(e['from'], float), np.array(e['to'], float)
        centre, half = (lo + hi) / 2, (hi - lo) / 2
    else:
        centre, half = np.array(e['centre'], float), np.array(e['size'], float) / 2
    rot = e.get('rot', 0)
    rot = (0.0, 0.0, float(rot)) if isinstance(rot, (int, float)) else tuple(float(v) for v in rot)
    return Prim(centre, half, rot, e.get('mat', 'steel'), e.get('part', 'body'), e.get('tag', ''), e.get('detail', False))


# ---------------------------------------------------------------- levels of detail

def lod(gid, level):
    prims = fit(gid)
    if level == 'fine':
        return prims
    if level == 'mid':
        return [p for p in prims if not (p.detail and p.volume() < MID_DETAIL)]
    keep = sorted((p for p in prims if not p.detail), key=lambda p: -p.half[0] * p.half[1])[:LOW_ELEMENTS]
    out = []
    for p in keep:
        q = p.copy()
        q.half = q.half * [1.0, 1.0, LOW_WIDEN]
        out.append(q)
    return out


_BUILT = {}


def build(gid, texture_ref='#p'):
    """{'parts': fine elements by part, 'mid': ..., 'low': ..., 'texture': image} for one gun.

    The parts are what the reload moves on its own: 'body', and 'mag' or 'pump' (guns/<id>.yml)."""
    if (gid, texture_ref) in _BUILT:
        return _BUILT[(gid, texture_ref)]
    import gunpaint
    fine = fit(gid)
    levels = {'fine': fine, 'mid': lod(gid, 'mid'), 'low': lod(gid, 'low')}
    texture, painted = gunpaint.paint(fine, levels)
    out = {'texture': texture}
    for name, elements in painted.items():
        parts = {'body': []}
        for box, e in elements:
            if texture_ref != '#p':
                for face in e['faces'].values():
                    face['texture'] = texture_ref
            parts.setdefault(box.part if name != 'low' else 'body', []).append(e)
        out['parts' if name == 'fine' else name] = parts
    _BUILT[(gid, texture_ref)] = out
    return out


def mag_axis(gid):
    """Unit (x, y) a magazine slides out along: the spec's mag_axis, else down the tallest mag box."""
    s = spec(gid)
    if 'mag_axis' in s:
        x, y = s['mag_axis']
        n = math.hypot(x, y)
        return x / n, y / n
    mags = [p for p in fit(gid) if p.part in ('mag', 'pump')]
    if not mags:
        return 0.0, -1.0
    p = max(mags, key=lambda q: q.volume())
    a = math.radians(p.rot[2])
    long_x = p.half[0] >= p.half[1]
    dx, dy = (math.cos(a), math.sin(a)) if long_x else (-math.sin(a), math.cos(a))
    if dy > 0:
        dx, dy = -dx, -dy
    return dx, dy


def style(gid):
    """How the reload goes: 'pump' (shells into a tube), 'mag' (the magazine comes out) or 'tilt'."""
    parts = {p.part for p in fit(gid)}
    return 'pump' if 'pump' in parts else 'mag' if 'mag' in parts else 'tilt'


def sight_y(gid):
    """Height of the iron sight line (top of the front post), or None: aiming puts it on the crosshair."""
    return spec(gid).get('sight_y')


def rail(gid):
    """(x, y) where an optic's base sits on the gun, or None to find the flattest top (models.rail_mount)."""
    r = spec(gid).get('rail')
    return tuple(r) if r else None


# ---------------------------------------------------------------- geometry queries

def inside_any(points, prims, skip=None, eps=1e-3):
    """Which points (N x 3) lie inside any of the boxes (within eps), skipping one of them."""
    out = np.zeros(len(points), dtype=bool)
    for k, p in enumerate(prims):
        if k == skip:
            continue
        local = (points - p.centre) @ p.matrix()
        out |= np.all(np.abs(local) <= p.half + eps, axis=1)
    return out


def iou_side(gid):
    """How well the fitted boxes follow the mesh, side on: intersection over union of the two silhouettes,
    rasterised, over the pieces that are fitted (skipped pieces and hand-placed parts left out)."""
    verts, tris, _ = load_mesh(gid)
    groups = pieces(verts, tris)
    fitted = [p for p in fit(gid) if p.piece >= 0]
    kept = sorted({p.piece for p in fitted})
    lo = verts.min(0)[:2] - 1
    hi = verts.max(0)[:2] + 1
    scale = 10

    def raster(polys):
        img = Image.new('1', (int((hi[0] - lo[0]) * scale), int((hi[1] - lo[1]) * scale)), 0)
        d = ImageDraw.Draw(img)
        for poly in polys:
            d.polygon([((x - lo[0]) * scale, (y - lo[1]) * scale) for x, y in poly], fill=1)
        return np.array(img, dtype=bool)
    mesh = raster(verts[tris[np.concatenate([groups[i] for i in kept])]][:, :, :2].tolist())
    boxes = []
    for p in fitted:
        c = p.corners()
        quads = [c[[0, 2, 6, 4]], c[[1, 3, 7, 5]], c[[0, 1, 3, 2]], c[[4, 5, 7, 6]], c[[0, 1, 5, 4]], c[[2, 3, 7, 6]]]
        boxes += [q[:, :2].tolist() for q in quads]
    model = raster(boxes)
    return float((mesh & model).sum() / max(1, (mesh | model).sum()))


# ---------------------------------------------------------------- debugging

def sheet(gid, out):
    """Side view: the mesh's pieces numbered (as the spec's `piece` rules count them), the fitted boxes
    outlined, a 1-unit grid."""
    verts, tris, mats = load_mesh(gid)
    groups = pieces(verts, tris)
    s = 48
    lo = verts.min(0) - 1
    hi = verts.max(0) + 1
    w, h = int((hi[0] - lo[0]) * s), int((hi[1] - lo[1]) * s)
    img = Image.new('RGB', (w, h), (240, 240, 236))
    d = ImageDraw.Draw(img)

    def to(x, y):
        return (x - lo[0]) * s, h - (y - lo[1]) * s
    for gx in range(int(lo[0]), int(hi[0]) + 1):
        d.line([to(gx, lo[1]), to(gx, hi[1])], fill=(215, 215, 210) if gx % 4 else (180, 180, 175))
        d.text(to(gx + 0.05, lo[1] + 0.4), str(gx), fill=(150, 150, 150))
    for gy in range(int(lo[1]), int(hi[1]) + 1):
        d.line([to(lo[0], gy), to(hi[0], gy)], fill=(215, 215, 210) if gy % 4 else (180, 180, 175))
        d.text(to(lo[0] + 0.05, gy + 0.3), str(gy), fill=(150, 150, 150))
    palette = [(200, 80, 60), (60, 140, 200), (90, 170, 80), (200, 160, 40), (150, 90, 190), (40, 170, 170), (210, 110, 160)]
    for i, g in enumerate(groups):
        col = palette[i % len(palette)]
        for tri in verts[tris[g]]:
            d.polygon([to(x, y) for x, y, _ in tri], fill=tuple(int(c * 0.35 + 165) for c in col))
    for p in fit(gid):
        c = p.corners()
        quad = c[[0, 2, 6, 4]] if True else c
        d.polygon([to(x, y) for x, y, _ in quad[:, :3]], outline=(20, 20, 20))
    for i, g in enumerate(groups):
        centre = verts[tris[g]].reshape(-1, 3).mean(0)
        d.text(to(centre[0], centre[1]), '%d %s' % (i, majority(mats, g)), fill=(0, 0, 0))
    img.save(out)
    print(out)


def stats(gid):
    import gunpaint
    for level in ('fine', 'mid', 'low'):
        prims = lod(gid, level)
        faces = sum(len(f) for f in gunpaint.visible_faces(prims))
        limit = BUDGET[level]
        print('%-5s %-4s %4d elements %5d faces  (budget %d / %d)' % (gid, level, len(prims), faces, limit[0], limit[1]))
    print('%-5s side silhouette IoU against the mesh: %.3f' % (gid, iou_side(gid)))


def main():
    gid, mode = sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else 'stats'
    if mode == 'sheet':
        sheet(gid, sys.argv[3] if len(sys.argv) > 3 else '/tmp/meshprim_%s.png' % gid)
    else:
        stats(gid)


if __name__ == '__main__':
    sys.path.insert(0, HERE)
    main()
