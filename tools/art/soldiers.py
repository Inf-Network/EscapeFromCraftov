"""Soldier skins for the AI (all original pixel art, painted procedurally).

Craftov's AI are zombies under the hood (pathfinding and hit boxes for free), and the zombie model
is the same humanoid as a player. Re-skinning zombie.png and husk.png therefore turns every AI into a
person: patrols wear woodland camouflage with a plate carrier and balaclava, elite units (husks)
wear black with a gas mask. Uses the classic 64x32 humanoid layout (limbs mirrored) on a 64x64 sheet.
"""
import numpy as np
from PIL import Image
from scipy import ndimage

# (u, v) of each box and its size (w, h, d) in the classic humanoid layout.
BOXES = {
    'head': ((0, 0), (8, 8, 8)),
    'hat': ((32, 0), (8, 8, 8)),
    'leg': ((0, 16), (4, 12, 4)),
    'body': ((16, 16), (8, 12, 4)),
    'arm': ((40, 16), (4, 12, 4)),
}


def faces(box):
    """Face rectangles (x, y, w, h) of a box: top, bottom, right, front, left, back."""
    (u, v), (w, h, d) = BOXES[box]
    return {
        'top': (u + d, v, w, d), 'bottom': (u + d + w, v, w, d),
        'right': (u, v + d, d, h), 'front': (u + d, v + d, w, h),
        'left': (u + d + w, v + d, d, h), 'back': (u + d + w + d, v + d, w, h),
    }


def camo(w, h, palette, seed, scale=1.6):
    """Blotchy camouflage: smoothed noise thresholded into layers of the palette."""
    rng = np.random.default_rng(seed)
    out = np.zeros((h, w, 3), np.uint8)
    out[:] = palette[0]
    for i, colour in enumerate(palette[1:]):
        field = ndimage.gaussian_filter(rng.random((h, w)), scale)
        cut = np.quantile(field, 0.62 + 0.06 * i)
        out[field > cut] = colour
    return out


def hexrgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


class Skin:
    def __init__(self):
        self.px = np.zeros((64, 64, 4), np.uint8)

    def fill(self, rect, rgb, alpha=255):
        x, y, w, h = rect
        self.px[y:y + h, x:x + w, :3] = rgb
        self.px[y:y + h, x:x + w, 3] = alpha

    def pattern(self, rect, pattern):
        x, y, w, h = rect
        self.px[y:y + h, x:x + w, :3] = pattern[:h, :w]
        self.px[y:y + h, x:x + w, 3] = 255

    def dot(self, x, y, rgb, alpha=255):
        self.px[y, x, :3] = rgb
        self.px[y, x, 3] = alpha

    def row(self, rect, local_y, rgb, x0=0, x1=None):
        x, y, w, h = rect
        x1 = w if x1 is None else x1
        self.px[y + local_y, x + x0:x + x1, :3] = rgb
        self.px[y + local_y, x + x0:x + x1, 3] = 255

    def shade(self, rect, factor):
        x, y, w, h = rect
        part = self.px[y:y + h, x:x + w, :3].astype(float) * factor
        self.px[y:y + h, x:x + w, :3] = np.clip(part, 0, 255).astype(np.uint8)

    def image(self):
        return Image.fromarray(self.px)


def soldier(palette, vest, strap, balaclava, skin_tone, seed, gas_mask=False):
    s = Skin()
    pal = [hexrgb(c) for c in palette]
    vest, strap, bal, skin = hexrgb(vest), hexrgb(strap), hexrgb(balaclava), hexrgb(skin_tone)
    dark = (30, 30, 28)
    boot = (43, 38, 32)
    glove = (38, 38, 36)
    # ---- clothing: camouflage everywhere on body, arms and legs
    for i, box in enumerate(('body', 'arm', 'leg')):
        for j, (name, rect) in enumerate(faces(box).items()):
            s.pattern(rect, camo(rect[2], rect[3], pal, seed * 31 + i * 7 + j))
    # ---- plate carrier on the torso, pouches, belt
    body = faces('body')
    for side in ('front', 'back'):
        x, y, w, h = body[side]
        s.fill((x, y + 1, w, 8), vest)
        s.row(body[side], 1, strap, 1, 2)
        s.row(body[side], 1, strap, w - 2, w - 1)
        s.shade((x, y + 8, w, 1), 0.75)
    fx, fy, fw, fh = body['front']
    for k in range(3):
        px = fx + 1 + k * 2
        s.fill((px, fy + 5, 2, 3), tuple(int(c * 0.82) for c in vest))
        s.dot(px, fy + 5, tuple(int(c * 0.62) for c in vest))
    s.fill((fx + 1, fy + 2, 2, 2), strap)          # radio pouch
    for side in ('right', 'left'):
        x, y, w, h = body[side]
        s.fill((x, y + 2, w, 6), tuple(int(c * 0.9) for c in vest))
    tx, ty, tw, th = body['top']
    s.fill((tx, ty, tw, th), vest)
    s.fill((tx + 1, ty, 1, th), strap)
    s.fill((tx + tw - 2, ty, 1, th), strap)
    for side in ('front', 'back', 'right', 'left'):
        x, y, w, h = body[side]
        s.fill((x, y + 9, w, 1), dark)               # belt
    s.dot(fx + fw // 2 - 1, fy + 9, (120, 112, 90))
    s.dot(fx + fw // 2, fy + 9, (120, 112, 90))
    # ---- gloves and elbow pads
    arm = faces('arm')
    for side in ('front', 'back', 'right', 'left'):
        x, y, w, h = arm[side]
        s.fill((x, y + 10, w, 2), glove)
        s.fill((x, y + 4, w, 1), tuple(int(c * 0.8) for c in pal[0]))
    s.fill(arm['bottom'], glove)
    # ---- knee pads and boots
    leg = faces('leg')
    lx, ly, lw, lh = leg['front']
    s.fill((lx, ly + 5, lw, 2), dark)
    for side in ('front', 'back', 'right', 'left'):
        x, y, w, h = leg[side]
        s.fill((x, y + 9, w, 3), boot)
        s.row(leg[side], 9, tuple(int(c * 1.3) for c in boot))
    s.fill(leg['bottom'], (30, 26, 22))
    # ---- head
    head = faces('head')
    for name, rect in head.items():
        s.fill(rect, bal)
    s.shade(head['top'], 0.9)
    hx, hy, hw, hh = head['front']
    if gas_mask:
        s.fill((hx + 1, hy + 2, 6, 5), (70, 72, 74))
        for ex in (hx + 1, hx + 5):
            s.fill((ex, hy + 3, 2, 2), (40, 70, 72))
            s.dot(ex, hy + 3, (110, 160, 160))
        s.fill((hx + 3, hy + 5, 2, 2), (52, 54, 56))
    else:
        s.fill((hx + 1, hy + 3, 6, 2), skin)
        for ex in (hx + 1, hx + 5):
            s.dot(ex, hy + 4, (240, 240, 236))
            s.dot(ex + 1, hy + 4, (36, 30, 26))
        s.fill((hx + 1, hy + 3, 6, 1), tuple(int(c * 0.85) for c in skin))
    # ---- outer layer: headset ear cups, and the mask's filter for the elite
    hat = faces('hat')
    for side in ('right', 'left'):
        x, y, w, h = hat[side]
        s.fill((x + 2, y + 3, 4, 4), (34, 35, 33))
        s.dot(x + 3, y + 2, (34, 35, 33))
    tx, ty, tw, th = hat['top']
    s.fill((tx, ty + 3, tw, 1), (34, 35, 33))      # headband
    if gas_mask:
        x, y, w, h = hat['front']
        s.fill((x + 3, y + 5, 2, 2), (46, 48, 50))
        s.dot(x + 3, y + 5, (90, 92, 94))
    return s.image()


def patrol():
    return soldier(['#4A5233', '#6B6142', '#3A3426', '#23241F'], vest='#6E6448', strap='#4B4535',
                   balaclava='#2F3328', skin_tone='#C49A78', seed=3)


def elite():
    return soldier(['#2B2D30', '#3E4146', '#55595F', '#1C1D1F'], vest='#1F2022', strap='#2E3033',
                   balaclava='#18191B', skin_tone='#B88E6E', seed=5, gas_mask=True)
