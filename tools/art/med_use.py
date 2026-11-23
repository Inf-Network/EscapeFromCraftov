"""Hand motion of the medical items while they are used (#41), baked into item model frames.

A medical item is a vanilla consumable (ItemFactory) with no use animation of its own (`none`;
the painkillers keep `eat`, which brings the bottle to the mouth): vanilla's brush, crossbow,
bundle and trident poses made a bandage brush sand and an injector a raised trident. Its item
definition instead picks a frame with `minecraft:using_item` and a `minecraft:use_duration` range
dispatch, scaled so the use runs from 0 to 1 over the item's use time in items.yml, like the
vanilla bow's pull. The client counts the use itself, so the motion costs no packets, and other
players see the third-person frames on the item in your hand.

The vanilla client draws the arm by itself: in the first person it is not drawn while an item is
held, and in the third person it can only take one of vanilla's use poses. So a thigh injection
is the injector turned needle down and driven down past the bottom of the view, not an arm moving.

Every medical item is a flat sprite (minecraft:item/generated). A pose is keyed as

  yaw, pitch, roll  turn of the whole sprite in view space, on top of its rest pose (roll turns it
                    in the screen plane, pitch tips its top away, yaw turns its face to the camera)
  spin              rotation in the sprite's own plane
  dx, dy, dz        where it goes, in display units (1/16 block): right, up, toward the camera

about the middle of the sprite. Keys are joined smoothly (Catmull-Rom), and a key marked 'hold'
stops there. Every key list starts and ends at the rest pose (0 everywhere), so the last frame
meets the held model without a jump. The third person has its own keys, in the same terms on top
of the third-person rest pose, where "view space" is the hand's (see THIRD).

Cues (items.yml med.cues) are timed to the motion: the needle goes in on med.inject, the gauze
goes round on med.wrap, the strap is pulled on med.strap.

Preview offline with `fpview.py <id> meduse sheet.png`, in the game with the shots scene `meduse`.
"""
import math

import reload

# Vanilla minecraft:item/generated, which every medical item uses.
FP_BASE = {'rotation': [0, -90, 25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]}
TP_BASE = {'rotation': [0, 0, 0], 'translation': [0, 3, 1], 'scale': [0.55, 0.55, 0.55]}

MIN_TURN = 2.0    # degrees a frame must turn, or
MIN_MOVE = 0.25   # display units it must move, to be baked as a frame of its own


def _circle(start, end, turns, centre, radius, spin_per_turn, extra=(0, 0, 0, 0), steps=6, plane=(0, 1)):
    """Keys going round `turns` times between progress `start` and `end`: the sprite's middle on a
    circle about `centre` (dx, dy, dz) in the plane of two of those terms (`plane`, dx and dy by
    default), spinning as it goes, like a roll of gauze wound round a limb. `extra` is the yaw,
    pitch, roll and spin it keeps meanwhile."""
    keys = []
    n = int(turns * steps)
    a_axis, b_axis = plane
    for i in range(n + 1):
        a = 2 * math.pi * i / steps
        p = start + (end - start) * i / n
        offset = list(centre)
        offset[a_axis] = round(centre[a_axis] + radius[0] * math.cos(a) - radius[0], 3)
        offset[b_axis] = round(centre[b_axis] + radius[1] * math.sin(a), 3)
        keys.append((round(p, 4), extra[0], extra[1], extra[2], extra[3] + spin_per_turn * i / steps) + tuple(offset))
    return keys


def _shake(start, end, count, base, amp):
    """Keys shaking about `base` (yaw, pitch, roll, spin, dx, dy, dz) `count` times: every other key
    pushed by `amp` (the same seven terms), as in a kit being rummaged through or a bottle rattled."""
    keys = []
    for i in range(count * 2 + 1):
        p = start + (end - start) * i / (count * 2)
        sign = 0 if i in (0, count * 2) else (1 if i % 2 else -1)
        keys.append((round(p, 4),) + tuple(round(b + sign * a, 3) for b, a in zip(base, amp)))
    return keys


REST = (0, 0, 0, 0, 0, 0, 0)

# ---------------------------------------------------------------- first person
# (progress, yaw, pitch, roll, spin, dx, dy, dz[, 'hold'])

# Injector, 1.5 s: the cap comes off at the start (med.cap), the needle is turned down and brought
# in front of the body, driven down into the thigh at 0.3 (med.inject), held while the plunger goes
# home, pulled out and put away.
FP_ADRENALINE = [
    (0.00,) + REST + ('hold',),
    (0.20, 20, 0, 0, 165, -6.0, 1.0, -1.5),
    (0.28, 20, 0, 0, 165, -6.0, 1.6, -1.5, 'hold'),
    (0.36, 20, 0, 0, 165, -5.5, -3.2, -1.0, 'hold'),
    (0.62, 20, 0, 0, 165, -5.5, -3.8, -1.0, 'hold'),
    (0.78, 20, 0, 0, 165, -6.0, 0.5, -1.5),
    (1.00,) + REST + ('hold',),
]
# Self-revive injector, 4 s, used lying down: the same shot, slower (med.inject at 0.15), held long
# while it takes, then the gasp (med.gasp at 0.7) as it is pulled out.
FP_REVIVE = [
    (0.00,) + REST + ('hold',),
    (0.10, 20, 0, 0, 165, -6.0, 1.0, -1.5),
    (0.14, 20, 0, 0, 165, -6.0, 1.6, -1.5, 'hold'),
    (0.19, 20, 0, 0, 165, -5.5, -3.2, -1.0, 'hold'),
    (0.55, 20, 0, 0, 165, -5.5, -3.9, -1.0, 'hold'),
    (0.70, 20, 0, 0, 165, -6.0, 0.5, -1.5),
    (0.80, 10, 0, 0, 90, -2.0, 0.6, -0.8),
    (1.00,) + REST + ('hold',),
]
# Bandage, 2.5 s: the roll brought in front, face on, and wound round the limb twice (med.wrap at
# 0.3 and 0.62), then lowered.
FP_BANDAGE = ([(0.00,) + REST + ('hold',), (0.14, 45, 0, 0, 0, -4.5, -1.5, -1.5)]
              + _circle(0.2, 0.86, 2, (-4.5, -1.5, -1.5), (1.8, 1.4), -360, (45, 0, 0, 0))
              + [(1.00,) + REST + ('hold',)])
# Haemostatic dressing, 3 s: the pad brought in front and pressed into the wound, worked round once,
# pressed again (med.wrap at 0.25 and 0.6).
FP_HEMOSTAT = ([(0.00,) + REST + ('hold',), (0.15, 50, -10, 0, 0, -4.5, -2.0, -1.5),
                (0.24, 50, -10, 0, 0, -4.5, -3.2, -2.5, 'hold'), (0.34, 50, -10, 0, 0, -4.5, -2.0, -1.5)]
               + _circle(0.4, 0.62, 1, (-4.5, -2.0, -1.5), (1.4, 1.0), -200, (50, -10, 0, 0))
               + [(0.70, 50, -10, 0, -200, -4.5, -3.2, -2.5, 'hold'), (0.84, 50, -10, 0, -200, -4.5, -2.0, -1.5),
                  (1.00, 0, 0, 0, -360, 0, 0, 0, 'hold')])
# Splint, 4 s: laid upright along the leg in front, then its strap pulled tight twice, a jerk each
# (med.strap at 0.45 and 0.72).
FP_SPLINT = [
    (0.00,) + REST + ('hold',),
    (0.18, 40, 0, -50, 0, -4.5, -0.6, -1.5),
    (0.26, 40, 0, -50, 0, -4.5, -1.0, -1.5, 'hold'),
    (0.43, 40, 0, -50, 0, -4.5, -1.0, -1.5, 'hold'),
    (0.47, 44, 0, -58, 0, -4.0, -0.2, -1.5),
    (0.53, 40, 0, -50, 0, -4.5, -1.0, -1.5, 'hold'),
    (0.70, 40, 0, -50, 0, -4.5, -1.0, -1.5, 'hold'),
    (0.74, 44, 0, -58, 0, -4.0, -0.2, -1.5),
    (0.80, 40, 0, -50, 0, -4.5, -1.0, -1.5, 'hold'),
    (1.00,) + REST + ('hold',),
]


def _kit(open_at, close_at, shakes):
    """A kit (small, large, surgical): lifted in front and turned face on, tipped open as its zip
    goes (med.kit at the start), shaken a little `shakes` times while it is worked through, tipped
    shut and lowered."""
    held = (70, -25, 0, 0, -5.0, -0.5, -2.0)
    return ([(0.00,) + REST + ('hold',), (open_at, 70, 0, 0, 0, -5.0, -0.5, -2.0), (open_at + 0.06,) + held + ('hold',)]
            + _shake(open_at + 0.08, close_at - 0.04, shakes, held, (0, 4, 3, 0, 0.35, 0.3, 0))[1:-1]
            + [(close_at,) + held + ('hold',), (close_at + 0.06, 70, 0, 0, 0, -5.0, -0.5, -2.0), (1.00,) + REST + ('hold',)])


FP_MEDKIT_S = _kit(0.12, 0.84, 4)
FP_MEDKIT_L = _kit(0.1, 0.86, 6)
FP_SURGERY = _kit(0.05, 0.93, 22)
# Painkillers, 1.5 s with vanilla's eating motion on top: the bottle rattled as the pills are shaken
# out (med.pills), then held while it goes to the mouth and the pills are swallowed (med.swallow).
FP_PAINKILLER = (_shake(0.0, 0.4, 4, REST, (0, 0, 0, 14, 0.3, 0.6, 0))
                 + [(0.45,) + REST + ('hold',), (1.00,) + REST + ('hold',)])

FIRST = {'adrenaline': FP_ADRENALINE, 'revive_kit': FP_REVIVE, 'bandage': FP_BANDAGE, 'hemostat': FP_HEMOSTAT,
         'splint': FP_SPLINT, 'medkit_s': FP_MEDKIT_S, 'medkit_l': FP_MEDKIT_L, 'surgery': FP_SURGERY,
         'painkiller': FP_PAINKILLER}

# ---------------------------------------------------------------- third person
# The same terms, on top of the third-person rest pose, in the hand's space. With vanilla's item pose
# (arm hanging a little forward) the sprite rests almost flat, face up, its top pointing forward: in
# the hand's space dx is out to the side (the right of a right hand), dy forward, dz up. Seen from
# the front or the side the flat sprite would be a line, so every pose first stands it up (pitch
# -90: its face to the front, its top down), and spin turns it in that upright plane.

UP = (0, -90, 0, 180)        # upright, its top up, its face to the front
TP_INJECT = [
    (0.00,) + REST + ('hold',),
    (0.20, 0, -90, 0, 45, -0.5, 1.0, 1.5),          # needle down, lifted
    (0.28, 0, -90, 0, 45, -0.5, 1.0, 2.0, 'hold'),
    (0.36, 0, -90, 0, 45, -1.0, 1.0, -2.5, 'hold'),  # driven into the thigh (med.inject at 0.3)
    (0.62, 0, -90, 0, 45, -1.0, 1.0, -2.9, 'hold'),
    (0.78, 0, -90, 0, 45, -0.5, 1.0, 1.0),
    (1.00,) + REST + ('hold',),
]
# The self-revive injector is only ever used lying down, face to the floor, where vanilla's crawling
# pose lays the arm along the body, the hand by the hip: posed as for the shot standing, the
# injector stayed under the body. So it goes the other way from the hand (dy -6: off the back,
# towards the sky) and is rolled a quarter turn about the arm, its face to the side, where it is
# seen driven down along the thigh.
TP_REVIVE = [
    (0.00,) + REST + ('hold',),
    (0.10, 0, -90, 90, 45, -0.5, -6.0, 1.5),
    (0.14, 0, -90, 90, 45, -0.5, -6.0, 2.0, 'hold'),
    (0.19, 0, -90, 90, 45, -1.0, -6.0, -2.5, 'hold'),
    (0.55, 0, -90, 90, 45, -1.0, -6.0, -2.9, 'hold'),
    (0.72, 0, -90, 90, 45, -0.5, -6.0, 1.0),
    (1.00,) + REST + ('hold',),
]
# A roll of gauze going round the thigh: circles out to the side and up and down.
TP_WRAP = ([(0.00,) + REST + ('hold',), (0.14,) + UP + (0, 1.5, -1.0)]
           + _circle(0.2, 0.86, 2, (0, 1.5, -1.0), (1.8, 1.8), -360, UP, plane=(0, 2))
           + [(1.00,) + REST + ('hold',)])
TP_PRESS = ([(0.00,) + REST + ('hold',), (0.15,) + UP + (0, 1.5, -1.0), (0.24,) + UP + (0, 2.5, -2.0, 'hold'),
             (0.34,) + UP + (0, 1.5, -1.0)]
            + _circle(0.4, 0.62, 1, (0, 1.5, -1.0), (1.4, 1.4), -200, UP, plane=(0, 2))
            + [(0.70, 0, -90, 0, -20, 0, 2.5, -2.0, 'hold'), (0.84, 0, -90, 0, -20, 0, 1.5, -1.0),
               (1.00, 0, 0, 0, -360, 0, 0, 0, 'hold')])
# The splint upright against the leg, jerked up as each strap is pulled tight (med.strap).
TP_SPLINT = [
    (0.00,) + REST + ('hold',),
    (0.18, 0, -90, 0, 225, -0.5, 1.0, -1.5),
    (0.43, 0, -90, 0, 225, -0.5, 1.0, -1.5, 'hold'),
    (0.47, 0, -90, 0, 235, -0.5, 1.0, 0.2),
    (0.53, 0, -90, 0, 225, -0.5, 1.0, -1.5, 'hold'),
    (0.70, 0, -90, 0, 225, -0.5, 1.0, -1.5, 'hold'),
    (0.74, 0, -90, 0, 235, -0.5, 1.0, 0.2),
    (0.80, 0, -90, 0, 225, -0.5, 1.0, -1.5, 'hold'),
    (1.00,) + REST + ('hold',),
]


def _tp_kit(open_at, close_at, shakes):
    """A kit brought up off the hand to the front of the chest, upright to the people in front,
    tipped open towards the body and worked through with a shake big enough to see from across a
    room. The arm stays down (vanilla has no pose for it), so the kit leaves the hand."""
    lifted = (0, -90, 0, 180, -3.0, 4.0, 4.0)
    held = (0, -65, 0, 180, -3.5, 4.0, 5.0)
    return ([(0.00,) + REST + ('hold',), (open_at,) + lifted, (open_at + 0.06,) + held + ('hold',)]
            + _shake(open_at + 0.08, close_at - 0.04, shakes, held, (10, 10, 12, 0, 0.6, 0.4, 0.9))[1:-1]
            + [(close_at,) + held + ('hold',), (close_at + 0.06,) + lifted, (1.00,) + REST + ('hold',)])


# The bottle upright, rattled at the hand. Vanilla's eating has no third-person pose: the arm stays
# down, and the bottle only goes to the mouth in the first person.
TP_PAINKILLER = ([(0.00,) + REST + ('hold',), (0.08,) + UP + (0, 0.5, 1.0)]
                 + _shake(0.08, 0.4, 4, UP + (0, 0.5, 1.0), (0, 0, 0, 14, 0.3, 0, 0.6))[1:]
                 + [(0.5,) + UP + (0, 0.5, 1.0), (1.00,) + REST + ('hold',)])

THIRD = {'adrenaline': TP_INJECT, 'revive_kit': TP_REVIVE, 'bandage': TP_WRAP, 'hemostat': TP_PRESS,
         'splint': TP_SPLINT, 'medkit_s': _tp_kit(0.12, 0.84, 4), 'medkit_l': _tp_kit(0.1, 0.86, 6),
         'surgery': _tp_kit(0.05, 0.93, 22), 'painkiller': TP_PAINKILLER}

ITEMS = tuple(FIRST)


# ---------------------------------------------------------------- interpolation

def sample(keys, p):
    """Values at progress p: cubic Hermite through the keys, Catmull-Rom tangents, zero at 'hold'."""
    size = 7

    def values(key):
        return list(key[1:1 + size])
    if p <= keys[0][0]:
        return values(keys[0])
    if p >= keys[-1][0]:
        return values(keys[-1])

    def tangent(i):
        if i == 0 or i == len(keys) - 1 or 'hold' in keys[i][1 + size:]:
            return [0.0] * size
        a, b = keys[i - 1], keys[i + 1]
        return [(vb - va) / (b[0] - a[0]) for va, vb in zip(values(a), values(b))]

    for i in range(len(keys) - 1):
        a, b = keys[i], keys[i + 1]
        if a[0] <= p <= b[0]:
            h = b[0] - a[0]
            if h <= 0:
                return values(b)
            s = (p - a[0]) / h
            h00 = 2 * s ** 3 - 3 * s ** 2 + 1
            h10 = s ** 3 - 2 * s ** 2 + s
            h01 = -2 * s ** 3 + 3 * s ** 2
            h11 = s ** 3 - s ** 2
            ma, mb = tangent(i), tangent(i + 1)
            return [h00 * va + h10 * h * ta + h01 * vb + h11 * h * tb
                    for va, vb, ta, tb in zip(values(a), values(b), ma, mb)]
    return values(keys[-1])


def pose(base, values):
    """Right-hand display for one set of key values, on top of `base`."""
    yaw, pitch, roll, spin, dx, dy, dz = values
    view = reload._mul(reload._rz(roll), reload._mul(reload._rx(pitch), reload._ry(yaw)))
    r = reload._mul(view, reload._mul(reload.euler_matrix(base['rotation']), reload._rz(spin)))
    t = [base['translation'][i] + (dx, dy, dz)[i] for i in range(3)]
    return {'rotation': reload.to_euler(r), 'translation': [round(v, 3) for v in t], 'scale': base['scale']}


def _moved(a, b):
    if a is None:
        return True
    turn = max(abs(x - y) for x, y in zip(a[:4], b[:4]))
    move = max(abs(x - y) for x, y in zip(a[4:], b[4:]))
    return turn >= MIN_TURN or move >= MIN_MOVE


def frames(item_id, use_seconds):
    """The frames of a use of `use_seconds`, one a tick at most: [(threshold, first person right
    hand, third person right hand)], threshold being the share of the use from which the frame
    shows. Tick k of the use (use_duration k) shows the pose at (k + 1) / ticks, so the item is
    already moving on the tick the use starts; a tick that moved less than MIN_TURN / MIN_MOVE
    from the last frame kept in both views gets no frame of its own."""
    ticks = max(1, round(use_seconds * 20))
    out = []
    last_fp = last_tp = None
    for k in range(ticks):
        p = min(1.0, (k + 1) / ticks)
        fp = sample(FIRST[item_id], p)
        tp = sample(THIRD[item_id], p)
        if k == 0 or _moved(last_fp, fp) or _moved(last_tp, tp):
            threshold = 0.0 if k == 0 else round((k - 0.5) / ticks, 5)
            out.append((threshold, pose(FP_BASE, fp), pose(TP_BASE, tp)))
            last_fp, last_tp = fp, tp
    return out


def frame_model(held, fp, tp):
    """A frame: the held model with this frame's displays, in both hands."""
    return {'parent': held, 'display': {'firstperson_righthand': fp, 'firstperson_lefthand': reload.mirrored(fp),
                                        'thirdperson_righthand': tp, 'thirdperson_lefthand': reload.mirrored(tp)}}
