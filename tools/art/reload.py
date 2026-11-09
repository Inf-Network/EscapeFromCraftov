"""First-person reload animation baked into item model frames.

While reloading, the server puts the gun's cooldown group on cooldown for exactly the reload time.
The client then counts the cooldown down by itself every frame, and the item definition picks a
frame with a `minecraft:cooldown` range dispatch. Each frame is a composite of the gun body, its
magazine (or a shotgun's pump and a shell) and any mounted optic, each a tiny model that only
overrides its first-person display transform. Moving the magazine is therefore just a different
translation along the magazine's own axis, so the whole animation costs no packets at all.

Poses are keyframed in view space (roll, pitch and yaw of the whole gun plus an offset), composed
with the gun's normal hip pose and converted back into the X-Y-Z Euler angles item displays use.
"""
import math

import gunvox

FRAMES = 24
HIDE_BEYOND = 20.0  # magazine travel (model units) past which it is out of view and hidden

# (progress, roll, pitch, yaw, dx, dy, dz, magazine travel, ease into this key)
# roll: about the barrel, negative turns the magazine well towards the player; pitch: muzzle up is positive; offsets in display units (1/16 block).
MAG_KEYS = [
    (0.00, 0, 0, 0, 0, 0, 0, 0, 'smooth'),
    (0.14, -30, 9, -8, -2.8, 1.4, 0.4, 0, 'smooth'),
    (0.20, -32, 10, -8, -2.8, 1.6, 0.4, 0.9, 'smooth'),
    (0.32, -30, 9, -8, -2.6, 1.2, 0.4, 24, 'in'),
    (0.36, -30, 9, -8, -2.6, 1.2, 0.4, 24, 'step'),
    (0.38, -30, 9, -8, -2.6, 1.2, 0.4, 16, 'step'),
    (0.58, -31, 10, -8, -2.7, 1.3, 0.4, 1.4, 'out'),
    (0.64, -26, 15, -8, -2.7, 2.2, 0.4, 0, 'in'),
    (0.70, -24, 9, -6, -2.4, 1.2, 0.4, 0, 'smooth'),
    (0.78, -16, 7, -4, -1.8, 1.0, 1.8, 0, 'smooth'),
    (0.84, -14, 6, -4, -1.6, 0.9, 0.2, 0, 'in'),
    (1.00, 0, 0, 0, 0, 0, 0, 0, 'smooth'),
]

# Shotguns: cant the gun, push three shells up into the loading port, then rack the pump.
# Columns as above, then shell travel below the port (None = hidden) and pump travel rearwards.
PUMP_KEYS = [
    (0.00, 0, 0, 0, 0, 0, 0, None, 0, 'smooth'),
    (0.12, -38, 6, -6, -2.4, 1.6, 0.4, None, 0, 'smooth'),
]
for _start in (0.16, 0.36, 0.56):
    PUMP_KEYS += [
        (_start, -38, 6, -6, -2.4, 1.6, 0.4, 3.5, 0, 'step'),
        (_start + 0.10, -38, 7.5, -6, -2.4, 1.9, 0.4, 0.4, 0, 'out'),
        (_start + 0.16, -38, 6, -6, -2.4, 1.6, 0.4, None, 0, 'step'),
    ]
PUMP_KEYS += [
    (0.78, -20, 6, -4, -1.6, 1.0, 0.4, None, 0, 'smooth'),
    (0.84, -20, 8, -4, -1.6, 1.2, 1.2, None, 2.6, 'in'),
    (0.90, -18, 6, -4, -1.4, 1.0, 0.2, None, 0, 'out'),
    (1.00, 0, 0, 0, 0, 0, 0, None, 0, 'smooth'),
]

# A 12-gauge shell in gun model space, lying along the barrel just under the loading port.
SHELL = [((3.6, 6.6, 7.55), (5.4, 7.4, 8.45), 'shell'), ((3.0, 6.55, 7.5), (3.6, 7.45, 8.5), 'brass')]


def _ease(mode, t):
    if mode == 'step':
        return 1.0 if t >= 1 else 0.0
    if mode == 'in':
        return t * t
    if mode == 'out':
        return 1 - (1 - t) * (1 - t)
    return t * t * (3 - 2 * t)


def sample(keys, p):
    """Interpolated key values at progress p (None values are not interpolated)."""
    for a, b in zip(keys, keys[1:]):
        if a[0] <= p <= b[0]:
            t = _ease(b[-1], (p - a[0]) / max(1e-6, b[0] - a[0]))
            out = []
            for va, vb in zip(a[1:-1], b[1:-1]):
                if va is None or vb is None:
                    out.append(vb if t >= 1 else va)
                else:
                    out.append(va + (vb - va) * t)
            return out
    return list(keys[-1][1:-1])


# ---------------------------------------------------------------- rotation maths (matches the client)

def _rx(d):
    a = math.radians(d)
    c, s = math.cos(a), math.sin(a)
    return [[1, 0, 0], [0, c, -s], [0, s, c]]


def _ry(d):
    a = math.radians(d)
    c, s = math.cos(a), math.sin(a)
    return [[c, 0, s], [0, 1, 0], [-s, 0, c]]


def _rz(d):
    a = math.radians(d)
    c, s = math.cos(a), math.sin(a)
    return [[c, -s, 0], [s, c, 0], [0, 0, 1]]


def _mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def _apply(m, v):
    return [sum(m[i][k] * v[k] for k in range(3)) for i in range(3)]


def euler_matrix(r):
    """ItemTransform rotation: Quaternionf.rotationXYZ = Rx * Ry * Rz."""
    return _mul(_rx(r[0]), _mul(_ry(r[1]), _rz(r[2])))


def to_euler(m):
    b = math.asin(max(-1.0, min(1.0, m[0][2])))
    if abs(m[0][2]) < 0.99999:
        a = math.atan2(-m[1][2], m[2][2])
        c = math.atan2(-m[0][1], m[0][0])
    else:
        a = math.atan2(m[2][1], m[1][1])
        c = 0.0
    return [round(math.degrees(a), 3), round(math.degrees(b), 3), round(math.degrees(c), 3)]


HIDDEN = {'rotation': [0, 0, 0], 'translation': [0, -80, 0], 'scale': [0, 0, 0]}


def posed(base, view, offset, part_offset=None):
    """Right-hand display for one part: the base hip pose turned by `view` (roll, pitch, yaw) about the
    model centre, moved by `offset`, with the part itself slid by `part_offset` in model units."""
    roll, pitch, yaw = view
    # Pitch and yaw turn the gun in view space; roll cants it about its own barrel (model X).
    r = _mul(_mul(_rx(pitch), _ry(yaw)), _mul(euler_matrix(base["rotation"]), _rx(-roll)))
    s = base['scale'][0]
    t = [base['translation'][i] + offset[i] for i in range(3)]
    if part_offset:
        moved = _apply(r, [c * s for c in part_offset])
        t = [t[i] + moved[i] for i in range(3)]
    return {'rotation': to_euler(r), 'translation': [round(v, 3) for v in t], 'scale': base['scale']}


def mirrored(right):
    """Left-hand display. The client applies Rx(a) Ry(-b) Rz(-c) and negates the X offset, so the
    mirrored pose F * R * G (F flips view X, G flips the gun's width) is decomposed and its Y/Z
    angles negated; the JSON offset stays the same."""
    if right is HIDDEN:
        return HIDDEN
    flip_view = [[-1, 0, 0], [0, 1, 0], [0, 0, 1]]
    flip_width = [[1, 0, 0], [0, 1, 0], [0, 0, -1]]
    a, b, c = to_euler(_mul(flip_view, _mul(euler_matrix(right['rotation']), flip_width)))
    return {'rotation': [a, round(-b, 3), round(-c, 3)], 'translation': right['translation'], 'scale': right['scale']}


def style(gid):
    spec = gunvox.PAINT.get(gid, {})
    if 'pump' in spec:
        return 'pump'
    return 'mag' if 'mag' in spec else 'tilt'


def frames(gid, base, lift=0.0):
    """Per frame: {part name: right-hand display}. Parts: body, mag / pump / shell, optic.

    `lift` raises the gun while it is canted, for pistols whose grip sits below the screen at the hip."""
    kind = style(gid)
    ax, ay = gunvox.mag_axis(gid)
    out = []
    for k in range(FRAMES):
        p = (k + 0.5) / FRAMES
        if kind == 'pump':
            roll, pitch, yaw, dx, dy, dz, shell, pump = sample(PUMP_KEYS, p)
            view, offset = (roll, pitch, yaw), (dx, dy, dz)
            frame = {'body': posed(base, view, offset), 'pump': posed(base, view, offset, (-pump, 0, 0))}
            frame['shell'] = HIDDEN if shell is None else posed(base, view, offset, (0, -shell, 0))
        else:
            roll, pitch, yaw, dx, dy, dz, travel = sample(MAG_KEYS, p)
            view, offset = (roll, pitch, yaw), (dx, dy + lift * min(1.0, abs(roll) / 30.0), dz)
            frame = {'body': posed(base, view, offset)}
            if kind == 'mag':
                frame['mag'] = HIDDEN if travel > HIDE_BEYOND else posed(base, view, offset, (ax * travel, ay * travel, 0))
        frame['optic'] = frame['body']
        out.append(frame)
    return out


def thresholds():
    """Cooldown runs from 1 down to 0 as the reload progresses; frame k covers progress [k/N, (k+1)/N)."""
    return [max(1e-4, round(1 - (k + 1) / FRAMES, 5)) for k in range(FRAMES)]
