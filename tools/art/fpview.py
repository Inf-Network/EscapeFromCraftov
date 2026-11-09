"""Offline first-person preview of a held item model, using the same transform chain as the
1.21.11 client (ItemInHandRenderer.applyItemArmTransform -> ItemTransform.apply -> -0.5 centring,
70 degree hand FOV). Lets display transforms be tuned without launching the game.

    python3 tools/art/fpview.py k47 [firstperson_righthand|ads] out.png
    python3 tools/art/fpview.py k47 reload sheet.png [optic id]     # contact sheet of reload frames
"""
import json
import math
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(__file__), '..', '..', 'src', 'main', 'resources', 'pack', 'craftov',
                    'resourcepack', 'assets', 'craftov')
W, H = 1000, 584          # the player's window aspect (2000x1168)
HAND_FOV = 70.0           # the hand is always rendered with a fixed 70 degree FOV
SHADE = {'up': 1.0, 'down': 0.5, 'north': 0.8, 'south': 0.8, 'east': 0.6, 'west': 0.6}


def mat_mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def translate(x, y, z):
    return [[1, 0, 0, x], [0, 1, 0, y], [0, 0, 1, z], [0, 0, 0, 1]]


def scale(x, y, z):
    return [[x, 0, 0, 0], [0, y, 0, 0], [0, 0, z, 0], [0, 0, 0, 1]]


def rot(axis, deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    if axis == 'x':
        return [[1, 0, 0, 0], [0, c, -s, 0], [0, s, c, 0], [0, 0, 0, 1]]
    if axis == 'y':
        return [[c, 0, s, 0], [0, 1, 0, 0], [-s, 0, c, 0], [0, 0, 0, 1]]
    return [[c, -s, 0, 0], [s, c, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]


def apply(m, v):
    x, y, z = v
    return tuple(m[i][0] * x + m[i][1] * y + m[i][2] * z + m[i][3] for i in range(3))


def hand_matrix(display, left=False):
    """Pose for the main-hand item: arm offset, then the model's display transform, then -0.5 centring."""
    sign = -1 if left else 1
    t = [v / 16.0 for v in display.get('translation', [0, 0, 0])]
    r = display.get('rotation', [0, 0, 0])
    s = display.get('scale', [1, 1, 1])
    ry, rz = (-r[1], -r[2]) if left else (r[1], r[2])
    m = translate(sign * 0.56, -0.52, -0.72)
    m = mat_mul(m, translate(sign * t[0], t[1], t[2]))
    m = mat_mul(m, mat_mul(rot('x', r[0]), mat_mul(rot('y', ry), rot('z', rz))))
    m = mat_mul(m, scale(*s))
    return mat_mul(m, translate(-0.5, -0.5, -0.5))


def load_model(path):
    with open(path, encoding='utf-8') as f:
        model = json.load(f)
    if 'parent' in model and model['parent'].startswith('craftov:'):
        parent = model['parent'].split(':', 1)[-1]
        base = load_model(os.path.join(ROOT, 'models', parent + '.json'))
        base.setdefault('display', {}).update(model.get('display', {}))
        base.setdefault('textures', {}).update(model.get('textures', {}))
        if 'elements' in model:
            base['elements'] = model['elements']
        return base
    return model


def load_texture(model, key='p'):
    refs = model.get('textures', {})
    ref = refs.get(key, refs.get('p', 'craftov:item/gun_palette')).split(':', 1)[-1]
    return Image.open(os.path.join(ROOT, 'textures', ref + '.png')).convert('RGBA')


def palette_color(texture, uvs):
    u = (uvs[0] + uvs[2]) / 2.0
    v = (uvs[1] + uvs[3]) / 2.0
    w, h = texture.size
    return texture.getpixel((min(w - 1, int(u / 16.0 * w)), min(h - 1, int(v / 16.0 * h))))[:3]


def faces_of(element):
    (x0, y0, z0), (x1, y1, z1) = element['from'], element['to']
    corners = {
        'north': [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)],
        'south': [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)],
        'east': [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)],
        'west': [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)],
        'up': [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
        'down': [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
    }
    rotation = element.get('rotation')
    for name, pts in corners.items():
        if name not in element.get('faces', {}):
            continue
        if rotation:
            o = rotation['origin']
            r = rot(rotation['axis'], rotation['angle'])
            pts = [tuple(c + d for c, d in zip(apply(r, (p[0] - o[0], p[1] - o[1], p[2] - o[2])), o)) for p in pts]
        yield name, [(p[0] / 16.0, p[1] / 16.0, p[2] / 16.0) for p in pts], element['faces'][name]


def project(p):
    x, y, z = p
    if z > -0.05:
        return None
    f = 1.0 / math.tan(math.radians(HAND_FOV) / 2)
    aspect = W / H
    return ((f / aspect * x / -z + 1) / 2 * W, (1 - f * y / -z) / 2 * H)


def zbuffer_fill(img, polys):
    """Rasterises quads with a depth buffer (nearest wins), like the game does.

    Each poly is (view z per corner, screen corners, shade, texture array, uv corners). Texture
    coordinates are interpolated perspective-correctly and sampled nearest-neighbour."""
    import numpy as np
    w, h = img.size
    depth = np.full((h, w), -1e9)
    pix = np.array(img)
    for zs, screen, shade, tex, uvs in polys:
        th, tw = tex.shape[:2]
        for tri in ((0, 1, 2), (0, 2, 3)):
            (x0, y0), (x1, y1), (x2, y2) = (screen[i] for i in tri)
            z0, z1, z2 = (zs[i] for i in tri)
            (u0, v0), (u1, v1), (u2, v2) = (uvs[i] for i in tri)
            minx, maxx = max(0, int(min(x0, x1, x2))), min(w - 1, int(max(x0, x1, x2)) + 1)
            miny, maxy = max(0, int(min(y0, y1, y2))), min(h - 1, int(max(y0, y1, y2)) + 1)
            if minx > maxx or miny > maxy:
                continue
            area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
            if abs(area) < 1e-9:
                continue
            ys, xs = np.mgrid[miny:maxy + 1, minx:maxx + 1]
            px, py = xs + 0.5, ys + 0.5
            w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) / area
            w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) / area
            w2 = 1 - w0 - w1
            inside = (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
            # Interpolate 1/z in screen space for correct perspective depth (z is negative, nearer = larger).
            inv = w0 / z0 + w1 / z1 + w2 / z2
            inv = np.where(inv == 0, -1e-9, inv)
            zz = 1.0 / inv
            uu = (w0 * u0 / z0 + w1 * u1 / z1 + w2 * u2 / z2) / inv
            vv = (w0 * v0 / z0 + w1 * v1 / z1 + w2 * v2 / z2) / inv
            tx = np.clip((uu / 16.0 * tw).astype(int), 0, tw - 1)
            ty = np.clip((vv / 16.0 * th).astype(int), 0, th - 1)
            texel = tex[ty, tx]
            region = depth[miny:maxy + 1, minx:maxx + 1]
            mask = inside & (zz > region) & (texel[..., 3] > 0)
            region[mask] = zz[mask]
            pix[miny:maxy + 1, minx:maxx + 1][mask] = (texel[..., :3][mask] * shade).astype(np.uint8)
    img.paste(Image.fromarray(pix))


def face_polys(model, display, textures):
    """Visible, textured faces of one model posed in the right hand."""
    import numpy as np
    m = hand_matrix(display)
    polys = []
    for element in model['elements']:
        for name, pts, face in faces_of(element):
            view = [apply(m, p) for p in pts]
            a, b, c = view[0], view[1], view[2]
            n = ((b[1] - a[1]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[1] - a[1]),
                 (b[2] - a[2]) * (c[0] - a[0]) - (b[0] - a[0]) * (c[2] - a[2]),
                 (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]))
            centre = tuple(sum(p[i] for p in view) / 4 for i in range(3))
            if n[0] * centre[0] + n[1] * centre[1] + n[2] * centre[2] >= 0:
                continue
            screen = [project(p) for p in view]
            if any(s is None for s in screen):
                continue
            key = face.get('texture', '#p').lstrip('#')
            ref = model.get('textures', {}).get(key, 'craftov:item/gun_palette')
            if ref not in textures:
                textures[ref] = np.array(load_texture(model, key))
            u0, v0, u1, v1 = face['uv']
            k = 1.0 if element.get('light_emission') else SHADE[name]
            polys.append(([p[2] for p in view], screen, k, textures[ref], [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]))
    return polys


def render_many(posed, out, label=''):
    """posed: [(model, right-hand display)] drawn together, like a composite item model."""
    textures = {}
    polys = []
    for model, display in posed:
        if display.get('scale', [1])[0] == 0:
            continue
        polys += face_polys(model, display, textures)
    img = Image.new('RGB', (W, H), (120, 150, 185))
    d = ImageDraw.Draw(img)
    d.rectangle([0, H * 0.55, W, H], fill=(110, 112, 108))
    zbuffer_fill(img, polys)
    d = ImageDraw.Draw(img)
    cx, cy = W // 2, H // 2
    d.line([cx - 8, cy, cx + 8, cy], fill=(255, 255, 255), width=2)
    d.line([cx, cy - 8, cx, cy + 8], fill=(255, 255, 255), width=2)
    hw = int(W * 0.131)
    d.rectangle([cx - hw, H - 40, cx + hw, H - 4], outline=(30, 30, 30), width=2)
    d.text((10, 10), label, fill=(255, 255, 255))
    img.save(out)


def render(model, display, out, label=''):
    render_many([(model, display)], out, label)


def model_path(ref):
    return os.path.join(ROOT, 'models', ref.split(':', 1)[-1].split('/', 1)[-1] + '.json')


def reload_sheet(gid, out, optic=None, cols=6, step=2):
    """Contact sheet of the reload frames (every `step`-th frame), each a composite of its part models."""
    folder = os.path.join(ROOT, 'models', 'item', 'gun_reload', gid)
    names = sorted(os.listdir(folder))
    count = 1 + max(int(n.rsplit('_', 1)[1][:-5]) for n in names)
    parts = sorted({n.rsplit('_', 1)[0] for n in names})
    parts = [p for p in parts if not p.startswith('optic_') or p == optic]
    tiles = []
    for k in range(0, count, step):
        posed = []
        for part in parts:
            model = load_model(os.path.join(folder, '%s_%d.json' % (part, k)))
            posed.append((model, model['display']['firstperson_righthand']))
        tmp = '/tmp/_fp_frame.png'
        render_many(posed, tmp, '%s frame %d' % (gid, k))
        tiles.append(Image.open(tmp).resize((W // 2, H // 2)))
    rows = (len(tiles) + cols - 1) // cols
    sheet = Image.new('RGB', (cols * W // 2, rows * H // 2))
    for i, t in enumerate(tiles):
        sheet.paste(t, ((i % cols) * W // 2, (i // cols) * H // 2))
    sheet.save(out)
    print(out)


def main():
    gid = sys.argv[1] if len(sys.argv) > 1 else 'k47'
    which = sys.argv[2] if len(sys.argv) > 2 else 'firstperson_righthand'
    out = sys.argv[3] if len(sys.argv) > 3 else '/tmp/fp_%s_%s.png' % (gid, which)
    if which == 'reload':
        reload_sheet(gid, out, sys.argv[4] if len(sys.argv) > 4 else None)
        return
    folder = 'gun_ads' if which == 'ads' else 'gun'
    model = load_model(os.path.join(ROOT, 'models', 'item', folder, gid + '.json'))
    display = model['display']['firstperson_righthand']
    render(model, display, out, '%s %s %s' % (gid, which, display))
    print(out)


if __name__ == '__main__':
    main()


def rot_zyx(z, y, x):
    """Quaternionf.rotationZYX(z, y, x): R = Rz * Ry * Rx (radians)."""
    return mat_mul(rot('z', math.degrees(z)), mat_mul(rot('y', math.degrees(y)), rot('x', math.degrees(x))))


def third_person_matrix(display, arm_x, arm_y=0.0):
    """Model-space pose of an item in the right hand of a humanoid (HumanoidModel + ItemInHandLayer)."""
    m = translate(-5 / 16.0, 2 / 16.0, 0)
    m = mat_mul(m, rot_zyx(0.0, arm_y, arm_x))
    m = mat_mul(m, rot('x', -90))
    m = mat_mul(m, rot('y', 180))
    m = mat_mul(m, translate(1 / 16.0, 0.125, -0.625))
    t = [v / 16.0 for v in display.get('translation', [0, 0, 0])]
    r = display.get('rotation', [0, 0, 0])
    m = mat_mul(m, translate(*t))
    m = mat_mul(m, mat_mul(rot('x', r[0]), mat_mul(rot('y', r[1]), rot('z', r[2]))))
    m = mat_mul(m, scale(*display.get('scale', [1, 1, 1])))
    return mat_mul(m, translate(-0.5, -0.5, -0.5)), mat_mul(translate(-5 / 16.0, 2 / 16.0, 0), rot_zyx(0.0, arm_y, arm_x))


def render_side(model, display, arm_x, out, label=''):
    """Side view (facing right) of a humanoid holding the item: model space has +Y down and -Z forward."""
    texture = load_texture(model)
    item_m, arm_m = third_person_matrix(display, arm_x)
    size, px = 600, 220.0

    def to_screen(p):
        return (size / 2 + (-p[2]) * px, size / 2 + p[1] * px)
    img = Image.new('RGB', (size, size), (120, 150, 185))
    d = ImageDraw.Draw(img)
    body = [(-4 / 16, 0, -2 / 16), (4 / 16, 12 / 16, 2 / 16)]
    d.rectangle([to_screen((0, body[0][1], 2 / 16))[0], to_screen((0, body[0][1], 0))[1],
                 to_screen((0, 0, -2 / 16))[0], to_screen((0, body[1][1], 0))[1]], fill=(60, 90, 140))
    d.rectangle([to_screen((0, 0, 4 / 16))[0], to_screen((0, -8 / 16, 0))[1], to_screen((0, 0, -4 / 16))[0],
                 to_screen((0, 0, 0))[1]], fill=(200, 160, 120))
    arm = [apply(arm_m, (x / 16, y / 16, z / 16)) for x, y, z in ((-1, -2, -2), (-1, 10, -2), (-1, 10, 2), (-1, -2, 2))]
    d.polygon([to_screen(p) for p in arm], fill=(200, 160, 120))
    polys = []
    for element in model['elements']:
        for name, pts, face in faces_of(element):
            world = [apply(item_m, p) for p in pts]
            polys.append((sum(p[0] for p in world) / 4, [to_screen(p) for p in world],
                          tuple(int(c * SHADE[name]) for c in palette_color(texture, face['uv']))))
    for _, screen, col in sorted(polys, key=lambda p: -p[0]):
        d.polygon(screen, fill=col)
    d.text((10, 10), label + '  (facing right)', fill=(255, 255, 255))
    img.save(out)
