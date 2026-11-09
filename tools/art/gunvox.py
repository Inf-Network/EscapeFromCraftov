"""Paints the voxel guns from meshvox.py and turns them into textured Minecraft box models.

Each gun's voxels keep their source material; PAINT maps those to our own finishes (black steel, tan
polymer, walnut, bakelite...) and repaints regions such as handguards, stocks and magazines. Moving
parts (the magazine, a shotgun's pump) are split off so the reload animation can move them.

Voxels of one part are merged into boxes purely by shape, and every visible box face gets its own
patch in a per-gun texture atlas, one texel per voxel face. Texels are shaded like voxel art:
convex edges catch light, creases fall into shadow, material borders get a panel line, and each
finish adds its own grain (brushed metal, stippled polymer, wood grain).
"""
import json
import math
import os

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
VOXGUNS = os.path.join(HERE, 'voxguns')

# name -> (base colour, finish)
MATERIALS = {
    'black_metal': ('#2E3136', 'metal'),
    'gunmetal': ('#4A4F57', 'metal'),
    'steel': ('#646A72', 'metal'),
    'stainless': ('#A7ACB2', 'metal'),
    'polymer': ('#26282C', 'polymer'),
    'grey_poly': ('#575C63', 'polymer'),
    'fde': ('#A38C67', 'polymer'),
    'od': ('#5B6545', 'polymer'),
    'wood': ('#8C5631', 'wood'),
    'wood_dark': ('#5F3B21', 'wood'),
    'bakelite': ('#7C3519', 'gloss'),
    'glass': ('#2F5E8C', 'glass'),
    'brass': ('#C9A04A', 'metal'),
    'shell': ('#B03A2E', 'gloss'),
}

# Per gun: 'base' maps source materials; 'zones' repaint (material, polygon[, only these source
# materials]); 'mag' is the detachable magazine polygon (top edge first, then bottom edge) and
# 'mag_paint' its finish; 'add_mag' fills the polygon with new voxels for guns modelled without one;
# 'pump' splits off a shotgun forend. Polygons are (x, y) in model units, barrel along +X.
PAINT = {
    'm4c': {
        'base': {'Main': 'black_metal', 'MainDark': 'polymer', 'MainLight': 'gunmetal'},
        'zones': [('fde', [(9.0, 8.3), (19.2, 8.3), (19.2, 11.15), (9.0, 11.15)]),
                  ('fde', [(-9, 6), (-0.4, 6), (-0.4, 12), (-9, 12)]),
                  ('polymer', [(-0.4, 3), (3.6, 3), (3.6, 8.4), (-0.4, 8.4)]),
                  ('steel', [(19.2, 9), (26, 9), (26, 11), (19.2, 11)])],
        'mag': [(4.9, 8.75), (8.7, 8.75), (8.9, 2.6), (5.4, 2.6)], 'mag_paint': 'fde', 'mag_plate': 'polymer',
    },
    'k47': {
        'base': {'Wood': 'wood', 'DarkWood': 'wood_dark', 'Black': 'black_metal', 'DarkMetal': 'gunmetal', 'Metal': 'steel'},
        'zones': [('steel', [(22, 9), (26, 9), (26, 12.5), (22, 12.5)])],
        'mag': [(5.6, 9.3), (8.9, 9.3), (11.0, 2.4), (7.7, 2.4)], 'mag_paint': 'bakelite',
    },
    'sv9': {
        'base': {'Black': 'black_metal', 'DarkMetal': 'gunmetal', 'Grey': 'grey_poly', 'Metal': 'polymer'},
        'zones': [('steel', [(16.5, 9), (20, 9), (20, 11.5), (16.5, 11.5)])],
        'mag': [(8.9, 8.1), (11.5, 8.1), (11.5, 3.2), (8.9, 3.2)], 'mag_paint': 'black_metal', 'mag_plate': 'polymer',
    },
    'v57': {
        'base': {'Black': 'black_metal', 'DarkMetal': 'od', 'Grey': 'gunmetal', 'Metal': 'polymer'},
        'zones': [('steel', [(18.5, 9), (21, 9), (21, 11), (18.5, 11)])],
        'mag': [(7.4, 8.1), (10.2, 8.1), (10.2, 3.6), (7.4, 3.6)], 'mag_paint': 'polymer',
    },
    'scr': {
        'base': {'MainDark': 'fde', 'Main': 'polymer', 'MainLight': 'gunmetal'},
        'zones': [('steel', [(19.6, 9), (23, 9), (23, 11), (19.6, 11)])],
        'mag': [(0.2, 8.0), (2.7, 8.0), (2.7, 4.2), (0.2, 4.2)], 'mag_paint': 'polymer',
    },
    'kl7': {
        'base': {'Black': 'polymer', 'DarkMetal': 'gunmetal', 'Metal': 'steel'},
        'zones': [('steel', [(17, 10), (25, 10), (25, 11.6), (17, 11.6)])],
        'mag': [(4.6, 9.6), (8.0, 9.6), (10.5, 2.6), (7.7, 2.6)], 'mag_paint': 'od', 'mag_plate': 'polymer',
    },
    'r12': {
        'base': {'Wood': 'wood', 'DarkWood': 'wood_dark', 'Metal': 'black_metal', 'DarkMetal': 'gunmetal', 'Black': 'black_metal'},
        'zones': [('steel', [(14, 9.2), (25, 9.2), (25, 10.7), (14, 10.7)])],
        'pump': [(8.3, 9.1), (14.0, 9.1), (14.0, 7.3), (8.3, 7.3)], 'pump_paint': 'wood_dark',
    },
    'dm14': {
        'base': {'Black': 'black_metal', 'DarkMetal': 'gunmetal', 'Glass': 'glass', 'Grey': 'gunmetal', 'LightMetal': 'wood'},
        'zones': [('steel', [(13, 8.5), (26, 8.5), (26, 9.6), (13, 9.6)])],
        'mag': [(5.0, 8.05), (8.3, 8.05), (8.3, 6.3), (5.0, 6.3)], 'mag_paint': 'black_metal',
    },
    'awr': {
        'base': {'Green': 'od', 'Black': 'black_metal', 'DarkMetal': 'gunmetal', 'Glass': 'glass', 'Grey': 'gunmetal'},
        'zones': [('steel', [(14.5, 8.2), (21.5, 8.2), (21.5, 9.4), (14.5, 9.4)])],
        'mag': [(2.4, 7.0), (5.0, 7.0), (5.0, 5.2), (2.4, 5.2)], 'mag_paint': 'black_metal', 'add_mag': (7.4, 8.6),
    },
    'p18': {
        'base': {'Black': 'black_metal', 'Black2': 'polymer', 'DarkMetal': 'fde', 'DarkWood': 'fde', 'Metal': 'steel'},
        'zones': [],
        'mag': [(3.3, 6.5), (6.0, 6.5), (5.6, 4.2), (2.5, 4.2)], 'mag_paint': 'fde', 'mag_plate': 'polymer',
    },
    'd50': {
        'base': {'Black': 'polymer', 'LightMetal': 'steel', 'Metal': 'stainless'},
        'zones': [],
        'mag': [(2.3, 6.3), (5.0, 6.3), (4.7, 4.1), (1.7, 4.1)], 'mag_paint': 'polymer', 'mag_plate': 'stainless',
    },
    'optic_4x': {'base': {'Black': 'black_metal', 'DarkMetal': 'gunmetal', 'Glass': 'glass'}, 'zones': []},
    'optic_8x': {'base': {'Black': 'black_metal', 'DarkMetal': 'gunmetal', 'Glass': 'glass'}, 'zones': []},
}

# Face name -> (normal, u axis (index, sign), v axis (index, sign)): the client's default UV layout.
FACES = {
    'up': ((0, 1, 0), (0, 1), (2, 1)),
    'down': ((0, -1, 0), (0, 1), (2, -1)),
    'north': ((0, 0, -1), (0, -1), (1, -1)),
    'south': ((0, 0, 1), (0, 1), (1, -1)),
    'west': ((-1, 0, 0), (2, 1), (1, -1)),
    'east': ((1, 0, 0), (2, -1), (1, -1)),
}


def _inside(poly, x, y):
    hit = False
    j = len(poly) - 1
    for i in range(len(poly)):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            hit = not hit
        j = i
    return hit


def _hash(*v):
    h = 2166136261
    for n in v:
        h = ((h ^ (int(n) & 0xFFFFFFFF)) * 16777619) & 0xFFFFFFFF
    h ^= h >> 13
    h = (h * 0x5BD1E995) & 0xFFFFFFFF
    return ((h ^ (h >> 15)) & 0xFFFF) / 65535.0


def _hex(c):
    return tuple(int(c[i:i + 2], 16) for i in (1, 3, 5))


def load(gid):
    with open(os.path.join(VOXGUNS, gid + '.json'), encoding='utf-8') as f:
        return json.load(f)


def paint(gid):
    """Returns (voxel size, origin, {part: {(x, y, z): material}})."""
    data = load(gid)
    spec = PAINT.get(gid, {'base': {}, 'zones': []})
    names = list(data['materials'])
    v = data['voxel']
    ox, oy, oz = data['origin']
    parts = {'body': {}}

    def centre(x, y):
        return ox + (x + 0.5) * v, oy + (y + 0.5) * v
    zs = []
    for x, y, z, m in data['voxels']:
        source = names[m]
        mat = spec['base'].get(source, 'gunmetal')
        cx, cy = centre(x, y)
        for zone in spec['zones']:
            if (len(zone) < 3 or source in zone[2]) and _inside(zone[1], cx, cy):
                mat = zone[0]
        part = 'body'
        for key in ('mag', 'pump'):
            if key in spec and _inside(spec[key], cx, cy):
                part = key
                mat = spec.get(key + '_paint', mat)
        if source == 'Glass':
            mat = 'glass'
        parts.setdefault(part, {})[(x, y, z)] = mat
        zs.append(z)
    if 'add_mag' in spec:
        z0, z1 = spec['add_mag']
        xs = [p[0] for p in spec['mag']]
        ys = [p[1] for p in spec['mag']]
        mag = parts.setdefault('mag', {})
        for x in range(int((min(xs) - ox) / v) - 1, int((max(xs) - ox) / v) + 2):
            for y in range(int((min(ys) - oy) / v) - 1, int((max(ys) - oy) / v) + 2):
                cx, cy = centre(x, y)
                if not _inside(spec['mag'], cx, cy):
                    continue
                for z in range(int((z0 - oz) / v), int(math.ceil((z1 - oz) / v))):
                    if (x, y, z) not in parts['body']:
                        mag[(x, y, z)] = spec['mag_paint']
    for key in ('mag', 'pump'):
        if parts.get(key):
            _adopt_strays(parts['body'], parts[key], spec.get(key + '_paint'))
    if 'mag_plate' in spec and parts.get('mag'):
        # The bottom row of the magazine is its base plate.
        mag = parts['mag']
        for (x, y, z) in list(mag):
            if (x, y - 1, z) not in mag and (x, y - 1, z) not in parts['body']:
                mag[(x, y, z)] = spec['mag_plate']
    return v, (ox, oy, oz), parts


def _components(solid):
    seen, out = set(), []
    for start in solid:
        if start in seen:
            continue
        group, stack = [], [start]
        seen.add(start)
        while stack:
            x, y, z = stack.pop()
            group.append((x, y, z))
            for d in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
                n = (x + d[0], y + d[1], z + d[2])
                if n in solid and n not in seen:
                    seen.add(n)
                    stack.append(n)
        out.append(group)
    return out


def _adopt_strays(body, part, paint_as):
    """Body pieces that only hung on via the moving part would float once it leaves; they go with it."""
    groups = sorted(_components(body), key=len, reverse=True)
    for group in groups[1:]:
        if len(group) > len(groups[0]) // 5:
            continue
        for v in group:
            part[v] = paint_as or body[v]
            del body[v]


# ---------------------------------------------------------------- texels

def _texel(mat, vox, normal, du, dv, solid):
    base, finish = MATERIALS[mat]
    r, g, b = _hex(base)
    x, y, z = vox
    f = 1.0
    h = _hash(x, y, z, normal[0] * 3 + normal[1] * 5 + normal[2] * 7)
    if finish == 'metal':
        f *= 1 + 0.05 * (_hash(x, y // 2, z) - 0.5)
    elif finish in ('polymer', 'gloss'):
        f *= 1 + 0.08 * (h - 0.5)
    elif finish == 'wood':
        grain = math.sin(y * 1.9 + z * 0.7 + 2.2 * math.sin(x * 0.21 + y * 0.6))
        f *= 1 + 0.09 * grain + 0.05 * (h - 0.5)
        if _hash(x // 4, y, z // 2, 9) > 0.93:
            f *= 0.85
    elif finish == 'glass':
        f *= 1.25 if y % 3 == 0 and h > 0.6 else 1.0
    edge_gain = {'metal': 0.30, 'polymer': 0.16, 'gloss': 0.28, 'wood': 0.16, 'glass': 0.45}[finish]
    edges = creases = seams = 0
    lit = 0
    for d in (du, (-du[0], -du[1], -du[2]), dv, (-dv[0], -dv[1], -dv[2])):
        nb = (x + d[0], y + d[1], z + d[2])
        if nb not in solid:
            edges += 1
            if d[1] > 0:
                lit = 1
        elif (nb[0] + normal[0], nb[1] + normal[1], nb[2] + normal[2]) in solid:
            creases += 1
        elif solid[nb] != mat:
            seams += 1
    f *= 1 + edge_gain * min(edges, 2) * (1.0 if lit or normal[1] > 0 else 0.7)
    f *= 1 - 0.12 * min(creases, 2)
    f *= 1 - 0.14 * min(seams, 1)
    if normal[1] > 0 and finish == 'gloss' and h > 0.8:
        f *= 1.15
    return tuple(max(0, min(255, int(round(c * f)))) for c in (r, g, b)) + (255,)


# ---------------------------------------------------------------- boxes and atlas

def _boxes(solid):
    """Greedy merge of a part's voxels into boxes by shape alone (the texture carries the colour)."""
    used = set()
    out = []
    for key in sorted(solid, key=lambda k: (k[1], k[2], k[0])):
        if key in used:
            continue
        x, y, z = key

        def free(p):
            return p in solid and p not in used
        x1 = x
        while free((x1 + 1, y, z)):
            x1 += 1
        y1 = y
        while all(free((xx, y1 + 1, z)) for xx in range(x, x1 + 1)):
            y1 += 1
        z1 = z
        while all(free((xx, yy, z1 + 1)) for xx in range(x, x1 + 1) for yy in range(y, y1 + 1)):
            z1 += 1
        for xx in range(x, x1 + 1):
            for yy in range(y, y1 + 1):
                for zz in range(z, z1 + 1):
                    used.add((xx, yy, zz))
        out.append(((x, y, z), (x1 + 1, y1 + 1, z1 + 1)))
    return out


def _unit(axis, sign):
    d = [0, 0, 0]
    d[axis] = sign
    return tuple(d)


def _face_patch(lo, hi, face, solid):
    """Texels of one box face in UV order, or None when every cell is covered by a neighbour."""
    normal, (ua, us), (va, vs) = FACES[face]
    n_axis = next(i for i in range(3) if normal[i])
    fixed = hi[n_axis] - 1 if normal[n_axis] > 0 else lo[n_axis]
    w, h = hi[ua] - lo[ua], hi[va] - lo[va]
    du, dv = _unit(ua, us), _unit(va, vs)
    cells = []
    visible = False
    for j in range(h):
        row = []
        for i in range(w):
            p = [0, 0, 0]
            p[n_axis] = fixed
            p[ua] = lo[ua] + i if us > 0 else hi[ua] - 1 - i
            p[va] = lo[va] + j if vs > 0 else hi[va] - 1 - j
            p = tuple(p)
            if (p[0] + normal[0], p[1] + normal[1], p[2] + normal[2]) not in solid:
                visible = True
            row.append((p, normal, du, dv))
        cells.append(row)
    return (w, h, cells) if visible else None


class Atlas:
    """Shelf-packed texture of face patches, each with a one-texel extruded border against bleeding."""

    def __init__(self):
        self.patches = []

    def add(self, w, h, pixels):
        self.patches.append([w, h, pixels, None])
        return self.patches[-1]

    def pack(self):
        size = 16
        while True:
            x = y = shelf = 0
            ok = True
            for p in sorted(self.patches, key=lambda p: -(p[1] + 2)):
                w, h = p[0] + 2, p[1] + 2
                if x + w > size:
                    x, y, shelf = 0, y + shelf, 0
                if x + w > size or y + h > size:
                    ok = False
                    break
                p[3] = (x + 1, y + 1)
                x += w
                shelf = max(shelf, h)
            if ok:
                break
            size *= 2
        img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        px = img.load()
        for w, h, pixels, (ax, ay) in self.patches:
            for j in range(-1, h + 1):
                for i in range(-1, w + 1):
                    px[ax + i, ay + j] = pixels[min(h - 1, max(0, j))][min(w - 1, max(0, i))]
        self.size = size
        self.image = img
        return img

    def uv(self, patch):
        w, h, _, (ax, ay) = patch
        s = 16.0 / self.size
        return [round(ax * s, 5), round(ay * s, 5), round((ax + w) * s, 5), round((ay + h) * s, 5)]


_CACHE = {}


def build(gid, texture_ref='#p'):
    """{'parts': {name: elements}, 'texture': atlas image, 'voxel', 'origin', 'painted'} for one gun or optic."""
    if (gid, texture_ref) in _CACHE:
        return _CACHE[(gid, texture_ref)]
    v, (ox, oy, oz), parts = paint(gid)
    atlas = Atlas()
    pending = {}
    for name, solid in parts.items():
        items = []
        for lo, hi in _boxes(solid):
            faces = {}
            for face in FACES:
                patch = _face_patch(lo, hi, face, solid)
                if patch is None:
                    continue
                w, h, cells = patch
                pixels = [[_texel(solid[p], p, n, du, dv, solid) for (p, n, du, dv) in row] for row in cells]
                faces[face] = atlas.add(w, h, pixels)
            if faces:
                items.append((lo, hi, faces))
        pending[name] = items
    texture = atlas.pack()
    out = {}
    for name, items in pending.items():
        elements = []
        for lo, hi, faces in items:
            elements.append({
                'from': [round(ox + lo[0] * v, 4), round(oy + lo[1] * v, 4), round(oz + lo[2] * v, 4)],
                'to': [round(ox + hi[0] * v, 4), round(oy + hi[1] * v, 4), round(oz + hi[2] * v, 4)],
                'faces': {f: {'uv': atlas.uv(p), 'texture': texture_ref} for f, p in faces.items()}})
        out[name] = elements
    result = {'parts': out, 'texture': texture, 'voxel': v, 'origin': (ox, oy, oz), 'painted': parts}
    _CACHE[(gid, texture_ref)] = result
    return result


def mag_axis(gid):
    """Unit (x, y) direction a magazine slides out along: from the middle of its top edge to its bottom edge."""
    poly = PAINT[gid].get('mag') or PAINT[gid].get('pump')
    if not poly:
        return 0.0, -1.0
    tx, ty = (poly[0][0] + poly[1][0]) / 2, (poly[0][1] + poly[1][1]) / 2
    bx, by = (poly[2][0] + poly[3][0]) / 2, (poly[2][1] + poly[3][1]) / 2
    length = math.hypot(bx - tx, by - ty)
    return (bx - tx) / length, (by - ty) / length


def side_image(gid, px=1):
    """Side view (x right, y up) of the painted voxels as seen from +Z, for inventory icons."""
    v, _, parts = paint(gid)
    solid = {}
    for p in parts.values():
        solid.update(p)
    xs = [k[0] for k in solid]
    ys = [k[1] for k in solid]
    x0, y0, y1 = min(xs), min(ys), max(ys)
    img = Image.new('RGBA', ((max(xs) - x0 + 1) * px, (y1 - y0 + 1) * px), (0, 0, 0, 0))
    front = {}
    for (x, y, z) in solid:
        if (x, y) not in front or z > front[(x, y)]:
            front[(x, y)] = z
    for (x, y), z in front.items():
        col = _texel(solid[(x, y, z)], (x, y, z), (0, 0, 1), (1, 0, 0), (0, -1, 0), solid)
        for i in range(px):
            for j in range(px):
                img.putpixel(((x - x0) * px + i, (y1 - y) * px + j), col)
    return img
