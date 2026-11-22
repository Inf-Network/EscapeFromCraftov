"""Images used by the BetterHud layout."""
from PIL import Image, ImageDraw
from px import rgb, shade

BG = (12, 14, 18, 165)
ACCENT = rgb('#F2C94C')


def panel(w, h, accent=ACCENT, side='left'):
    img = Image.new('RGBA', (w, h), BG)
    d = ImageDraw.Draw(img)
    if side == 'left':
        d.rectangle([0, 0, 1, h - 1], fill=accent)
    else:
        d.rectangle([w - 2, 0, w - 1, h - 1], fill=accent)
    d.rectangle([0, h - 1, w - 1, h - 1], fill=(255, 255, 255, 30))
    return img


def bar(w, h, color, empty=False):
    img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if empty:
        d.rectangle([0, 0, w - 1, h - 1], fill=(255, 255, 255, 38))
        return img
    for x in range(w):
        t = x / max(1, w - 1)
        c = shade(color, 0.85 + 0.3 * t)
        d.line([(x, 0), (x, h - 1)], fill=c)
    d.line([(0, 0), (w - 1, 0)], fill=shade(color, 1.4))
    return img


def marker(color, size=15, thick=2, gap=3):
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    m = size - 1
    d.line([(0, 0), (m // 2 - gap, m // 2 - gap)], fill=color, width=thick)
    d.line([(m, 0), (m // 2 + gap, m // 2 - gap)], fill=color, width=thick)
    d.line([(0, m), (m // 2 - gap, m // 2 + gap)], fill=color, width=thick)
    d.line([(m, m), (m // 2 + gap, m // 2 + gap)], fill=color, width=thick)
    return img


# Crosshair gaps in GUI pixels, narrowest first: HudData.CROSSHAIR_GAPS has the same list (the HUD picks
# one by the cone of the next shot); one more picture after them is the plain cross without a gun.
CROSSHAIR_GAPS = [2, 4, 6, 8, 10, 13, 17, 22]
CROSSHAIR_SIZE = 2 * (22 + 5) + 1 + 2   # the widest one, arms and outline included: every picture is this big


def crosshair(gap, arm=5, color=(240, 240, 240, 235), edge=(0, 0, 0, 150)):
    """Four arms around the centre, `gap` pixels out, each `arm` long and one pixel wide with a dark
    outline so it reads on sky and snow alike. No centre: the vanilla crosshair, a single dot in the
    pack (vanilla_crosshair), marks exactly where the shot goes. BetterHud's whole HUD can sit two
    GUI pixels off to one side or the other in a raid; the vanilla dot never does."""
    n = CROSSHAIR_SIZE
    c = n // 2
    img = Image.new('RGBA', (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    boxes = [(c - gap - arm + 1, c, c - gap, c), (c + gap, c, c + gap + arm - 1, c),
             (c, c - gap - arm + 1, c, c - gap), (c, c + gap, c, c + gap + arm - 1)]
    for x0, y0, x1, y1 in boxes:
        d.rectangle([x0 - 1, y0 - 1, x1 + 1, y1 + 1], fill=edge)
    for x0, y0, x1, y1 in boxes:
        d.rectangle([x0, y0, x1, y1], fill=color)
    return pin_corners(img)


def pin_corners(img):
    """BetterHud trims fully transparent rows off an image, so pictures of one size with smaller
    content would sit lower or higher: a barely visible pixel in each corner keeps the whole canvas."""
    w, h = img.size
    for x, y in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
        img.putpixel((x, y), (0, 0, 0, 2))
    return img


def plain_crosshair():
    """The crosshair without a gun: a small plus around the vanilla dot, for doors, boxes and menus."""
    return crosshair(2, arm=3, color=(240, 240, 240, 220), edge=(0, 0, 0, 140))


def vanilla_crosshair():
    """The vanilla crosshair sprite (15x15, drawn centred and inverting what is behind it): one dot in
    the middle. Always exactly where the shot goes, and small enough to sit on iron sights."""
    img = Image.new('RGBA', (15, 15), (0, 0, 0, 0))
    img.putpixel((7, 7), (255, 255, 255, 255))
    return img


def knock_marker(color, size=19, ring=2, inner=3, outer=5):
    """
    The knock-down marker: a ring around a small cross, so it reads apart at a glance from the plain
    hit and headshot crosses and from the bold red kill cross.
    """
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([0, 0, size - 1, size - 1], outline=color, width=ring)
    c = size // 2
    for sx in (-1, 1):
        for sy in (-1, 1):
            d.line([(c + sx * inner, c + sy * inner), (c + sx * outer, c + sy * outer)], fill=color, width=2)
    return img


GLYPHS = {
    'N': ["X...X", "XX..X", "X.X.X", "X..XX", "X...X"],
    'S': [".XXXX", "X....", ".XXX.", "....X", "XXXX."],
    'E': ["XXXXX", "X....", "XXXX.", "X....", "XXXXX"],
    'W': ["X...X", "X...X", "X.X.X", "XX.XX", "X...X"],
}


def glyph(text, color=(235, 230, 215, 255), scale=2):
    width = sum(len(GLYPHS[ch][0]) + 1 for ch in text) - 1
    img = Image.new('RGBA', (width * scale, 5 * scale), (0, 0, 0, 0))
    ox = 0
    for ch in text:
        g = GLYPHS[ch]
        for y, row in enumerate(g):
            for x, v in enumerate(row):
                if v == 'X':
                    for dx in range(scale):
                        for dy in range(scale):
                            img.putpixel(((ox + x) * scale + dx, y * scale + dy), color)
        ox += len(g[0]) + 1
    return img


def tick(h=6, color=(235, 230, 215, 140)):
    img = Image.new('RGBA', (1, h), color)
    return img


def diamond(color, size=9):
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    m = size // 2
    d.polygon([(m, 0), (size - 1, m), (m, size - 1), (0, m)], fill=color)
    d.polygon([(m, 2), (size - 3, m), (m, size - 3), (2, m)], fill=shade(color, 1.35))
    return img


def pointer(color=ACCENT):
    img = Image.new('RGBA', (7, 5), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.polygon([(0, 0), (6, 0), (3, 4)], fill=color)
    return img


# Front-view body figure for the vitals panel: each part is its own full-size layer so BetterHud can
# colour them independently. The character's right side is drawn on the viewer's left.
BODY_W, BODY_H = 22, 40
BODY_PARTS = {
    'head': ('ellipse', 8, 0, 13, 5),
    'chest': ('rect', 7, 7, 14, 13),
    'stomach': ('rect', 7, 14, 14, 19),
    'right_arm': ('rect', 3, 7, 5, 19),
    'left_arm': ('rect', 16, 7, 18, 19),
    'right_leg': ('rect', 7, 21, 10, 37),
    'left_leg': ('rect', 11, 21, 14, 37),
}
BODY_STATES = ['#7E848C', '#FF8A80', '#FF2D2D', '#FFB74D', '#FF6B3D']


def body_part(part, state):
    img = Image.new('RGBA', (BODY_W, BODY_H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    kind, x0, y0, x1, y1 = BODY_PARTS[part]
    fill = rgb(BODY_STATES[state], 210 if state == 0 else 255)
    edge = shade(fill, 0.55)
    if kind == 'ellipse':
        d.ellipse([x0, y0, x1, y1], fill=fill, outline=edge)
    else:
        d.rectangle([x0, y0, x1, y1], fill=fill, outline=edge)
    return img


def damage_arc(index, size=96, radius=38, width=7, span=36):
    """Hit-direction marker: a red arc on a ring around the crosshair, index 0 = front, clockwise in 45s."""
    import math
    img = Image.new('RGBA', (size * 4, size * 4), (0, 0, 0, 0))
    px = img.load()
    c = size * 2
    centre = math.radians(index * 45 - 90)
    for y in range(size * 4):
        for x in range(size * 4):
            dx, dy = (x - c) / 4, (y - c) / 4
            r = math.hypot(dx, dy)
            if abs(r - radius) > width / 2:
                continue
            a = math.atan2(dy, dx) - centre
            a = (a + math.pi) % (2 * math.pi) - math.pi
            t = abs(math.degrees(a)) / (span / 2)
            if t > 1:
                continue
            alpha = (1 - t ** 2) * (1 - (abs(r - radius) / (width / 2)) ** 3)
            px[x, y] = (255, 77, 64, int(235 * alpha))
    return img.resize((size, size), Image.LANCZOS)
