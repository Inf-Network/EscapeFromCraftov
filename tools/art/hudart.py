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
