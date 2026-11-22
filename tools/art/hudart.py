"""Images used by the BetterHud layout, in the design palette (palette.py)."""
from PIL import Image, ImageDraw
import palette as PAL
from px import rgb, shade

BG = rgb(PAL.PANEL, 190)
ACCENT = rgb(PAL.ACCENT)


def panel(w, h, accent=ACCENT, side='left'):
    """A translucent panel with a hairline top edge and the amber tick on the outer side."""
    img = Image.new('RGBA', (w, h), BG)
    d = ImageDraw.Draw(img)
    d.line([(0, 0), (w - 1, 0)], fill=rgb(PAL.LINE, 230))
    if side == 'left':
        d.rectangle([0, 1, 1, h - 1], fill=accent)
    else:
        d.rectangle([w - 2, 1, w - 1, h - 1], fill=accent)
    return img


def band(w, h, accent=ACCENT):
    """The alert banner: darker, with amber ticks at both ends."""
    img = Image.new('RGBA', (w, h), rgb(PAL.BG, 215))
    d = ImageDraw.Draw(img)
    d.line([(0, 0), (w - 1, 0)], fill=accent)
    d.line([(0, h - 1), (w - 1, h - 1)], fill=rgb(PAL.LINE, 230))
    return img


def icon(kind, size=9):
    """9x9 status icons for the vitals panel: bleeding, heavy bleeding, fracture, painkiller, and the
    tiny helmet and vest marks in front of the armour bars."""
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    red, dark, amber, ok = rgb(PAL.DANGER), rgb('#A3242A'), rgb(PAL.ACCENT), rgb(PAL.OK)
    if kind == 'bleed':
        d.polygon([(4, 0), (7, 5), (4, 8), (1, 5)], fill=red)
        d.ellipse([1, 3, 7, 8], fill=red)
    elif kind == 'heavy':
        for x in (0, 4):
            d.polygon([(x + 2, 0), (x + 4, 4), (x + 2, 6), (x, 4)], fill=dark)
            d.ellipse([x, 3, x + 4, 7], fill=red)
    elif kind == 'fracture':
        d.line([(1, 7), (3, 4), (5, 5), (7, 1)], fill=amber, width=2)
    elif kind == 'pain':
        d.rounded_rectangle([0, 2, 8, 6], 2, fill=ok)
        d.rectangle([4, 2, 8, 6], fill=rgb(PAL.TEXT))
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
BODY_W, BODY_H = 16, 34
BODY_PARTS = {
    'head': ('ellipse', 5, 0, 10, 5),
    'chest': ('rect', 5, 7, 10, 12),
    'stomach': ('rect', 5, 13, 10, 17),
    'right_arm': ('rect', 2, 7, 3, 17),
    'left_arm': ('rect', 12, 7, 13, 17),
    'right_leg': ('rect', 5, 19, 7, 33),
    'left_leg': ('rect', 8, 19, 10, 33),
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
    # BetterHud crops transparent borders off single images, which would slide every part to the
    # same corner; a barely-there pixel in two corners keeps the layer full size (the HUD shader
    # discards anything under 10% alpha, so they never show).
    img.putpixel((0, 0), (0, 0, 0, 1))
    img.putpixel((BODY_W - 1, BODY_H - 1), (0, 0, 0, 1))
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
