"""Tiny pixel-art toolkit on top of PIL: shapes, outlines and soft shading."""
from PIL import Image, ImageDraw
import numpy as np


def rgb(h, a=255):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def mix(c1, c2, t):
    return tuple(int(round(c1[i] * (1 - t) + c2[i] * t)) for i in range(4))


def shade(c, f):
    """f>1 lightens, f<1 darkens."""
    if f >= 1:
        return mix(c, (255, 255, 255, c[3]), min(1, f - 1))
    return mix(c, (0, 0, 0, c[3]), min(1, 1 - f))


class Canvas:
    def __init__(self, w=32, h=32):
        self.img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.img)
        self.w, self.h = w, h

    def rect(self, x0, y0, x1, y1, c):
        self.d.rectangle([x0, y0, x1, y1], fill=c)

    def box(self, x0, y0, x1, y1, c, light=1.25, dark=0.7):
        """Filled rectangle with a highlight on the top/left edge and shadow on bottom/right."""
        self.rect(x0, y0, x1, y1, c)
        self.d.line([x0, y0, x1, y0], fill=shade(c, light))
        self.d.line([x0, y0, x0, y1], fill=shade(c, light * 0.95 if light > 1 else light))
        self.d.line([x0, y1, x1, y1], fill=shade(c, dark))
        self.d.line([x1, y0, x1, y1], fill=shade(c, dark))

    def ellipse(self, x0, y0, x1, y1, c):
        self.d.ellipse([x0, y0, x1, y1], fill=c)

    def poly(self, pts, c):
        self.d.polygon(pts, fill=c)

    def line(self, pts, c, width=1):
        self.d.line(pts, fill=c, width=width)

    def px(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.img.putpixel((x, y), c)

    def outline(self, c=(24, 24, 28, 255)):
        a = np.array(self.img)
        alpha = a[:, :, 3] > 0
        grown = np.zeros_like(alpha)
        grown[1:, :] |= alpha[:-1, :]
        grown[:-1, :] |= alpha[1:, :]
        grown[:, 1:] |= alpha[:, :-1]
        grown[:, :-1] |= alpha[:, 1:]
        edge = grown & ~alpha
        a[edge] = c
        self.img = Image.fromarray(a)
        self.d = ImageDraw.Draw(self.img)
        return self

    def save(self, path):
        self.img.save(path)


def gradient_bg(size, color, strength=0.55):
    """Rarity background: soft vertical gradient with a bright bottom bar."""
    w, h = size
    img = Image.new('RGBA', size, (0, 0, 0, 0))
    base = np.zeros((h, w, 4), dtype=np.float32)
    for y in range(h):
        t = y / (h - 1)
        a = 40 + 120 * t ** 1.6
        base[y, :, 0] = color[0] * (0.45 + 0.55 * t)
        base[y, :, 1] = color[1] * (0.45 + 0.55 * t)
        base[y, :, 2] = color[2] * (0.45 + 0.55 * t)
        base[y, :, 3] = a * strength
    base[h - 2:, :, :3] = color[:3]
    base[h - 2:, :, 3] = 235
    base[0, :, 3] = 0
    img = Image.fromarray(base.clip(0, 255).astype(np.uint8))
    return img
