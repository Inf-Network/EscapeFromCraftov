"""Box-built 3D models (guns, containers) with flat palette textures, plus 2D icon renders."""
import json
import math
import os
from PIL import Image, ImageDraw

import gunvox
import meshprim
import reload
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
        col = rgb(c[:7], int(c[7:9], 16)) if len(c) == 9 else rgb(c)
        for dx in range(4):
            for dy in range(4):
                img.putpixel((x + dx, y + dy), col)
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


SMGS = {'sv9', 'v57'}


def voxel_gun(gid):
    """Painted, textured model built from a CC0 mesh: rotated primitives by meshprim.py when the gun has a
    tools/art/guns/<id>.yml, else voxels by gunvox.py; None falls back to the hand-built boxes."""
    if meshprim.has_spec(gid):
        return meshprim.build(gid)
    if not os.path.exists(os.path.join(gunvox.VOXGUNS, gid + '.json')):
        return None
    return gunvox.build(gid)


def gun_texture(gid):
    return 'craftov:item/gun/' + gid + '_skin' if voxel_gun(gid) else 'craftov:item/gun_palette'


def gun_skin_image(gid):
    data = voxel_gun(gid)
    return data['texture'] if data else None


def gun_parts(gid):
    """Model elements per part ('body', and 'mag' or 'pump' when the gun has a moving part)."""
    data = voxel_gun(gid)
    if data:
        return data['parts']
    shift = 0 if gid in PISTOLS else -4
    return {'body': [element(_to_x_axis(b, shift)) for b in GUNS[gid]()]}


def gun_elements(gid):
    """Model elements, barrel along +X, top +Y, centred on (8, 8, 8)."""
    return [e for part in gun_parts(gid).values() for e in part]


def _bounds(elements):
    return [(min(e['from'][i] for e in elements), max(e['to'][i] for e in elements)) for i in range(3)]


def _gun_class(gid):
    return 'pistol' if gid in PISTOLS else 'smg' if gid in SMGS else 'rifle'


def first_person(gid, elements, ads, sight_y=None, eye=None):
    """Display transforms tuned with tools/art/fpview.py (the client's own first-person maths).

    Guns are built barrel-along-+X, so a +90 degree yaw points the barrel away from the camera. ADS
    centres the gun with its top surface just under the crosshair and the stock near the cheek; hip
    fire sits lower right with a slight inward yaw. The left hand mirrors the right one: the client
    negates the left-hand yaw/roll and X offset, so the JSON uses (yaw-180) for a mirrored barrel.
    eye: (model x, blocks) puts that point of the gun that far in front of the eye when aimed (a 1x
    sight's window, see sight_eye) instead of the stock at the cheek.
    """
    (x0, _), (_, y1), _ = _bounds(elements)
    kind = _gun_class(gid)
    s = {'rifle': 0.6, 'smg': 0.66, 'pistol': 0.72}[kind]
    rear = {'rifle': -0.30, 'smg': -0.33, 'pistol': -0.52}[kind]
    if ads:
        # Iron sights: the gun's top edge sits just under the crosshair. Optics: the reticle sits on it.
        ty = 16 * (0.505 - (y1 - 8) / 16 * s) if sight_y is None else 16 * (0.52 - (sight_y - 8) / 16 * s)
        tz = 16 * (rear + 0.72 + (x0 - 8) / 16 * s)
        if eye is not None:
            tz = 16 * (-eye[1] + 0.72 + (eye[0] - 8) / 16 * s)
        right = {'rotation': [0, 90, 0], 'translation': [-8.96, round(ty, 2), round(tz, 2)], 'scale': [s] * 3}
        left = {'rotation': [0, -90, 0], 'translation': [-8.96, round(ty, 2), round(tz, 2)], 'scale': [s] * 3}
        return right, left
    hs = round(s * 0.85, 3)
    ty = 16 * (0.505 - (y1 - 8) / 16 * hs) - 3.4
    tz = 16 * (rear - 0.02 + 0.72 + (x0 - 8) / 16 * hs)
    t = [-1.2, round(ty, 2), round(tz, 2)]
    return ({'rotation': [0, 94, 3], 'translation': t, 'scale': [hs] * 3},
            {'rotation': [0, -86, -3], 'translation': t, 'scale': [hs] * 3})


def third_person(gid, arm_raise):
    """Barrel forward from the hand: [arm, 90, 90] cancels the holder's arm pitch (player ~18, zombie ~80)."""
    pistol = gid in PISTOLS
    t = [0, 1, 1] if pistol else [0, -3.5, 1]
    s = [0.75] * 3 if pistol else [0.6] * 3
    return ({'rotation': [-arm_raise, 90, 90], 'translation': t, 'scale': s},
            {'rotation': [-arm_raise, -90, -90], 'translation': t, 'scale': s})


# Seen by other players, the gun travels as a charged crossbow so their client draws the
# two-handed crossbow-hold pose (main arm pitch -90+5.7 degrees plus look pitch, yaw 17 degrees
# inward). This rotation undoes that arm transform exactly: barrel straight ahead, top up, the
# stock on the shoulder. Solved numerically against the 1.21.11 ItemInHandLayer chain.
AIMED_ROTATION = [5.73, 72.81, 0]


def third_person_aimed(gid):
    pistol = gid in PISTOLS
    t = [0, 1, 1] if pistol else [0, -3.5, 1]
    s = [0.75] * 3 if pistol else [0.6] * 3
    r = AIMED_ROTATION
    return ({'rotation': list(r), 'translation': t, 'scale': s},
            {'rotation': [r[0], -r[1], -r[2]], 'translation': t, 'scale': s})


def gun_aimed_model(gid, parent):
    """Variant shown to other players while the holder has the gun up (see AIMED_ROTATION)."""
    tp_r, tp_l = third_person_aimed(gid)
    return {'parent': parent, 'display': {'thirdperson_righthand': tp_r, 'thirdperson_lefthand': tp_l}}


def gun_model(gid):
    elements = gun_elements(gid)
    fp_r, fp_l = first_person(gid, elements, False)
    tp_r, tp_l = third_person(gid, 18)
    texture = gun_texture(gid)
    return {
        'textures': {'p': texture, 'particle': texture},
        'elements': elements,
        'display': {
            'firstperson_righthand': fp_r,
            'firstperson_lefthand': fp_l,
            'thirdperson_righthand': tp_r,
            'thirdperson_lefthand': tp_l,
            'ground': {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [0.4, 0.4, 0.4]},
            'fixed': {'rotation': [0, 180, 0], 'scale': [0.55, 0.55, 0.55]},
            'head': {'rotation': [0, -90, 0], 'scale': [0.55, 0.55, 0.55]},
        },
    }


def posed(display, view=(0, 0, 0), roll=0.0, shift=(0, 0, 0), left=False):
    """A first-person display transform moved into another pose.

    view: extra rotation about the camera axes in degrees (pitch up, yaw left, roll counter-clockwise),
    roll: cant about the barrel (model +X), shift: pixels (right, up, toward the camera). The result
    is solved back into the XYZ Euler angles the client applies (rotationXYZ, item pivot at its centre).
    """
    from scipy.spatial.transform import Rotation
    base = Rotation.from_euler('XYZ', display['rotation'], degrees=True)
    pitch, yaw, spin = view
    if left:
        # The client mirrors left-hand transforms (Y and Z angles negated), so mirror the change too.
        yaw, spin, roll = -yaw, -spin, -roll
    v = Rotation.from_euler('ZYX', [spin, yaw, pitch], degrees=True)
    r = v * base * Rotation.from_euler('X', roll, degrees=True)
    t = list(display.get('translation', [0, 0, 0]))
    dx, dy, dz = shift
    t = [round(t[0] + dx, 2), round(t[1] + dy, 2), round(t[2] + dz, 2)]
    out = {'rotation': [round(float(a), 2) for a in r.as_euler('XYZ', degrees=True)], 'translation': t}
    if 'scale' in display:
        out['scale'] = display['scale']
    return out


# First-person poses picked on the client from held keys (no server round trip). Values are tuned
# with fpview.py: sprint carries the gun low and canted, strafing leans it.
HIP_POSES = {
    'sprint': dict(view=(-30, 40, 20), roll=40, shift=(-0.5, -1.5, 0.5)),
    'left': dict(view=(0, 0, 5), roll=0, shift=(-0.4, 0, 0)),
    'right': dict(view=(0, 0, -5), roll=0, shift=(0.4, 0, 0)),
}
ADS_POSES = {
    'left': dict(view=(0, 0, 2), roll=0, shift=(0, 0, 0)),
    'right': dict(view=(0, 0, -2), roll=0, shift=(0, 0, 0)),
}

# The kick of one shot at full strength: muzzle up (degrees) and the gun pushed back toward the camera.
# The server starts the gun's cooldown on every shot it really fires (WeaponService.shoot), and the
# item model steps through these poses as the client counts that cooldown down: the gun snaps back
# with the flash, then settles over the rest of the shot's kick time.
HIP_KICK = dict(view=(3.4, 0, 0), roll=0, shift=(0, 0.3, 1.6))
ADS_KICK = dict(view=(1.2, 0, 0), roll=0, shift=(0, 0.12, 0.8))
# Kick strengths there are poses for (fractions of the full kick). The curve is sampled onto these.
KICK_LEVELS = (1.0, 0.6, 0.3, 0.1)
# Longest kick, in ticks: a slow gun (bolt, pump, heavy pistol) settles in 0.3 s, not over its whole
# cycle. WeaponService uses the same rule (GunSpec.kickTicks).
KICK_MAX_TICKS = 6
# The flash shows for the first tick of a shot, about 50 ms.
FLASH_TICKS = 1.0


def kick_pose(kind, level, strength=1.0):
    """The kick pose at a fraction of full strength, scaled by how hard the gun kicks."""
    base = HIP_KICK if kind == 'hip' else ADS_KICK
    k = level * strength
    return dict(view=tuple(round(v * k, 3) for v in base['view']), roll=0, shift=tuple(round(v * k, 3) for v in base['shift']))


def kick_strength(recoil):
    """How hard this gun's kick looks, from its camera recoil per shot (items.yml recoil, degrees)."""
    return max(0.7, min(1.5, 0.55 + 0.25 * recoil))


def kick_ticks(rpm):
    """Ticks one shot's kick lasts: the time between shots, at least 2 and at most KICK_MAX_TICKS."""
    between = max(1, int(math.floor(1200.0 / rpm + 0.5)))
    return max(2, min(KICK_MAX_TICKS, between))


def kick_curve(t, ticks):
    """Kick strength t ticks after a shot: full for the first tick, then easing back to nothing."""
    if t < 1.0:
        return 1.0
    return max(0.0, (ticks - t) / max(1e-6, ticks - 1.0)) ** 1.6


def kick_frames(rpm, step=0.25):
    """(cooldown threshold, kick level index, flash) for each part of a shot, the first part first.

    The client's cooldown runs from 1 down to 0 over kick_ticks; a range dispatch picks the entry with
    the highest threshold at or below it. A part showing no kick is left out: below the last threshold
    the item model falls back to the gun at rest.
    """
    ticks = kick_ticks(rpm)
    out = []
    t = 0.0
    while t < ticks - 1e-9:
        mid = t + step / 2
        k = kick_curve(mid, ticks)
        threshold = max(1e-4, round(1 - (t + step) / ticks, 5))
        if k < 0.05:
            break
        level = min(range(len(KICK_LEVELS)), key=lambda i: abs(KICK_LEVELS[i] - k))
        flash = mid < FLASH_TICKS
        if out and out[-1][1] == level and out[-1][2] == flash:
            out[-1] = (threshold, level, flash)
        else:
            out.append((threshold, level, flash))
        t += step
    return out


def muzzle_point(gid):
    """Model-space centre of the barrel tip (barrel along +X)."""
    el = gun_elements(gid)
    mx = max(e['to'][0] for e in el)
    tip = [e for e in el if e['to'][0] >= mx - 0.8]
    my = sum((e['from'][1] + e['to'][1]) / 2 for e in tip) / len(tip)
    mz = sum((e['from'][2] + e['to'][2]) / 2 for e in tip) / len(tip)
    return mx, my, mz


HIDDEN = {'rotation': [0, 0, 0], 'translation': [0, -80, 0], 'scale': [0, 0, 0]}


def flash_model(gid, display_r, display_l):
    """Muzzle flash posed with the gun: two crossed jets along the barrel and a star facing the camera.

    Shown only for the first tick of a shot the server really fired (see kick_frames).
    """
    mx, my, mz = muzzle_point(gid)
    length = round(min(8.5, 31.7 - mx), 2)
    h = 3.0
    face = lambda tex: {'uv': [0, 0, 16, 16], 'texture': tex}
    x0, x1 = round(mx + 0.2, 2), round(mx + 0.2 + length, 2)
    elements = [
        {'from': [x0, round(my - h, 2), mz], 'to': [x1, round(my + h, 2), mz], 'shade': False,
         'faces': {'north': face('#side'), 'south': face('#side')}},
        {'from': [x0, my, round(mz - h, 2)], 'to': [x1, my, round(mz + h, 2)], 'shade': False,
         'faces': {'up': face('#side'), 'down': face('#side')}},
        {'from': [x0, round(my - h * 1.5, 2), round(mz - h * 1.5, 2)], 'to': [x0, round(my + h * 1.5, 2), round(mz + h * 1.5, 2)],
         'shade': False, 'faces': {'east': face('#front'), 'west': face('#front')}},
    ]
    display = {k: HIDDEN for k in ('thirdperson_righthand', 'thirdperson_lefthand', 'ground', 'fixed', 'head', 'gui')}
    display['firstperson_righthand'] = display_r
    display['firstperson_lefthand'] = display_l
    return {'textures': {'side': 'craftov:item/fx/muzzle_side', 'front': 'craftov:item/fx/muzzle_front',
                         'particle': 'craftov:item/fx/muzzle_front'},
            'elements': elements, 'display': display}


def attachment_boxes(gid, att):
    """Boxes of an attachment in the gun's model space, placed from the gun's own geometry."""
    el = gun_elements(gid)
    mx, my, mz = muzzle_point(gid)
    pistol = gid in PISTOLS
    if att == 'att_suppressor':
        r = 0.72 if pistol else 0.95
        length = min(4.4 if pistol else 6.4, 31.7 - mx)
        x0, x1 = mx - 0.3, mx - 0.3 + length
        return [(x0, my - r, mz - r * 0.62, x1, my + r, mz + r * 0.62, DG),
                (x0, my - r * 0.62, mz - r, x1, my + r * 0.62, mz + r, DG),
                (x1 - 0.5, my - r * 0.66, mz - r * 0.66, x1 + 0.1, my + r * 0.66, mz + r * 0.66, K),
                (x0 + length * 0.45, my - r - 0.05, mz - r * 0.3, x0 + length * 0.55, my + r + 0.05, mz + r * 0.3, K)]
    if att == 'att_compensator':
        r = 0.72
        length = min(2.4, 31.7 - mx)
        x0 = mx - 0.2
        return [(x0, my - r, mz - r, x0 + length, my + r, mz + r, MG),
                (x0 + 0.5, my + r - 0.25, mz - r * 0.5, x0 + 0.9, my + r + 0.02, mz + r * 0.5, K),
                (x0 + 1.4, my + r - 0.25, mz - r * 0.5, x0 + 1.8, my + r + 0.02, mz + r * 0.5, K)]
    if att == 'att_vgrip':
        gx = mx - (8.0 if gid == 'r12' else 6.5)
        under = [e['from'][1] for e in el if e['from'][0] <= gx <= e['to'][0]]
        bottom = min(under) if under else my - 1.5
        return [(gx - 1.0, bottom - 0.35, mz - 0.55, gx + 1.0, bottom + 0.05, mz + 0.55, K),
                (gx - 0.55, bottom - 3.4, mz - 0.55, gx + 0.55, bottom - 0.35, mz + 0.55, DG),
                (gx - 0.65, bottom - 3.6, mz - 0.65, gx + 0.65, bottom - 3.3, mz + 0.65, K)]
    if att == 'att_taclight':
        lx = mx - (2.6 if pistol else 4.5)
        near = [e['from'][2] for e in el if e['from'][0] <= lx <= e['to'][0]]
        side = min(near) if near else mz - 0.6
        ly = my - (0.7 if pistol else 0.1)
        return [(lx - 1.8, ly - 0.55, side - 1.1, lx + 1.5, ly + 0.55, side - 0.05, DG),
                (lx + 1.5, ly - 0.7, side - 1.25, lx + 2.1, ly + 0.7, side + 0.05, K),
                (lx + 2.1, ly - 0.5, side - 1.05, lx + 2.15, ly + 0.5, side - 0.15, WH),
                (lx - 1.0, ly - 0.9, side - 0.8, lx - 0.2, ly - 0.55, side - 0.3, K)]
    if att == 'att_extmag':
        mag = gun_parts(gid).get('mag') or []
        if not mag:
            return []
        x0 = min(e['from'][0] for e in mag)
        x1 = max(e['to'][0] for e in mag)
        y0 = min(e['from'][1] for e in mag)
        y1 = max(e['to'][1] for e in mag)
        z0 = min(e['from'][2] for e in mag)
        z1 = max(e['to'][2] for e in mag)
        ext = max(1.2, (y1 - y0) * 0.5)
        inset = (x1 - x0) * 0.12
        return [(x0 + inset, y0 - ext, z0, x1 - inset, y0 + 0.05, z1, DG),
                (x0 + inset - 0.1, y0 - ext - 0.35, z0 - 0.1, x1 - inset + 0.1, y0 - ext, z1 + 0.1, K)]
    return []


def attachment_model(parent, gid, att):
    """An attachment drawn in a gun model's pose: the parent supplies the display transforms."""
    boxes = [tuple(round(v, 3) if isinstance(v, float) else v for v in b) for b in attachment_boxes(gid, att)]
    return {'parent': parent, 'textures': {'a': 'craftov:item/gun_palette'}, 'elements': [element(b, '#a') for b in boxes]}


def pose_model(parent, base_display, pose):
    """Display-only variant of a gun model in one of the poses above."""
    return {'parent': parent, 'display': {
        'firstperson_righthand': posed(base_display['firstperson_righthand'], **pose),
        'firstperson_lefthand': posed(base_display['firstperson_lefthand'], left=True, **pose)}}


def iron_sights(gid):
    """(sight line height, (rear sight x, blocks from the eye)) of a gun with modelled iron sights, else
    (None, None): aiming then puts the gun's top edge under the crosshair."""
    if meshprim.has_spec(gid) and meshprim.sight_y(gid) is not None:
        eye = meshprim.spec(gid).get('eye')
        return meshprim.sight_y(gid), tuple(eye) if eye else None
    return None, None


def gun_ads_model(gid, scoped=False):
    """Aim-down-sights pose, selected client-side while the use key (right mouse) is held.

    Guns with a built-in scope (items.yml scope) look through the scope overlay instead, so the gun itself is hidden.
    """
    if scoped:
        hidden = {'rotation': [0, 0, 0], 'translation': [0, -80, 0], 'scale': [0, 0, 0]}
        return {'parent': 'craftov:item/gun/' + gid, 'display': {'firstperson_righthand': hidden, 'firstperson_lefthand': hidden}}
    sight, eye = iron_sights(gid)
    fp_r, fp_l = first_person(gid, gun_elements(gid), True, sight_y=sight, eye=eye)
    return {'parent': 'craftov:item/gun/' + gid, 'display': {'firstperson_righthand': fp_r, 'firstperson_lefthand': fp_l}}


# The sights coming up (WeaponService.startAds): the server runs the gun's cooldown for its ads time with
# the aiming mark on, and the item model steps through these frames while the client counts it down,
# eased in and out like the zoom the server sends alongside.
ADS_RISE_FRAMES = 6


def ads_ease(t):
    return t * t * (3 - 2 * t)


# The last ADS_RISE_TAIL of that cooldown holds the final frame (WeaponService.ADS_FRAMES_TAIL): the
# server's aimed mark, which ends the frames, may come a tick late without the gun dropping back.
ADS_RISE_TAIL = 0.25


def ads_rise_frames(n=ADS_RISE_FRAMES):
    """(cooldown threshold, progress 0..1) per frame, the first first. The cooldown runs from 1 down
    to 0: the frames step through its first 1 - ADS_RISE_TAIL, the last one holds to the end. Once it
    has run out (and before it comes) the model is the first frame, the gun at the hip."""
    span = 1 - ADS_RISE_TAIL
    return [(max(1e-4, round(ADS_RISE_TAIL + span * (1 - (k + 1) / n), 5)) if k < n - 1 else 1e-4, round(ads_ease((k + 0.5) / n), 4))
            for k in range(n)]


def lerp_display(a, b, t):
    """A display transform t of the way from a to b (angles, offset and scale each straight across)."""
    out = {}
    for key, default in (('rotation', [0, 0, 0]), ('translation', [0, 0, 0]), ('scale', [1, 1, 1])):
        va, vb = a.get(key, default), b.get(key, default)
        out[key] = [round(x + (y - x) * t, 3) for x, y in zip(va, vb)]
    return out


def gun_rise_display(gid):
    """Where a gun with a built-in scope rises to before its mask takes over: the top of the scope
    under the crosshair, as iron sights would be."""
    r, l = first_person(gid, gun_elements(gid), True)
    return {'firstperson_righthand': r, 'firstperson_lefthand': l}


def optic_rise_display(gid, oid):
    """Where a gun with this optic rises to: a 1x sight's aimed pose, a scope's eyepiece on the crosshair."""
    gun = gun_elements(gid)
    placed, sight_y = placed_optic(gid, oid)
    if sight_y is not None:
        return gun_with_optic(gid, oid, True)['display']
    sight_y = (min(e['from'][1] for e in placed) + max(e['to'][1] for e in placed)) / 2
    r, l = first_person(gid, gun, True, sight_y=sight_y)
    return {'firstperson_righthand': r, 'firstperson_lefthand': l}


def gun_part_model(gid, part):
    """One moving part of the gun (body, mag or pump) with the gun's textures and display."""
    return {'parent': 'craftov:item/gun/' + gid, 'elements': gun_parts(gid)[part]}


def shell_model():
    boxes = [(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2], RD if mat == 'shell' else BR) for lo, hi, mat in reload.SHELL]
    return {'textures': {'p': 'craftov:item/gun_palette', 'particle': 'craftov:item/gun_palette'},
            'elements': [element(b) for b in boxes]}


def _frames(gid):
    base = gun_model(gid)['display']['firstperson_righthand']
    return reload.frames(gid, base, lift=4.0 if gid in PISTOLS else 0.0)


def reload_frames(gid):
    """[(frame index, part, model parent, display)] for the reload animation of one gun (optics separate)."""
    out = []
    for k, frame in enumerate(_frames(gid)):
        for part, right in frame.items():
            if part == 'optic':
                continue
            parent = 'craftov:item/gun_part/shell' if part == 'shell' else 'craftov:item/gun_part/%s_%s' % (gid, part)
            out.append((k, part, parent, right))
    return out


def reload_frames_optic(gid):
    """Right-hand display of a mounted optic per reload frame (it moves with the body)."""
    return [frame['optic'] for frame in _frames(gid)]


def frame_model(parent, right):
    return {'parent': parent, 'display': {'firstperson_righthand': right, 'firstperson_lefthand': reload.mirrored(right)}}


def gun_ai_model(gid):
    """Variant for zombie soldiers, whose arms are raised about 80 degrees."""
    tp_r, tp_l = third_person(gid, 80)
    return {'parent': 'craftov:item/gun/' + gid, 'display': {'thirdperson_righthand': tp_r, 'thirdperson_lefthand': tp_l}}


def gun_icon(gid):
    """Side-view 2D icon rendered from the box geometry (z horizontal, y vertical)."""
    if meshprim.has_spec(gid):
        import gunpaint
        data = meshprim.build(gid)
        return side_icon(gunpaint.side_image([e for part in data['parts'].values() for e in part], data['texture'], 4))
    if voxel_gun(gid):
        return side_icon(gunvox.side_image(gid, 4))
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


def voxel_icon(data, size=32, margin=1, tilt=30):
    """Inventory icon of a converted gun: side view (x right, y up) tilted so long guns fill the slot."""
    els = data['elements']
    x0 = min(e['from'][0] for e in els); x1 = max(e['to'][0] for e in els)
    y0 = min(e['from'][1] for e in els); y1 = max(e['to'][1] for e in els)
    big = 16
    px_per_unit = 12
    w = int((x1 - x0) * px_per_unit) + 4
    h = int((y1 - y0) * px_per_unit) + 4
    img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for e in sorted(els, key=lambda e: e['to'][2]):
        col = shade(rgb(data['palette'][e['c']]), 1.35)
        a = (2 + (e['from'][0] - x0) * px_per_unit, 2 + (y1 - e['to'][1]) * px_per_unit)
        b = (2 + (e['to'][0] - x0) * px_per_unit - 1, 2 + (y1 - e['from'][1]) * px_per_unit - 1)
        if b[0] >= a[0] and b[1] >= a[1]:
            d.rectangle([a, b], fill=col)
    angle = tilt if (x1 - x0) / max(1e-6, y1 - y0) > 1.8 else 0
    img = img.rotate(angle, resample=Image.BICUBIC, expand=True)
    bbox = img.getbbox()
    img = img.crop(bbox)
    fit = (size - 2 * margin) / max(img.size)
    small = img.resize((max(1, round(img.size[0] * fit)), max(1, round(img.size[1] * fit))), Image.LANCZOS)
    px = small.load()
    for yy in range(small.size[1]):
        for xx in range(small.size[0]):
            r, g, b_, a = px[xx, yy]
            px[xx, yy] = (r, g, b_, 255) if a > 100 else (0, 0, 0, 0)
    c = Canvas()
    c.img.alpha_composite(small, ((size - small.size[0]) // 2, (size - small.size[1]) // 2))
    return c.outline().img


def side_icon(img, size=32, margin=1, tilt=30):
    """Inventory icon from a painted side view, tilted so long guns fill the slot."""
    w, h = img.size
    angle = tilt if w / max(1, h) > 1.8 else 0
    img = img.rotate(angle, resample=Image.BICUBIC, expand=True)
    img = img.crop(img.getbbox())
    fit = (size - 2 * margin) / max(img.size)
    small = img.resize((max(1, round(img.size[0] * fit)), max(1, round(img.size[1] * fit))), Image.LANCZOS)
    px = small.load()
    for yy in range(small.size[1]):
        for xx in range(small.size[0]):
            r, g, b_, a = px[xx, yy]
            px[xx, yy] = (min(255, int(r * 1.15) + 8), min(255, int(g * 1.15) + 8), min(255, int(b_ * 1.15) + 8), 255) if a > 100 else (0, 0, 0, 0)
    c = Canvas()
    c.img.alpha_composite(small, ((size - small.size[0]) // 2, (size - small.size[1]) // 2))
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


# ---------------------------------------------------------------- optics (attachments)
# Procedural sights are authored with the base at y=0, centred on x=0 and z=0, in model units.
# Palette for procedural optics: black, dark metal, tinted glass (translucent), emissive red.
OPTIC_PALETTE = ['#1E2023', '#3A3E44', '#5A88B033', '#FF3B30']


def _optic_box(x0, y0, z0, x1, y1, z1, c, glow=False):
    e = {'from': [x0, y0, z0], 'to': [x1, y1, z1], 'c': c}
    if glow:
        e['glow'] = True
    return e


# The window of a 1x sight seen from the eye: the far end of its housing (x), the height of the sight
# line (y) and the clear opening around it (half width and half height). When aimed, the eye is put so
# the window spans SIGHT_WINDOW of the screen's width (the issue #5 target: 18-25%), and the reticle,
# a glowing picture just behind the glass, is RETICLE_PX pixels across on a 1080-pixel-high screen.
SIGHT_WINDOW = 0.21
WINDOW_ASPECT = 1000 / 584      # fpview's window, the owner's 2000x1168
RETICLE = {'optic_reddot': dict(x=0.5, y=1.3, half_w=0.75, half_h=0.75, kind='dot', px=4),
           'optic_holo': dict(x=1.3, y=1.55, half_w=0.95, half_h=0.65, kind='holo', px=64)}
HAND_TAN = math.tan(math.radians(35))


def _reddot():
    """A compact sight: a short square hood over a low mount, the dot just behind the glass."""
    w, top, bottom, t = 0.75, 2.05, 0.55, 0.1
    return [_optic_box(-1.0, 0, -0.55, 1.0, 0.45, 0.55, 1),
            _optic_box(0.0, 0.45, -w - t, 0.5, bottom, w + t, 0),
            _optic_box(0.0, bottom, -w - t, 0.5, top + t, -w, 0), _optic_box(0.0, bottom, w, 0.5, top + t, w + t, 0),
            _optic_box(0.0, top, -w, 0.5, top + t, w, 0),
            _optic_box(0.1, 1.0, w + t, 0.4, 1.4, w + t + 0.15, 1),
            _optic_box(0.46, bottom, -w, 0.5, top, w, 2)]


def _holo():
    """A holographic sight: a flat battery box behind a short, wide hood; a ring-and-dot reticle in the glass."""
    w, top, bottom, t = 0.95, 2.2, 0.9, 0.12
    return [_optic_box(-1.6, 0, -0.8, 1.6, 0.6, 0.8, 1), _optic_box(-1.6, 0.6, -0.7, 0.5, 0.82, 0.7, 0),
            _optic_box(0.6, 0.6, -w - t, 1.3, bottom, w + t, 0),
            _optic_box(0.6, bottom, -w - t, 1.3, top + t, -w, 0), _optic_box(0.6, bottom, w, 1.3, top + t, w + t, 0),
            _optic_box(0.6, top, -w, 1.3, top + t, w, 0),
            _optic_box(1.26, bottom, -w, 1.3, top, w, 2)]


def reticle_image(kind, size=64):
    """The reticle picture of a 1x sight, red on clear: a round dot, or a ring around a dot."""
    from PIL import ImageDraw as _Draw
    big = size * 4
    img = Image.new('RGBA', (big, big), (0, 0, 0, 0))
    d = _Draw.Draw(img)
    red = (255, 52, 40, 255)
    if kind == 'dot':
        d.ellipse([0, 0, big - 1, big - 1], fill=red)
    else:
        ring = big * 3 // 64
        d.ellipse([0, 0, big - 1, big - 1], outline=red, width=ring)
        c, r = big // 2, big * 2 // 64
        d.ellipse([c - r, c - r, c + r, c + r], fill=red)
        for k in range(4):  # short ticks inside the ring, top, bottom and both sides
            if k % 2 == 0:
                x = c
                y0, y1 = (ring, ring + big // 12) if k == 0 else (big - ring - big // 12, big - ring)
                d.rectangle([x - ring // 2, y0, x + ring // 2, y1], fill=red)
    small = img.resize((size, size), Image.LANCZOS)
    px = small.load()
    for yy in range(size):
        for xx in range(size):
            r, g, b, a = px[xx, yy]
            px[xx, yy] = (255, 52, 40, 255) if a >= 110 else (0, 0, 0, 0)
    return small


def reticle_texture(oid):
    return 'craftov:item/optic/reticle_' + RETICLE[oid]['kind']


def _prism3x():
    return [_optic_box(-1.6, 0, -0.6, 1.6, 0.5, 0.6, 1), _optic_box(-1.8, 0.5, -0.75, 1.8, 2.0, 0.75, 0),
            _optic_box(1.8, 0.7, -0.85, 2.6, 2.05, 0.85, 1), _optic_box(2.55, 0.8, -0.7, 2.62, 1.95, 0.7, 2),
            _optic_box(-2.6, 0.8, -0.65, -1.8, 1.9, 0.65, 0), _optic_box(-1.0, 2.0, -0.4, 1.0, 2.3, 0.4, 1),
            _optic_box(0.2, 2.3, -0.15, 0.5, 2.45, 0.15, 3, True)]


def _shift(elements, dx, dy, dz):
    out = []
    for e in elements:
        moved = dict(e)
        moved['from'] = [round(e['from'][0] + dx, 4), round(e['from'][1] + dy, 4), round(e['from'][2] + dz, 4)]
        moved['to'] = [round(e['to'][0] + dx, 4), round(e['to'][1] + dy, 4), round(e['to'][2] + dz, 4)]
        out.append(moved)
    return out


# id -> (textured elements on '#o' with the base at y=0 centred on x=z=0, texture image,
#        sight height above the base for 1x sights or None for overlay scopes)
def optic_data(oid):
    procedural = {'optic_reddot': (_reddot, RETICLE['optic_reddot']['y']), 'optic_holo': (_holo, RETICLE['optic_holo']['y']),
                  'optic_3x': (_prism3x, None)}
    if oid in procedural:
        build, sight = procedural[oid]
        return _textured(build(), '#o'), palette_texture(OPTIC_PALETTE), sight
    if not os.path.exists(os.path.join(gunvox.VOXGUNS, oid + '.json')):
        return None
    data = gunvox.build(oid, '#o')
    els = data['parts']['body']
    x0 = min(e['from'][0] for e in els); x1 = max(e['to'][0] for e in els)
    y0 = min(e['from'][1] for e in els)
    cz = (min(e['from'][2] for e in els) + max(e['to'][2] for e in els)) / 2
    return _shift(els, -(x0 + x1) / 2, -y0, -cz), data['texture'], None


OPTICS = ['optic_reddot', 'optic_holo', 'optic_3x', 'optic_4x', 'optic_8x']
PISTOL_OPTICS = ['optic_reddot']
BUILTIN_SCOPES = {'dm14', 'awr'}


def optics_for(gid):
    if gid in BUILTIN_SCOPES:
        return []
    return PISTOL_OPTICS if gid in PISTOLS else OPTICS


def rail_mount(elements):
    """Top of the receiver: the most common top height over the middle of the gun, and the middle of that run."""
    x0 = min(e['from'][0] for e in elements); x1 = max(e['to'][0] for e in elements)
    length = x1 - x0
    tops = {}
    step = 0.25
    x = x0 + length * 0.25
    while x <= x0 + length * 0.62:
        top = None
        for e in elements:
            if e['from'][0] <= x < e['to'][0] and e['from'][2] <= 8.0 <= e['to'][2]:
                top = e['to'][1] if top is None else max(top, e['to'][1])
        if top is not None:
            tops[round(x, 2)] = round(top, 2)
        x += step
    if not tops:
        return (x0 + x1) / 2, 12.0
    counts = {}
    for t in tops.values():
        counts[t] = counts.get(t, 0) + 1
    best = max(counts, key=lambda t: (counts[t], -t))
    xs = sorted(k for k, t in tops.items() if t == best)
    runs, run = [], [xs[0]]
    for a, b in zip(xs, xs[1:]):
        if b - a <= step + 1e-6:
            run.append(b)
        else:
            runs.append(run)
            run = [b]
    runs.append(run)
    longest = max(runs, key=len)
    return (longest[0] + longest[-1]) / 2, best


def _textured(elements, tex):
    out = []
    for e in elements:
        el = {'from': [round(v, 3) for v in e['from']], 'to': [round(v, 3) for v in e['to']],
              'faces': {f: {'uv': uv(e['c']), 'texture': tex} for f in ('north', 'south', 'east', 'west', 'up', 'down')}}
        if e.get('glow'):
            el['light_emission'] = 15
        out.append(el)
    return out


def optic_texture(oid):
    return 'craftov:item/optic/' + oid + '_skin'


def optic_skin_image(oid):
    return optic_data(oid)[1]


def placed_optic(gid, oid):
    """The optic's elements sitting on the gun's top rail, plus its sight line height (None for scopes)."""
    optic, _, sight = optic_data(oid)
    mx, my = gun_rail(gid)
    return _shift(optic, mx, my, 8), (None if sight is None else my + sight)


def gun_rail(gid):
    """Where an optic's base sits: the gun spec's rail, else the flattest stretch of the top."""
    if meshprim.has_spec(gid) and meshprim.rail(gid):
        return meshprim.rail(gid)
    return rail_mount(gun_elements(gid))


def _gun_scale(gid):
    return {'rifle': 0.6, 'smg': 0.66, 'pistol': 0.72}[_gun_class(gid)]


def sight_window(gid, oid):
    """A 1x sight's window on this gun, in the gun's model units: the far end of the housing (x), the
    sight line (y) and the opening (y0..y1 high, z0..z1 wide)."""
    r = RETICLE[oid]
    mx, my = gun_rail(gid)
    return {'x': mx + r['x'], 'y': my + r['y'], 'y0': my + r['y'] - r['half_h'], 'y1': my + r['y'] + r['half_h'],
            'z0': 8 - r['half_w'], 'z1': 8 + r['half_w']}


def sight_eye(gid, oid):
    """(model x, blocks): how far in front of the eye the window's far end goes when aimed, so the window
    spans SIGHT_WINDOW of the screen's width (the hand is drawn at a fixed 70 degree field of view)."""
    win = sight_window(gid, oid)
    width = (win['z1'] - win['z0']) * _gun_scale(gid) / 16
    return win['x'], width / (SIGHT_WINDOW * 2 * HAND_TAN * WINDOW_ASPECT)


def reticle_element(gid, oid):
    """The reticle: a glowing picture just in front of the glass, centred on the sight line, sized so it
    is RETICLE px pixels across on a 1080-pixel-high screen at the distance the sight is aimed at."""
    r = RETICLE[oid]
    win = sight_window(gid, oid)
    x_at, front = sight_eye(gid, oid)
    x = win['x'] - 0.06
    dist = front - (x_at - x) * _gun_scale(gid) / 16
    side = r['px'] / 1080 * 2 * HAND_TAN * dist * 16 / _gun_scale(gid)
    h = side / 2
    face = {'uv': [0, 0, 16, 16], 'texture': '#r'}
    return {'from': [round(x, 4), round(win['y'] - h, 5), round(8 - h, 5)], 'to': [round(x, 4), round(win['y'] + h, 5), round(8 + h, 5)],
            'shade': False, 'light_emission': 15, 'faces': {'west': face, 'east': face}}


def gun_with_optic(gid, oid, ads):
    """Gun plus mounted optic. Hip pose uses the bare gun's bounds so the gun stays put; ADS lines up the
    sight, a 1x sight close to the eye (see sight_eye) with its reticle just behind the glass."""
    gun = gun_elements(gid)
    placed, sight_y = placed_optic(gid, oid)
    texture = gun_texture(gid)
    model = {'textures': {'p': texture, 'o': optic_texture(oid), 'particle': texture}, 'elements': gun + placed}
    if oid in RETICLE:
        model['textures']['r'] = reticle_texture(oid)
        model['elements'] = gun + placed + [reticle_element(gid, oid)]
    display = dict(gun_model(gid)['display'])
    if ads:
        if sight_y is None:
            hidden = {'rotation': [0, 0, 0], 'translation': [0, -80, 0], 'scale': [0, 0, 0]}
            display['firstperson_righthand'] = hidden
            display['firstperson_lefthand'] = hidden
        else:
            r, l = first_person(gid, gun, True, sight_y=sight_y, eye=sight_eye(gid, oid) if oid in RETICLE else None)
            display['firstperson_righthand'] = r
            display['firstperson_lefthand'] = l
    model['display'] = display
    return model


def optic_on_gun_model(gid, oid):
    """Just the mounted optic, posed like the gun; drawn alongside the moving gun parts while reloading."""
    placed, _ = placed_optic(gid, oid)
    return {'textures': {'o': optic_texture(oid), 'particle': optic_texture(oid)}, 'elements': placed,
            'display': gun_model(gid)['display']}


def optic_item_model(oid):
    """The optic as a loose item (held or on the ground)."""
    optic, _, _ = optic_data(oid)
    return {'textures': {'o': optic_texture(oid), 'particle': optic_texture(oid)}, 'elements': _shift(optic, 8, 6, 8),
            'display': {'firstperson_righthand': {'rotation': [0, 90, 0], 'translation': [0, 2, 0], 'scale': [1.4, 1.4, 1.4]},
                        'thirdperson_righthand': {'rotation': [0, 90, 90], 'translation': [0, 2, 1], 'scale': [1.2, 1.2, 1.2]},
                        'ground': {'translation': [0, 2, 0], 'scale': [1, 1, 1]},
                        'fixed': {'rotation': [0, 180, 0], 'scale': [1.5, 1.5, 1.5]}}}


# The dark ring around a magnified scope's view: a square picture with a round hole, drawn with the
# hand. The hand is projected at a fixed 70 degree field of view with the window's own aspect, so the
# hole stays round at any window shape (the camera overlay, stretched over the window, would not) and
# the same size at any zoom. The quad is MASK_DIST blocks in front of the eye and covers windows up to
# 2.5:1; the hole's radius is MASK_HOLE of the screen height, as the reticles assume (ui.SCOPE_HOLE).
MASK_DIST = 0.45
MASK_HALF = 14.0          # model units either side of the centre: the quad is 28 units across
MASK_SIZE = 1024
MASK_BASE = 0.45
MASK_HOLE = {'optic_3x': 0.47, 'optic_4x': 0.45, 'optic_8x': 0.43}
MASK_TEXTURE = 'craftov:item/scope/mask'


def mask_hole_fraction():
    """The hole's radius as a share of the quad's half side, at the base size (MASK_BASE)."""
    quad = MASK_HALF / 16 / MASK_DIST
    hole = 2 * MASK_BASE * math.tan(math.radians(35))
    return hole / quad


def mask_image(size=MASK_SIZE):
    """Black, with a clear round hole and a soft dark edge just inside it, like looking down a tube."""
    import numpy as np
    yy, xx = np.mgrid[0:size, 0:size] + 0.5
    r = np.hypot(xx - size / 2, yy - size / 2) / (size / 2)
    hole = mask_hole_fraction()
    edge = 0.035 * hole
    a = np.clip((r - (hole - edge)) / edge, 0, 1) ** 1.6
    alpha = np.where(r >= hole, 255, (a * 235).astype(np.uint8)).astype(np.uint8)
    rgba = np.zeros((size, size, 4), np.uint8)
    rgba[..., 3] = alpha
    return Image.fromarray(rgba, 'RGBA')


def scope_mask_model(oid):
    """The mask posed straight ahead in first person only (both hands: it sits on the view axis)."""
    scale = round(MASK_HOLE[oid] / MASK_BASE, 4)
    lo, hi = 8 - MASK_HALF, 8 + MASK_HALF
    face = {'uv': [0, 0, 16, 16], 'texture': '#m'}
    fp = {'rotation': [0, 0, 0], 'translation': [-8.96, 8.32, round(16 * (0.72 - MASK_DIST), 3)], 'scale': [scale] * 3}
    display = {k: HIDDEN for k in ('thirdperson_righthand', 'thirdperson_lefthand', 'ground', 'fixed', 'head', 'gui')}
    display['firstperson_righthand'] = fp
    display['firstperson_lefthand'] = fp
    return {'textures': {'m': MASK_TEXTURE, 'particle': MASK_TEXTURE},
            'elements': [{'from': [lo, lo, 8], 'to': [hi, hi, 8], 'shade': False, 'faces': {'south': face, 'north': face}}],
            'display': display}


def optic_icon(oid):
    if os.path.exists(os.path.join(gunvox.VOXGUNS, oid + '.json')):
        return side_icon(gunvox.side_image(oid, 4), tilt=0)
    procedural = {'optic_reddot': _reddot, 'optic_holo': _holo, 'optic_3x': _prism3x}[oid]()
    return voxel_icon({'elements': procedural, 'palette': [p[:7] for p in OPTIC_PALETTE]}, tilt=0)
