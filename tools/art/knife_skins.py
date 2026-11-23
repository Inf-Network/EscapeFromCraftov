"""Knife skins (#106): the same four knife sprites in other colours and patterns.

A skin is a look, not an item: the player's profile says which skins they own and which one each knife
wears, and the server only swaps the item model of the knife in the melee slot (SkinService). The
catalogue (id, knife, name, price in 点券) is src/main/resources/skins.yml, which the plugin reads
too; every skin there needs a recipe here, and build.py refuses to build without one.

Each knife icon (icons.py) is drawn in a handful of flat colours, one per part: blade, the light line
along it (an edge, or the ridge of the dagger), the dark spine line, guard, grip, grip wraps, pommel
or rivets, and the karambit's ring. A recipe recolours those parts: a part is a flat colour or a
function of the pixel, and a part a recipe leaves out keeps its own colour. The edge and spine follow
the blade (lighter and darker) unless the recipe gives them their own.

Patterns are worked out on the 32x32 sprite itself, so they read at the size the client draws them:
`u` runs along the knife from the grip to the tip and `v` across it (texture pixels), `t` is how far
along the blade a pixel is (0 at the guard, 1 at the tip).

All names and patterns are Craftov's own: weathering, camouflage, plating and the like, named after
things in the game's world. Preview offline with `fpview.py <skin id> inspect sheet.png`.
"""
import math

import numpy as np

import icons
from px import Canvas, mix, rgb, shade

# The colour each knife's parts are drawn in (icons.py) -> part.
PARTS = {
    'knife_starter': {'#9AA3AB': 'blade', '#D7DDE2': 'edge', '#5D646B': 'spine', '#1E1F22': 'guard',
                      '#2E3236': 'grip', '#4A5056': 'wrap', '#8A9198': 'pommel'},
    'knife_tactical': {'#4B5259': 'blade', '#8E979F': 'edge', '#B08D57': 'guard', '#5A4632': 'grip',
                       '#3B2E21': 'wrap'},
    'knife_machete': {'#A7ADB2': 'blade', '#E1E5E8': 'edge', '#6F767C': 'spine', '#1E1F22': 'guard',
                      '#7A5230': 'grip', '#8A9198': 'pommel'},
    'knife_karambit': {'#C0C6CC': 'blade', '#EEF1F3': 'edge', '#3A2A4F': 'grip', '#9AA3AB': 'pommel'},
}
KNIVES = list(PARTS)

# Grip and tip of each sprite (knife_inspect.GRIP): the axis the patterns are laid along.
AXIS = {
    'knife_starter': ((8, 25), (29, 3)),
    'knife_tactical': ((8, 25), (29, 2)),
    'knife_machete': ((7, 25), (30, 2)),
    'knife_karambit': ((5.5, 28), (28, 3)),
}


# ---------------------------------------------------------------- pattern helpers

def smooth_noise(seed, passes=3, size=32):
    """Value noise in 0..1 with blotches a few pixels across: random cells, box-blurred a few times."""
    rng = np.random.default_rng(seed)
    a = rng.random((size + 8, size + 8))
    for _ in range(passes):
        a = (a + np.roll(a, 1, 0) + np.roll(a, -1, 0) + np.roll(a, 1, 1) + np.roll(a, -1, 1)) / 5
    a = a[4:-4, 4:-4]
    return (a - a.min()) / (a.max() - a.min())


def block_noise(seed, block=2, size=32):
    """One random value per block x block cell (digital camouflage)."""
    rng = np.random.default_rng(seed)
    cells = rng.random((size // block + 1, size // block + 1))
    return np.kron(cells, np.ones((block, block)))[:size, :size]


def specks(seed, density, size=32):
    rng = np.random.default_rng(seed)
    return rng.random((size, size)) < density


def gradient(stops, t):
    """Colour at t (0..1) along [(position, colour), ...]."""
    t = min(1.0, max(0.0, t))
    for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
        if t <= p1:
            return mix(c0, c1, (t - p0) / (p1 - p0) if p1 > p0 else 0)
    return stops[-1][1]


# ---------------------------------------------------------------- rendering

class _Px:
    __slots__ = ('x', 'y', 'u', 'v', 't')


def _part_map(knife):
    """The knife's sprite before its outline, and its part per pixel (None where transparent)."""
    c = Canvas()
    icons.ICONS[knife](c)
    colours = {rgb(h)[:3]: part for h, part in PARTS[knife].items()}
    img = c.img
    parts = [[None] * 32 for _ in range(32)]
    for y in range(32):
        for x in range(32):
            px = img.getpixel((x, y))
            if px[3]:
                part = colours.get(px[:3])
                if part is None:
                    raise ValueError('%s: colour #%02X%02X%02X at %d,%d has no part' % ((knife,) + px[:3] + (x, y)))
                parts[y][x] = part
    return img, parts


def _coords(knife, parts):
    """u (along the knife from the grip), v (across it) and t (0..1 along the blade) per pixel."""
    (gx, gy), (tx, ty) = AXIS[knife]
    length = math.hypot(tx - gx, ty - gy)
    ax, ay = (tx - gx) / length, (ty - gy) / length
    uv = {}
    for y in range(32):
        for x in range(32):
            if parts[y][x]:
                dx, dy = x - gx, y - gy
                uv[(x, y)] = (dx * ax + dy * ay, dx * -ay + dy * ax)
    blade = [uv[p][0] for p in uv if parts[p[1]][p[0]] in ('blade', 'edge', 'spine')]
    lo, hi = min(blade), max(blade)
    return {p: (u, v, (u - lo) / (hi - lo)) for p, (u, v) in uv.items()}


def _colour(spec, p):
    return spec(p) if callable(spec) else spec


def render(skin_id):
    """The skin's 32x32 sprite, outlined like every item icon."""
    recipe = SKINS[skin_id]
    knife = recipe['knife']
    base, parts = _part_map(knife)
    coords = _coords(knife, parts)
    out = Canvas()
    p = _Px()
    for (x, y), (u, v, t) in coords.items():
        p.x, p.y, p.u, p.v, p.t = x, y, u, v, t
        part = parts[y][x]
        if part in recipe:
            colour = _colour(recipe[part], p)
        elif part == 'edge' and 'blade' in recipe:
            colour = shade(_colour(recipe['blade'], p), 1.35)
        elif part == 'spine' and 'blade' in recipe:
            colour = shade(_colour(recipe['blade'], p), 0.65)
        else:
            colour = base.getpixel((x, y))
        out.px(x, y, tuple(colour[:3]) + (255,))
    return out.outline().img


def knife_of(skin_id):
    return SKINS[skin_id]['knife']


# ---------------------------------------------------------------- the skins
# Every recipe: knife, and per part a colour or fn(px) -> colour, where px has x, y, u, v, t.

def _rustport():
    rust, bright, pit, teal = rgb('#8C4A2F'), rgb('#C0703F'), rgb('#4E2A1E'), rgb('#3F8C7E')
    n, m, f = smooth_noise(11), smooth_noise(12, 1), specks(13, 0.05)

    def blade(p):
        k = n[p.y, p.x]
        if f[p.y, p.x] and m[p.y, p.x] > 0.55:
            return teal
        return pit if k < 0.3 else bright if k > 0.68 else rust
    return {'knife': 'knife_starter', 'blade': blade, 'edge': rgb('#C9C2B4'), 'spine': rgb('#4A2A1E'),
            'guard': rgb('#3A2B24'), 'grip': rgb('#4E5233'), 'wrap': rgb('#383B25'), 'pommel': rgb('#8C4A2F')}


def _snowline():
    ice, snow, glint = rgb('#6FA7D3'), rgb('#F0F6FA'), specks(21, 0.12)

    def blade(p):
        if glint[p.y, p.x] and p.t > 0.45:
            return rgb('#FFFFFF')
        return gradient([(0, ice), (0.35, mix(ice, snow, 0.35)), (0.75, snow), (1, snow)], p.t)
    return {'knife': 'knife_starter', 'blade': blade, 'edge': rgb('#FFFFFF'),
            'spine': lambda p: shade(blade(p), 0.62), 'guard': rgb('#E3EAF0'), 'grip': rgb('#B9C6D2'),
            'wrap': rgb('#7E95AA'), 'pommel': rgb('#6FA7D3')}


def _hazard():
    # Not quite black: the stripes must stay apart from the sprite's outline.
    amber, black = rgb('#F2B544'), rgb('#34343B')

    def stripe(p):
        return (p.x // 2) % 2 == 0

    return {'knife': 'knife_starter', 'blade': lambda p: amber if stripe(p) else black,
            'edge': lambda p: rgb('#FFE3A3') if stripe(p) else rgb('#55555E'), 'spine': rgb('#27272D'),
            'guard': amber, 'grip': rgb('#2E2E35'), 'wrap': rgb('#C8901F'), 'pommel': amber}


def _obsidian():
    glass, sheen, glints = rgb('#2A2433'), rgb('#6E5E8C'), specks(41, 0.06)

    def blade(p):
        if glints[p.y, p.x]:
            return rgb('#B7A2EC')
        band = math.exp(-((p.v + 0.5 - 2.5 * math.sin(p.u * 0.22)) / 1.3) ** 2)
        return mix(glass, sheen, 0.85 * band)
    return {'knife': 'knife_tactical', 'blade': blade, 'edge': rgb('#A988F2'), 'guard': rgb('#3B3A46'),
            'grip': rgb('#25232B'), 'wrap': rgb('#4A3E5E')}


def _nightrun():
    navy, deep, cyan, white = rgb('#1D2C4E'), rgb('#111A31'), rgb('#33E0DA'), rgb('#C9FFFC')

    def ridge(p):
        return white if int(round(p.u)) % 5 == 0 else cyan
    return {'knife': 'knife_tactical', 'blade': lambda p: gradient([(0, deep), (1, navy)], p.t), 'edge': ridge,
            'guard': rgb('#E0409A'), 'grip': rgb('#141B2E'), 'wrap': rgb('#2BB8B3')}


def _gobi():
    sand, tan, khaki, brown = rgb('#E2D2AD'), rgb('#C9AF84'), rgb('#A38A61'), rgb('#76603F')
    n = block_noise(61)

    def blade(p):
        k = n[p.y, p.x]
        return brown if k < 0.25 else khaki if k < 0.5 else sand if k > 0.8 else tan
    return {'knife': 'knife_tactical', 'blade': blade, 'edge': rgb('#EFE5CC'), 'guard': rgb('#76603F'),
            'grip': rgb('#A38A61'), 'wrap': rgb('#76603F')}


def _canopy():
    olive, dark, black, leaf = rgb('#4F6130'), rgb('#2B391D'), rgb('#141910'), rgb('#728540')
    a, b = smooth_noise(71), smooth_noise(72)

    def blade(p):
        if a[p.y, p.x] > 0.66:
            return black
        if b[p.y, p.x] > 0.62:
            return leaf
        return dark if a[p.y, p.x] < 0.3 else olive
    return {'knife': 'knife_machete', 'blade': blade, 'edge': rgb('#C6CBC2'), 'spine': rgb('#1D2614'),
            'guard': rgb('#141910'), 'grip': rgb('#2B391D'), 'pommel': rgb('#728540')}


def _magma():
    coal, ember, glow, hot = rgb('#25211F'), rgb('#7A2C12'), rgb('#FF7A1A'), rgb('#FFC062')
    n = smooth_noise(81, 2)

    def blade(p):
        d = abs(n[p.y, p.x] - 0.5)
        heat = max(0.0, (p.t - 0.55) / 0.45)
        if d < 0.045:
            return hot if heat > 0.5 else glow
        if d < 0.1:
            return mix(ember, glow, 0.25 + 0.5 * heat)
        return mix(coal, ember, 0.55 * heat)
    return {'knife': 'knife_machete', 'blade': blade,
            'edge': lambda p: gradient([(0, rgb('#5A2212')), (0.6, glow), (1, hot)], p.t), 'spine': rgb('#120F0E'),
            'guard': rgb('#3A1A10'), 'grip': rgb('#1D1817'), 'pommel': glow}


def _bronze():
    bronze, etch, patina, polish = rgb('#B08A4A'), rgb('#6E5126'), rgb('#5E8F79'), rgb('#D8B66E')
    spots = specks(91, 0.05)

    def blade(p):
        wave = p.v + 0.4 - 1.6 * math.sin(p.u * 0.8)
        if abs(wave) < 0.55:
            return etch
        if spots[p.y, p.x]:
            return patina
        return polish if wave < -1.6 else bronze
    return {'knife': 'knife_machete', 'blade': blade, 'edge': rgb('#EBCB86'), 'spine': rgb('#6E5126'),
            'guard': rgb('#D9B45A'), 'grip': rgb('#2A1E17'), 'pommel': rgb('#EBCB86')}


def _gilded():
    gold, deep, bright = rgb('#D2A22A'), rgb('#8E6A12'), rgb('#FFE8A0')
    glints = specks(101, 0.07)

    def blade(p):
        if glints[p.y, p.x]:
            return bright
        return mix(deep, gold, min(1.0, 0.45 + 0.35 * (p.v + 1.5)))
    return {'knife': 'knife_karambit', 'blade': blade, 'edge': rgb('#FFF2C4'), 'grip': rgb('#1A161C'),
            'pommel': rgb('#E3B93F')}


def _starchart():
    night, violet, line = rgb('#1C2552'), rgb('#33226A'), rgb('#5A6FC0')
    # Five stars along the curve of the blade, each in the middle of its width, joined by faint lines.
    _, parts = _part_map('knife_karambit')
    coords = _coords('knife_karambit', parts)
    steel = [(xy, c) for xy, c in coords.items() if parts[xy[1]][xy[0]] in ('blade', 'edge')]
    stars = []
    for target, colour in ((0.1, '#FFFFFF'), (0.3, '#A8E6FF'), (0.5, '#FFFFFF'), (0.7, '#FFE9A8'), (0.88, '#FFFFFF')):
        near = sorted((c[1], xy) for xy, c in steel if abs(c[2] - target) < 0.06)
        stars.append((near[len(near) // 2][1], rgb(colour)))

    def on_line(x, y):
        for ((x0, y0), _), ((x1, y1), _) in zip(stars, stars[1:]):
            dx, dy = x1 - x0, y1 - y0
            k = max(0.0, min(1.0, ((x - x0) * dx + (y - y0) * dy) / (dx * dx + dy * dy)))
            if math.hypot(x - x0 - k * dx, y - y0 - k * dy) < 0.5:
                return True
        return False

    def steel_colour(base):
        def fn(p):
            for xy, colour in stars:
                if xy == (p.x, p.y):
                    return colour
            colour = base(p)
            return mix(colour, line, 0.7) if on_line(p.x, p.y) else colour
        return fn
    blade = steel_colour(lambda p: gradient([(0, night), (1, violet)], p.t))
    edge = steel_colour(lambda p: rgb('#8296F0'))
    return {'knife': 'knife_karambit', 'blade': blade, 'edge': edge, 'grip': rgb('#141838'),
            'pommel': rgb('#C9D2FF')}


def _redtide():
    crimson, deep, crest = rgb('#9E1B2A'), rgb('#5E0D18'), rgb('#D5404F')

    def blade(p):
        w = math.sin(p.u * 0.9 + 1.4 * p.v)
        return crest if w > 0.6 else deep if w < -0.5 else crimson
    return {'knife': 'knife_karambit', 'blade': blade, 'edge': rgb('#FF97A0'), 'grip': rgb('#2A0E14'),
            'pommel': rgb('#C9364A')}


SKINS = {
    'starter_rustport': _rustport(),
    'starter_snowline': _snowline(),
    'starter_hazard': _hazard(),
    'tactical_obsidian': _obsidian(),
    'tactical_nightrun': _nightrun(),
    'tactical_gobi': _gobi(),
    'machete_canopy': _canopy(),
    'machete_magma': _magma(),
    'machete_bronze': _bronze(),
    'karambit_gilded': _gilded(),
    'karambit_starchart': _starchart(),
    'karambit_redtide': _redtide(),
}


