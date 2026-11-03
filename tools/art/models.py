"""Box-built 3D models (guns, containers) with flat palette textures, plus 2D icon renders."""
import json
from PIL import Image
from px import Canvas, rgb, shade

# Shared 16-color palette laid out as 4x4 cells on a 16x16 texture.
GUN_PALETTE = ['#1B1C1F', '#2D3035', '#4A4F57', '#7A5230', '#4F3420', '#B59E76', '#5B653C', '#2F7FB8',
               '#E23B3B', '#C9A24A', '#8D949C', '#111214', '#3A3D42', '#6E7B48', '#D8D2C2', '#9A6A3A']
BOX_PALETTE = ['#4C6B3C', '#3A5130', '#B08A5A', '#7E5E36', '#C8CCD2', '#8A9198', '#2E3236', '#C0392B',
               '#E8E8E8', '#E2B33C', '#1F2E46', '#6B7079', '#F2C14E', '#3D6FB6', '#2B2B2B', '#5A4632']


def palette_texture(colors):
    img = Image.new('RGBA', (16, 16))
    for i, c in enumerate(colors):
        x, y = (i % 4) * 4, (i // 4) * 4
        for dx in range(4):
            for dy in range(4):
                img.putpixel((x + dx, y + dy), rgb(c))
    return img


def uv(i):
    x, y = (i % 4) * 4, (i // 4) * 4
    return [x + 1, y + 1, x + 3, y + 3]


def element(box, tex='#p'):
    x0, y0, z0, x1, y1, z1, col = box[:7]
    rot = box[7] if len(box) > 7 else None
    e = {'from': [x0, y0, z0], 'to': [x1, y1, z1],
         'faces': {f: {'uv': uv(col), 'texture': tex} for f in ('north', 'south', 'east', 'west', 'up', 'down')}}
    if rot:
        axis, angle, origin = rot
        e['rotation'] = {'angle': angle, 'axis': axis, 'origin': origin}
    return e


# ---------------------------------------------------------------- guns
# Boxes: (x0,y0,z0, x1,y1,z1, palette index[, (axis, angle, origin)]). +Z is the muzzle direction.
K, DG, MG, WD, DW, TN, OL, GL, RD, BR, LG, BK, RL, OD, WH, LW = range(16)


def rifle_k47():
    return [(7, 8, 0, 9, 11, 7, WD), (7, 9.5, 6, 9, 11.5, 9, WD), (6.8, 9, 9, 9.2, 12, 18, DG), (7.2, 12, 10, 8.8, 12.6, 17, MG),
            (7.2, 5, 13, 8.8, 9, 15, DW, ('x', -22.5, [8, 9, 14])), (7.1, 3, 16, 8.9, 9, 19, K, ('x', 22.5, [8, 9, 17])),
            (6.9, 8.8, 18, 9.1, 11.6, 24, WD), (7.4, 11.6, 18, 8.6, 12.4, 25, MG), (7.5, 10, 24, 8.5, 11, 31, K),
            (7.6, 11, 28, 8.4, 13, 29, K), (7.3, 9.8, 31, 8.7, 11.2, 32, DG)]


def rifle_m4c():
    return [(7, 8, 0, 9, 11.5, 5, BK), (7.4, 10, 5, 8.6, 11, 9, DG), (6.8, 9, 9, 9.2, 12, 18, DG),
            (7.2, 12, 9.5, 8.8, 12.6, 18, RL), (7.3, 12.6, 11, 8.7, 14.4, 14, K), (7.5, 13, 13.6, 8.5, 14, 14.1, RD),
            (7.2, 5, 12, 8.8, 9, 14, BK, ('x', -22.5, [8, 9, 13])), (7.2, 3, 15, 8.8, 9, 17.5, MG, ('x', 11.25, [8, 9, 16])),
            (6.9, 8.8, 18, 9.1, 12, 26, RL), (7.5, 9.8, 26, 8.5, 10.8, 31, K), (7.4, 9.6, 30, 8.6, 11, 32, DG)]


def smg_sv9():
    return [(7.3, 9, 2, 8.7, 10.5, 7, K), (7, 8, 2, 9, 10, 3, K), (6.9, 9, 7, 9.1, 12, 17, DG), (7.3, 12, 8, 8.7, 13, 15, K),
            (7.2, 5, 10, 8.8, 9, 12, BK, ('x', -22.5, [8, 9, 11])), (7.2, 3, 13, 8.8, 9, 15, K, ('x', 22.5, [8, 9, 14])),
            (6.9, 8.6, 17, 9.1, 11.8, 21, BK), (7.5, 9.8, 21, 8.5, 10.8, 24, K)]


def pdw_v57():
    return [(6.6, 6.5, 3, 9.4, 12, 20, TN), (6.8, 12, 6, 9.2, 13, 18, GL), (7.3, 13, 9, 8.7, 14.5, 13, K),
            (7, 4.5, 8, 9, 7, 11, TN, ('x', -22.5, [8, 6, 9])), (7.5, 9.5, 20, 8.5, 10.5, 23, K), (6.8, 6, 3, 9.2, 8, 7, DG)]


def rifle_scr():
    return [(7, 8, 0, 9, 11.5, 6, TN), (6.8, 9, 6, 9.2, 12.2, 18, TN), (7.2, 12.2, 7, 8.8, 12.8, 22, RL),
            (7.1, 12.8, 9, 8.9, 14.8, 15, K), (7.4, 13.6, 15, 8.6, 14.2, 15.3, GL),
            (7.2, 5, 12, 8.8, 9, 14, BK, ('x', -22.5, [8, 9, 13])), (7.2, 3.5, 15, 8.8, 9, 17.5, K),
            (6.9, 8.8, 18, 9.1, 12, 25, TN), (7.5, 9.9, 25, 8.5, 10.9, 31, K), (7.3, 9.6, 30, 8.7, 11.2, 32, DG)]


def lmg_kl7():
    return [(7, 8, 0, 9, 11.5, 6, BK), (6.7, 8.8, 6, 9.3, 12.4, 18, DG), (7.2, 12.4, 7, 8.8, 13, 17, RL),
            (7.2, 5, 11, 8.8, 9, 13, BK, ('x', -22.5, [8, 9, 12])), (5.8, 2.5, 13, 10.2, 8.8, 18, OD),
            (6.9, 8.8, 18, 9.1, 12, 23, DG), (7.5, 9.8, 23, 8.5, 10.8, 32, K), (7.6, 10.8, 29, 8.4, 12.4, 30, K),
            (7.6, 4, 24, 8.4, 9.8, 25, K, ('x', -22.5, [8, 9.8, 24.5]))]


def shotgun_r12():
    return [(7, 7.5, 0, 9, 11, 8, WD), (6.8, 9, 8, 9.2, 12, 15, DG), (7.2, 5, 10, 8.8, 9, 12, WD, ('x', -22.5, [8, 9, 11])),
            (7.3, 8.2, 15, 8.7, 9.4, 28, K), (6.9, 7.4, 17, 9.1, 10, 22, DW), (7.3, 9.9, 15, 8.7, 11.2, 31, MG),
            (7.6, 11.2, 29, 8.4, 11.8, 30, BR)]


def dmr_dm14():
    return [(7, 7.5, 0, 9, 11.2, 9, WD), (6.8, 9, 9, 9.2, 12, 18, DG), (7.2, 5, 12, 8.8, 9, 14, WD, ('x', -22.5, [8, 9, 13])),
            (7.2, 3.8, 15, 8.8, 9, 17, K), (6.9, 8.8, 18, 9.1, 11.8, 25, WD), (7.5, 9.8, 25, 8.5, 10.8, 32, K),
            (7.2, 12, 10, 8.8, 13, 17, K), (7, 13, 9, 9, 15.4, 18, BK), (7.3, 13.4, 18, 8.7, 15, 18.4, GL)]


def sniper_awr():
    return [(7, 7, 0, 9, 11, 9, OD), (7.3, 7.6, 3, 8.7, 9.4, 6, (0)), (6.8, 9, 9, 9.2, 12, 19, OD),
            (7.2, 5, 12, 8.8, 9, 14, OD, ('x', -22.5, [8, 9, 13])), (7.2, 6.5, 15, 8.8, 9, 18, K),
            (9.2, 10.5, 15, 10.8, 11.5, 16, LG), (7.6, 10, 19, 8.4, 10.8, 32, K),
            (6.9, 12.6, 7, 9.1, 15.8, 20, BK), (7.2, 13, 20, 8.8, 15.4, 20.5, GL), (7.4, 12, 11, 8.6, 12.6, 16, DG),
            (7.6, 6, 22, 8.4, 10, 23, K, ('x', 22.5, [8, 10, 22.5]))]


def pistol_p18():
    return [(7.2, 10, 6, 8.8, 12.2, 15, DG), (7.25, 8.6, 6, 8.75, 10, 14, K),
            (7.2, 3.5, 6, 8.8, 9, 8.8, K, ('x', -22.5, [8, 9, 7])), (7.6, 12.2, 13.8, 8.4, 12.8, 14.4, K),
            (7.5, 8, 9, 8.5, 8.6, 11, K)]


def pistol_d50():
    return [(7, 10, 5, 9, 13, 16, LG), (7.1, 8.3, 5, 8.9, 10, 14.5, MG),
            (7.1, 3, 5, 8.9, 9, 8.5, BK, ('x', -22.5, [8, 9, 6.5])), (7.5, 13, 14.5, 8.5, 13.8, 15.5, K),
            (7.4, 7.6, 8.5, 8.6, 8.4, 11, MG)]


GUNS = {'k47': rifle_k47, 'm4c': rifle_m4c, 'sv9': smg_sv9, 'v57': pdw_v57, 'scr': rifle_scr, 'kl7': lmg_kl7,
        'r12': shotgun_r12, 'dm14': dmr_dm14, 'awr': sniper_awr, 'p18': pistol_p18, 'd50': pistol_d50}
PISTOLS = {'p18', 'd50'}


def _to_x_axis(box, shift):
    """Re-orients a box authored muzzle-along-+Z so the barrel runs along +X.

    Vanilla handheld items (swords) point their sprite's +X forward in first person with a
    [0, -90, 25] display rotation; building guns along +X lets them reuse that proven transform.
    """
    x0, y0, z0, x1, y1, z1, col = box[:7]
    out = [z0 + shift, y0, 16 - x1, z1 + shift, y1, 16 - x0, col]
    if len(box) > 7 and box[7]:
        axis, angle, origin = box[7]
        out.append(('z', -angle, [origin[2] + shift, origin[1], 16 - origin[0]]))
    return out


def gun_model(gid):
    pistol = gid in PISTOLS
    shift = 0 if pistol else -4
    elements = [element(_to_x_axis(b, shift)) for b in GUNS[gid]()]
    fp = 0.8 if pistol else 0.55
    tp = 0.75 if pistol else 0.6
    return {
        'textures': {'p': 'craftov:item/gun_palette', 'particle': 'craftov:item/gun_palette'},
        'elements': elements,
        'display': {
            'firstperson_righthand': {'rotation': [0, -90, 4], 'translation': [1.13, 2.6 if pistol else 2.2, 1.13], 'scale': [fp, fp, fp]},
            'firstperson_lefthand': {'rotation': [0, 90, -4], 'translation': [1.13, 2.6 if pistol else 2.2, 1.13], 'scale': [fp, fp, fp]},
            'thirdperson_righthand': {'rotation': [0, -90, 0], 'translation': [0, 3, 1], 'scale': [tp, tp, tp]},
            'thirdperson_lefthand': {'rotation': [0, 90, 0], 'translation': [0, 3, 1], 'scale': [tp, tp, tp]},
            'ground': {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [0.4, 0.4, 0.4]},
            'fixed': {'rotation': [0, 180, 0], 'scale': [0.55, 0.55, 0.55]},
            'head': {'rotation': [0, -90, 0], 'scale': [0.55, 0.55, 0.55]},
        },
    }


def gun_ai_model(gid):
    """Variant for zombie soldiers: their arms are raised ~80 degrees, so pre-tilt the gun to point ahead."""
    return {'parent': 'craftov:item/gun/' + gid, 'display': {
        'thirdperson_righthand': {'rotation': [80, -90, 0], 'translation': [0, 0, 1], 'scale': [0.65, 0.65, 0.65]},
        'thirdperson_lefthand': {'rotation': [80, 90, 0], 'translation': [0, 0, 1], 'scale': [0.65, 0.65, 0.65]}}}


def gun_icon(gid):
    """Side-view 2D icon rendered from the box geometry (z horizontal, y vertical)."""
    boxes = GUNS[gid]()
    zs = [b[2] for b in boxes] + [b[5] for b in boxes]
    ys = [b[1] for b in boxes] + [b[4] for b in boxes]
    span_z = max(zs) - min(zs)
    span_y = max(ys) - min(ys)
    scale = min(30 / span_z, 26 / max(1, span_y))
    c = Canvas()
    oz = (32 - span_z * scale) / 2
    oy = (32 - span_y * scale) / 2
    for b in sorted(boxes, key=lambda b: -(b[3] - b[0])):
        col = rgb(GUN_PALETTE[b[6]])
        x0 = oz + (b[2] - min(zs)) * scale
        x1 = oz + (b[5] - min(zs)) * scale
        y0 = oy + (max(ys) - b[4]) * scale
        y1 = oy + (max(ys) - b[1]) * scale
        if len(b) > 7 and b[7]:
            sk = 2 if b[7][1] > 0 else -2
            c.poly([(x0, y0), (x1, y0), (x1 + sk, y1), (x0 + sk, y1)], col)
        else:
            c.box(int(round(x0)), int(round(y0)), max(int(round(x0)), int(round(x1)) - 1),
                  max(int(round(y0)), int(round(y1)) - 1), col)
    return c.outline().img


# ---------------------------------------------------------------- containers
CONTAINERS = {
    'crate': [(1, 0, 2, 15, 9, 14, 2), (1, 9, 2, 15, 10, 14, 3), (0.8, 2, 1.8, 15.2, 3, 14.2, 3), (0.8, 6, 1.8, 15.2, 7, 14.2, 3)],
    'parcel': [(3, 0, 4, 13, 7, 12, 2), (3, 6.8, 7.4, 13, 7.2, 8.6, 9), (6, 3, 3.9, 10, 5, 4, 8)],
    'weapon_case': [(0, 0, 4, 16, 5, 12, 0), (0, 5, 4, 16, 6, 12, 1), (7, 2, 3.6, 9, 4, 4, 9), (1, 2, 3.6, 3, 4, 4, 6), (13, 2, 3.6, 15, 4, 4, 6)],
    'ammo_box': [(3, 0, 4, 13, 7, 12, 0), (2.8, 7, 3.8, 13.2, 8, 12.2, 1), (6.5, 8, 7, 9.5, 9, 9, 6), (5, 3, 3.9, 11, 5, 4, 9)],
    'med_box': [(2, 0, 4, 14, 9, 12, 8), (2, 9, 4, 14, 10, 12, 4), (7, 3, 3.9, 9, 7, 4, 7), (6, 4, 3.9, 10, 6, 4, 7), (6, 10, 7, 10, 11, 9, 6)],
    'toolbox': [(1, 0, 5, 15, 7, 11, 7), (1, 7, 5, 15, 8, 11, 6), (5, 8, 7.5, 11, 10, 8.5, 6), (1, 4, 4.9, 15, 4.5, 5, 6)],
    'computer': [(4, 0, 2, 12, 15, 14, 4), (4.5, 10, 1.9, 11.5, 13, 2, 6), (5, 5, 1.9, 7, 6, 2, 13), (4, 0, 2, 12, 0.5, 14, 6)],
    'filing_cabinet': [(2, 0, 3, 14, 22, 13, 5), (2.5, 1, 2.9, 13.5, 7, 3, 11), (2.5, 8, 2.9, 13.5, 14, 3, 11), (2.5, 15, 2.9, 13.5, 21, 3, 11),
                       (6, 4, 2.6, 10, 5, 2.9, 6), (6, 11, 2.6, 10, 12, 2.9, 6), (6, 18, 2.6, 10, 19, 2.9, 6)],
    'locker': [(2, 0, 3, 14, 30, 13, 10), (2.2, 2, 2.9, 7.8, 28, 3, 13), (8.2, 2, 2.9, 13.8, 28, 3, 13), (6.5, 14, 2.7, 7.5, 18, 2.9, 5),
               (8.5, 14, 2.7, 9.5, 18, 2.9, 5), (3, 24, 2.8, 7, 25, 2.9, 6), (9, 24, 2.8, 13, 25, 2.9, 6)],
    'safe': [(2, 0, 2, 14, 14, 14, 6), (3, 1, 1.9, 13, 13, 2, 11), (6, 5, 1.6, 10, 9, 1.9, 5), (11, 6, 1.6, 12, 8, 1.9, 9)],
    'vault': [(0.5, 0, 1, 15.5, 18, 15, 14), (1.5, 1, 0.9, 14.5, 17, 1, 12), (5, 6, 0.5, 11, 12, 0.9, 5), (7.5, 8.5, 0.2, 8.5, 9.5, 0.5, 9)],
    'airdrop': [(0, 0, 0, 16, 14, 16, 13), (-0.2, 0, -0.2, 16.2, 2, 16.2, 6), (-0.2, 12, -0.2, 16.2, 14, 16.2, 6),
                (4, 14, 4, 12, 15, 12, 7), (6, 6, -0.4, 10, 9, -0.2, 8)],
    'body_bag': [(1, 0, 2, 15, 4, 14, 1), (2, 4, 3, 14, 5, 13, 0), (3, 2, 1.9, 13, 2.5, 2, 6)],
}


def container_model(cid):
    boxes = CONTAINERS[cid]
    return {
        'textures': {'p': 'craftov:item/box_palette', 'particle': 'craftov:item/box_palette'},
        'elements': [element(b) for b in boxes],
        'display': {
            'fixed': {'rotation': [0, 0, 0], 'translation': [0, 0, 0], 'scale': [1, 1, 1]},
            'gui': {'rotation': [30, 225, 0], 'scale': [0.55, 0.55, 0.55]},
            'ground': {'scale': [0.4, 0.4, 0.4]},
        },
    }


def dump(obj):
    return json.dumps(obj, ensure_ascii=False, separators=(',', ':'))
