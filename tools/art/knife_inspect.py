"""First-person knife inspect animations baked into item model frames.

F with a knife in hand puts the craftov:inspect cooldown group on cooldown for combat.melee.inspect-ticks
(InspectService). The client counts it down every tick and the knife's item definition picks one of
FRAMES display transforms with a `minecraft:cooldown` range dispatch, exactly like the gun reload
(reload.py). The item property reads the cooldown without the partial tick, so the pose changes at
20 Hz: FRAMES is one frame per tick of the default 50-tick inspect.

Each knife is a flat sprite held like a sword (minecraft:item/handheld). A pose is keyed as:

  view rotation   yaw, pitch, roll of the whole knife in view space, on top of the hip pose
                  (yaw +90 turns the flat of the blade to the camera, roll turns it in the screen plane)
  spin            rotation in the knife's own plane, about the grip (a karambit twirled on its ring)
  twist           rotation about the knife's long axis, grip to tip (turning the blade over)
  dx, dy, dz      where the grip goes, in display units (1/16 block) from where it sits at the hip:
                  right, up, toward the camera

and everything turns about the grip, so the knife looks held in a hand that is not drawn. Keys are
joined with a Catmull-Rom curve (the motion flows through them) unless a key is marked 'hold', which
stops there (zero velocity), as at the start and the end. Every key list starts and ends at the hip
pose with spin and twist at whole turns, so the last frame meets the normal model without a jump.

Preview offline with `fpview.py <knife> inspect sheet.png`, in the game with the shots scene
`inspect`. Knife skins (cosmetic variants of a knife to collect and show off in the inspect) are not
made: issue #82 leaves them to the owner to decide on.
"""
import math

import reload

FRAMES = 50  # = combat.melee.inspect-ticks (2.5 s); the motion scales with that setting

# Vanilla minecraft:item/handheld, which knives use.
BASE = {'rotation': [0, -90, 25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]}

# Grip and tip in texture pixels of the 32x32 icon (icons.py draws every knife handle bottom left).
GRIP = {
    'knife_starter': ((8, 25), (29, 3)),
    'knife_tactical': ((8, 25), (29, 2)),
    'knife_machete': ((7, 25), (30, 2)),
    'knife_karambit': ((5.5, 28), (28, 3)),  # the ring, which the twirl turns about
}

# (progress, yaw, pitch, roll, spin, twist, dx, dy, dz[, 'hold'])
# Starter knife: brought up across the view, flat to the camera; turned over to look at the other side
# and back; lowered.
STARTER = [
    (0.00, 0, 0, 0, 0, 0, 0, 0, 0, 'hold'),
    (0.22, 75, -20, 65, 0, 0, -3.0, 4.4, -5.0),
    (0.30, 76, -20, 68, 0, 0, -3.0, 4.6, -5.0, 'hold'),
    (0.50, 76, -20, 68, 0, 180, -3.0, 4.8, -5.0, 'hold'),
    (0.62, 76, -18, 70, 0, 180, -3.0, 4.8, -5.0, 'hold'),
    (0.80, 76, -20, 66, 0, 0, -3.0, 4.6, -5.0, 'hold'),
    (1.00, 0, 0, 0, 0, 0, 0, 0, 0, 'hold'),
]

# Tactical dagger: brought up, tossed into a full flip about the blade and caught flat, tipped to
# look along the edge, lowered.
TACTICAL = [
    (0.00, 0, 0, 0, 0, 0, 0, 0, 0, 'hold'),
    (0.16, 75, -20, 65, 0, 0, -3.0, 4.2, -5.0, 'hold'),
    (0.30, 75, -20, 65, 0, 180, -3.0, 6.2, -5.0),
    (0.44, 75, -20, 65, 0, 360, -3.0, 4.4, -5.0, 'hold'),
    (0.52, 75, -20, 67, 0, 360, -3.0, 4.6, -5.0, 'hold'),
    (0.66, 30, -40, 60, 0, 360, -2.5, 4.6, -5.5, 'hold'),
    (0.78, 32, -40, 62, 0, 360, -2.5, 4.8, -5.5, 'hold'),
    (1.00, 0, 0, 0, 0, 360, 0, 0, 0, 'hold'),
]

# Machete: heavy and slow; laid level across the view, weighed in the hand, turned over, and turned
# back over as it goes down.
MACHETE = [
    (0.00, 0, 0, 0, 0, 0, 0, 0, 0, 'hold'),
    (0.26, 80, -8, 100, 0, 0, -1.5, 4.5, -5.5, 'hold'),
    (0.34, 80, -6, 102, 0, 0, -1.5, 3.6, -5.5),
    (0.42, 80, -8, 100, 0, 0, -1.5, 4.6, -5.5, 'hold'),
    (0.62, 80, -8, 100, 0, 180, -1.5, 4.8, -5.5, 'hold'),
    (0.72, 80, -6, 98, 0, 180, -1.5, 4.8, -5.5, 'hold'),
    (1.00, 0, 0, 0, 0, 360, 0, 0, 0, 'hold'),
]

# Karambit: twirled twice on the ring finger (about 30 degrees a tick), caught, turned over, and
# lowered finishing the turn.
KARAMBIT = [
    (0.00, 0, 0, 0, 0, 0, 0, 0, 0, 'hold'),
    (0.14, 60, 0, 80, -40, 0, -6.0, 6.5, -8.0),
    (0.60, 60, 0, 80, -700, 0, -6.0, 6.5, -8.0),
    (0.66, 72, -15, 64, -720, 0, -4.0, 6.0, -5.5, 'hold'),
    (0.82, 72, -15, 64, -720, 180, -4.0, 6.2, -5.5, 'hold'),
    (1.00, 0, 0, 0, -720, 360, 0, 0, 0, 'hold'),
]

KEYS = {'knife_starter': STARTER, 'knife_tactical': TACTICAL, 'knife_machete': MACHETE, 'knife_karambit': KARAMBIT}


# ---------------------------------------------------------------- interpolation

def _values(key):
    return list(key[1:9])


def sample(keys, p):
    """Values at progress p: cubic Hermite through the keys, Catmull-Rom tangents, zero at 'hold'."""
    if p <= keys[0][0]:
        return _values(keys[0])
    if p >= keys[-1][0]:
        return _values(keys[-1])

    def tangent(i):
        if i == 0 or i == len(keys) - 1 or 'hold' in keys[i][9:]:
            return [0.0] * 8
        a, b = keys[i - 1], keys[i + 1]
        return [(vb - va) / (b[0] - a[0]) for va, vb in zip(_values(a), _values(b))]

    for i in range(len(keys) - 1):
        a, b = keys[i], keys[i + 1]
        if a[0] <= p <= b[0]:
            h = b[0] - a[0]
            t = (p - a[0]) / h
            h00 = 2 * t ** 3 - 3 * t ** 2 + 1
            h10 = t ** 3 - 2 * t ** 2 + t
            h01 = -2 * t ** 3 + 3 * t ** 2
            h11 = t ** 3 - t ** 2
            ma, mb = tangent(i), tangent(i + 1)
            return [h00 * va + h10 * h * ta + h01 * vb + h11 * h * tb
                    for va, vb, ta, tb in zip(_values(a), _values(b), ma, mb)]
    return _values(keys[-1])


# ---------------------------------------------------------------- posing (same maths as the client)

def _axis_angle(axis, deg):
    x, y, z = axis
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    t = 1 - c
    return [[t * x * x + c, t * x * y - s * z, t * x * z + s * y],
            [t * x * y + s * z, t * y * y + c, t * y * z - s * x],
            [t * x * z - s * y, t * y * z + s * x, t * z * z + c]]


def _model_point(px):
    """Texture pixel (32x32, y down) to model space about the item's centre, in blocks."""
    return [px[0] / 32.0 - 0.5, 0.5 - px[1] / 32.0, 0.0]


def pose(knife, values):
    """Right-hand first-person display for one set of key values."""
    yaw, pitch, roll, spin, twist, dx, dy, dz = values
    grip_px, tip_px = GRIP[knife]
    grip, tip = _model_point(grip_px), _model_point(tip_px)
    axis = [tip[i] - grip[i] for i in range(3)]
    n = math.sqrt(sum(v * v for v in axis))
    axis = [v / n for v in axis]
    base = reload.euler_matrix(BASE['rotation'])
    view = reload._mul(reload._rz(roll), reload._mul(reload._rx(pitch), reload._ry(yaw)))
    local = reload._mul(reload._rz(spin), _axis_angle(axis, twist))
    r = reload._mul(view, reload._mul(base, local))
    s = BASE['scale'][0]
    before = reload._apply(base, grip)
    after = reload._apply(r, grip)
    t = [BASE['translation'][i] + (dx, dy, dz)[i] + 16 * s * (before[i] - after[i]) for i in range(3)]
    return {'rotation': reload.to_euler(r), 'translation': [round(v, 3) for v in t], 'scale': BASE['scale']}


def frames(knife):
    """Right-hand display per frame. Frame k is on screen during tick k of the inspect, so it shows the
    pose at progress (k + 1) / FRAMES: the knife is already moving on the tick F is pressed."""
    keys = KEYS[knife]
    out = []
    for k in range(FRAMES):
        out.append(pose(knife, sample(keys, (k + 1) / FRAMES)))
    return out


def thresholds():
    """The cooldown runs from 1 at the tick it starts down to 0; at tick k it is (FRAMES - k) / FRAMES.
    Each threshold sits halfway between two ticks, so rounding never shows a frame a tick early."""
    return [round(1 - (k + 0.5) / FRAMES, 5) for k in range(FRAMES)]
