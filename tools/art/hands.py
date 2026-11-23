"""First-person gloves and sleeves, baked into every first-person gun and knife model.

The 1.21.11 client draws the player's arm in first person only with an empty main hand, so a held gun
floats. Here each gun model gets a right fist round its pistol grip (hands.grip in the gun's spec) and
a left hand under the handguard (hands.fore), or round the right fist on a pistol, each with a sleeve
running back out of the bottom of the screen. They are elements in the gun's own model space, added to
its first-person bodies (models.with_hands), so every pose, kick, sight, draw and reload frame, being a
model that only changes the display of one of those, carries them with the gun; in the reload the left
hand goes to the magazine, takes it out and brings the new one in (models.reload_hand). A knife's fist
follows the knife and its sleeve keeps to the bottom right corner (models.knife_hand_frames). Boxes may
turn any way (the 1.21.11 client takes x/y/z rotations), so a hand is a few boxes: the back of the
hand, the fingers round the grip, the thumb, a cuff and the sleeve. Faces that never show are left out:
every one is baked again for each pose (tools/art/gunbudget.py counts them).
"""
import math

import numpy as np

# Coyote gloves, which stand out against black steel, and an olive field shirt's sleeves.
PALETTE = ['#6E5B45', '#5A4A38', '#2A2C2F', '#3F4347',      # glove, knuckle pad, cuff, seams
           '#56603F', '#4A5336', '#65704A', '#3A4229',      # sleeve, its shade, light, deep shade
           '#5E4B3A', '#6B5644', '#2E3033', '#4C3F31',      # skin (unused), wrist, glove dark, palm
           '#000000', '#000000', '#000000', '#000000']
GLOVE, KNUCKLE, CUFF, SEAM, SLEEVE, SLEEVE_DARK, SLEEVE_LIGHT, SLEEVE_DEEP = range(8)
PALM = 11
TEXTURE = 'craftov:item/hands'


def texture():
    """The 16x16 palette texture the hands are painted from (4x4 cells)."""
    from PIL import Image
    img = Image.new('RGBA', (16, 16))
    for i, c in enumerate(PALETTE):
        col = tuple(int(c[k:k + 2], 16) for k in (1, 3, 5)) + (255,)
        x, y = (i % 4) * 4, (i // 4) * 4
        for dx in range(4):
            for dy in range(4):
                img.putpixel((x + dx, y + dy), col)
    return img


def _uv(i):
    x, y = (i % 4) * 4, (i // 4) * 4
    return [x + 1, y + 1, x + 3, y + 3]


def _rz(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def _ry(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def _rx(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def _euler_zyx(m):
    """(x, y, z) degrees with m = Rz(z) Ry(y) Rx(x), the element rotation the client builds."""
    sy = -m[2, 0]
    sy = max(-1.0, min(1.0, sy))
    y = math.asin(sy)
    if abs(math.cos(y)) > 1e-6:
        x = math.atan2(m[2, 1], m[2, 2])
        z = math.atan2(m[1, 0], m[0, 0])
    else:
        x = math.atan2(-m[1, 2], m[1, 1])
        z = 0.0
    return math.degrees(x), math.degrees(y), math.degrees(z)


FACES = ('north', 'south', 'east', 'west', 'up', 'down')


def box(centre, half, matrix, colour, tex='#h', skip=()):
    """One element: a box of half sizes `half` along the columns of `matrix`, centred on `centre`, without
    the faces in `skip` (east and west are its own +x and -x ends: a sleeve's ends never show)."""
    c = np.asarray(centre, float)
    h = np.asarray(half, float)
    lo, hi = c - h, c + h
    e = {'from': [round(float(v), 3) for v in lo], 'to': [round(float(v), 3) for v in hi],
         'faces': {f: {'uv': _uv(colour), 'texture': tex} for f in FACES if f not in skip}}
    x, y, z = _euler_zyx(np.asarray(matrix, float))
    if max(abs(x), abs(y), abs(z)) > 1e-3:
        e['rotation'] = {'origin': [round(float(v), 3) for v in c], 'x': round(x, 3), 'y': round(y, 3), 'z': round(z, 3)}
    return e


def frame(forward, up):
    """A rotation whose columns are `forward`, `up` made perpendicular to it, and their cross (right)."""
    f = np.asarray(forward, float)
    f /= np.linalg.norm(f)
    u = np.asarray(up, float)
    u = u - f * f.dot(u)
    u /= np.linalg.norm(u)
    return np.column_stack([f, u, np.cross(f, u)])


def _part(origin, m, local, half, colour, skip=()):
    """A box given in a hand's own frame (origin, rotation m): centre `local`, half sizes along m's columns."""
    return box(np.asarray(origin) + m @ np.asarray(local, float), half, m, colour, skip=skip)


def sleeve(wrist, direction, length=15.0, width=1.25, cuff=1.1):
    """A cuffed sleeve from the wrist back along `direction` (out of the bottom of the screen). Its ends
    never show: the far one is off the screen, the near one inside the cuff, the cuff's inside the glove."""
    d = np.asarray(direction, float)
    d /= np.linalg.norm(d)
    side = np.cross(d, [0.0, 1.0, 0.0])
    if np.linalg.norm(side) < 1e-3:
        side = np.array([0.0, 0.0, 1.0])
    m = frame(d, np.cross(side, d))
    # A model's from and to must stay within -16..32: they are the box before its turn, the centre plus
    # or minus the half sizes. A grip far back (a thumbhole stock) gets a shorter sleeve.
    w = np.asarray(wrist, float)
    while length > 6 and not all(-16 <= c - h and c + h <= 32 for c, h in zip(w + d * (cuff + length / 2), (length / 2, width, width))):
        length -= 0.5
    return [_part(wrist, m, (cuff / 2, 0, 0), (cuff / 2, width * 0.9, width * 0.9), CUFF, skip=('east', 'west')),
            _part(wrist, m, (cuff + length / 2, 0, 0), (length / 2, width, width), SLEEVE, skip=('east', 'west'))]


def fist(origin, axis_deg, half_depth, half_width, side=1, forearm=None):
    """A gloved fist round a grip whose line rises `axis_deg` from the barrel (in the gun's side view),
    centred on `origin`, the grip `half_depth` thick along the barrel and `half_width` across. side 1:
    the right hand, back of the hand on the gun's right (+Z), fingers round the front to the left;
    -1 mirrors it. `forearm`: the direction the sleeve runs back in (gun space)."""
    a = math.radians(axis_deg)
    up = np.array([math.cos(a), math.sin(a), 0.0])           # along the grip, bottom to top
    fwd = np.array([math.sin(a), -math.cos(a), 0.0])        # across the grip, towards the muzzle
    if fwd[0] < 0:
        fwd = -fwd
    zs = np.array([0.0, 0.0, float(side)])
    m = np.column_stack([fwd, up, zs])
    o = np.asarray(origin, float)
    w, d = half_width, half_depth
    parts = [
        _part(o, m, (-0.15, 0.0, w + 0.45), (d + 0.55, 1.65, 0.45), GLOVE),            # back of the hand
        _part(o, m, (d + 0.42, 0.0, 0.1), (0.42, 1.55, w + 0.75), KNUCKLE),            # fingers across the front
        _part(o, m, (d * 0.25, -0.05, -w - 0.36), (d * 0.75 + 0.2, 1.45, 0.36), PALM),  # finger tips round the far side
        _part(o, m, (0.25, 1.95, -w - 0.3), (d + 0.5, 0.36, 0.34), GLOVE),             # thumb over the top, far side
    ]
    if forearm is not None:
        parts += sleeve(fist_wrist(origin, axis_deg, half_depth, half_width, side), forearm)
    return parts


def fist_wrist(origin, axis_deg, half_depth, half_width, side=1):
    """Where the sleeve of a fist (see fist) starts: behind the grip and below the back of the hand."""
    a = math.radians(axis_deg)
    up = np.array([math.cos(a), math.sin(a), 0.0])
    fwd = np.array([math.sin(a), -math.cos(a), 0.0])
    if fwd[0] < 0:
        fwd = -fwd
    m = np.column_stack([fwd, up, np.array([0.0, 0.0, float(side)])])
    return np.asarray(origin, float) + m @ np.array([-half_depth - 0.5, -1.0, half_width + 0.5])


def cup(origin, half_width, forearm, length=2.8, thumb=True):
    """A left hand under a handguard (or a pump) whose bottom middle is `origin` and which is
    `half_width` across: the palm under it, the fingers up the right side, the thumb along the left."""
    o = np.asarray(origin, float)
    m = np.identity(3)
    w = half_width
    # From the hip the camera sees a gun's left side (it is right of the camera), so the outer edge of
    # the hand and the thumb are on that side; the fingers curl round the far one.
    parts = [
        _part(o, m, (0.0, -0.48, 0.0), (length / 2, 0.48, w + 0.3), GLOVE),           # palm under
        _part(o, m, (-0.1, -0.15, -w - 0.42), (length / 2 + 0.1, 0.85, 0.42), KNUCKLE),  # outer edge, lower left
        _part(o, m, (0.1, 0.55, w + 0.38), (length / 2 - 0.15, 0.95, 0.38), GLOVE),     # fingers up the far side
    ]
    if thumb:
        parts.append(_part(o, m, (0.7, 1.0, -w - 0.3), (length / 2 - 0.2, 0.3, 0.3), GLOVE))   # thumb along the near side
    wrist = o + np.array([-length / 2 - 0.2, -0.45, -w - 0.3])
    return parts + sleeve(wrist, forearm)
