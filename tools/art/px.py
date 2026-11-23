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


TILE = 36          # a GUI item texture: the whole 18x18 slot at 2 texture pixels per GUI pixel
TILE_ICON = 2      # where the 32x32 icon sits on the tile (its 16 GUI pixels, as vanilla draws an item)
TILE_SCALE = TILE / 32  # the GUI model's scale, so the 32 px icon keeps its size and the tile fills the well


def rarity_tile(color, rank, backdrop=(13, 16, 20, 255)):
    """Rarity tile behind an item in a slot (#10): a solid, dark tint of the rarity colour over the
    whole 18x18 well, lighter towards the bottom, with a 1 GUI px bar of the full colour along the
    bottom. Purple and above (rank >= 3) are framed in their colour; gold and red (rank >= 4) also
    get bright corners and a brighter fill, so the rarest finds stand out from purple at a glance.
    Every feature is a whole GUI pixel (2 texture pixels), so nothing shimmers at any GUI scale."""
    img = np.zeros((TILE, TILE, 4), dtype=np.float32)
    keep = {0: 0.80, 1: 0.74, 2: 0.70, 3: 0.66, 4: 0.58, 5: 0.56}[rank]
    for y in range(TILE):
        t = y / (TILE - 1)
        k = keep + 0.08 * (1 - t) - 0.04   # top a little darker than the bottom
        img[y, :, :3] = [color[i] * (1 - k) + backdrop[i] * k for i in range(3)]
        img[y, :, 3] = 208
    img[TILE - 2:, :, :3] = color[:3]
    img[TILE - 2:, :, 3] = 235
    if rank >= 3:
        edge = [color[i] * 0.85 + backdrop[i] * 0.15 for i in range(3)]
        for sl in (np.s_[0:2, :], np.s_[:, 0:2], np.s_[:, TILE - 2:]):
            img[sl][..., :3] = edge
            img[sl][..., 3] = 225
    if rank >= 4:
        bright = [min(255, color[i] * 0.6 + 255 * 0.4) for i in range(3)]
        n = 8
        for ys, xs in ((np.s_[0:2], np.s_[0:n]), (np.s_[0:n], np.s_[0:2]), (np.s_[0:2], np.s_[TILE - n:]),
                       (np.s_[0:n], np.s_[TILE - 2:])):
            img[ys, xs, :3] = bright
            img[ys, xs, 3] = 245
    return Image.fromarray(img.clip(0, 255).astype(np.uint8))
