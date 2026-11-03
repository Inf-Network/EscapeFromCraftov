"""Soldier skin (replaces the zombie texture) and armor layers worn on the body."""
import random
from PIL import Image
from px import rgb, shade


def box_faces(u, v, w, h, d):
    """Texture rectangles of a cuboid in the vanilla entity UV layout."""
    return {
        'top': (u + d, v, w, d), 'bottom': (u + d + w, v, w, d),
        'right': (u, v + d, d, h), 'front': (u + d, v + d, w, h),
        'left': (u + d + w, v + d, d, h), 'back': (u + 2 * d + w, v + d, w, h),
    }


def fill(img, rect, color_fn):
    x, y, w, h = rect
    for i in range(w):
        for j in range(h):
            img.putpixel((x + i, y + j), color_fn(i, j, w, h))


def camo(base, dark, light, seed):
    rnd = random.Random(seed)
    blobs = [(rnd.randint(0, 16), rnd.randint(0, 16), rnd.randint(1, 3), rnd.choice((dark, light))) for _ in range(9)]

    def fn(i, j, w, h):
        for bx, by, r, col in blobs:
            if (i - bx % max(1, w)) ** 2 + (j - by % max(1, h)) ** 2 <= r * r:
                return col
        return base if (i * 7 + j * 3) % 11 else shade(base, 0.92)
    return fn


def soldier_skin(seed=3):
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    base, dark, light = rgb('#4F5A3A'), rgb('#343C26'), rgb('#6E7650')
    skin = rgb('#B98B6A')
    mask = rgb('#25272B')
    # Head: balaclava with an eye slit.
    for name, rect in box_faces(0, 0, 8, 8, 8).items():
        fill(img, rect, lambda i, j, w, h: shade(mask, 1.0 + ((i + j) % 3) * 0.04))
    fx, fy, _, _ = box_faces(0, 0, 8, 8, 8)['front']
    for i in range(1, 7):
        img.putpixel((fx + i, fy + 3), skin)
        img.putpixel((fx + i, fy + 4), skin)
    img.putpixel((fx + 2, fy + 3), rgb('#F0F0F0'))
    img.putpixel((fx + 2, fy + 4), rgb('#1A1A1A'))
    img.putpixel((fx + 5, fy + 3), rgb('#F0F0F0'))
    img.putpixel((fx + 5, fy + 4), rgb('#1A1A1A'))
    # Hat overlay: a soft cap brim.
    hx, hy, hw, hh = box_faces(32, 0, 8, 8, 8)['top']
    fill(img, (hx, hy, hw, hh), camo(base, dark, light, seed))
    for name in ('front', 'left', 'right', 'back'):
        x, y, w, h = box_faces(32, 0, 8, 8, 8)[name]
        fill(img, (x, y, w, 2), camo(base, dark, light, seed + 1))
    # Body + arms + legs camouflage.
    parts = [(16, 16, 8, 12, 4), (40, 16, 4, 12, 4), (0, 16, 4, 12, 4), (32, 48, 4, 12, 4), (16, 48, 4, 12, 4)]
    for k, (u, v, w, h, d) in enumerate(parts):
        for name, rect in box_faces(u, v, w, h, d).items():
            fill(img, rect, camo(base, dark, light, seed + 10 + k))
    # Plate carrier on the chest/back.
    vest = rgb('#3B3A30')
    for name in ('front', 'back'):
        x, y, w, h = box_faces(16, 16, 8, 12, 4)[name]
        fill(img, (x + 1, y + 1, w - 2, 7), lambda i, j, w2, h2: shade(vest, 1.1 if j == 0 else 1.0))
        for p in range(3):
            fill(img, (x + 1 + p * 2, y + 5, 2, 3), lambda i, j, w2, h2: shade(vest, 0.8 if i == 1 else 0.9))
    # Belt.
    for name in ('front', 'back', 'left', 'right'):
        x, y, w, h = box_faces(16, 16, 8, 12, 4)[name]
        fill(img, (x, y + 10, w, 1), lambda *a: rgb('#2A241C'))
    # Gloves and boots.
    for u, v in ((40, 16), (32, 48)):
        for name in ('front', 'back', 'left', 'right'):
            x, y, w, h = box_faces(u, v, 4, 12, 4)[name]
            fill(img, (x, y + 10, w, 2), lambda *a: rgb('#1C1C1E'))
        x, y, w, h = box_faces(u, v, 4, 12, 4)['bottom']
        fill(img, (x, y, w, h), lambda *a: rgb('#1C1C1E'))
    for u, v in ((0, 16), (16, 48)):
        for name in ('front', 'back', 'left', 'right'):
            x, y, w, h = box_faces(u, v, 4, 12, 4)[name]
            fill(img, (x, y + 9, w, 3), lambda i, j, *a: rgb('#2B2420') if j < 2 else rgb('#151312'))
        x, y, w, h = box_faces(u, v, 4, 12, 4)['bottom']
        fill(img, (x, y, w, h), lambda *a: rgb('#151312'))
    return img


def helmet_layer(color, visor=None, full=False):
    img = Image.new('RGBA', (64, 32), (0, 0, 0, 0))
    faces = box_faces(0, 0, 8, 8, 8)
    for name, rect in faces.items():
        if name == 'bottom':
            continue
        x, y, w, h = rect
        rows = h if name == 'top' else (h if full else 4)
        for i in range(w):
            for j in range(rows):
                if name == 'front' and not full and j >= 3:
                    continue
                img.putpixel((x + i, y + j), shade(color, 1.12 if j == 0 else 1.0 - (i % 3 == 0) * 0.05))
        if name == 'front' and visor:
            for i in range(1, 7):
                img.putpixel((x + i, y + 3), visor)
                img.putpixel((x + i, y + 4), shade(visor, 1.3))
    return img


def vest_layer(color, pouch, heavy=False):
    img = Image.new('RGBA', (64, 32), (0, 0, 0, 0))
    faces = box_faces(16, 16, 8, 12, 4)
    for name, rect in faces.items():
        x, y, w, h = rect
        rows = 9 if name in ('front', 'back', 'left', 'right') else h
        for i in range(w):
            for j in range(min(h, rows)):
                img.putpixel((x + i, y + j), shade(color, 1.1 if j == 0 else 1.0))
        if name == 'front':
            for p in range(3):
                for i in range(2):
                    for j in range(3):
                        img.putpixel((x + 1 + p * 2 + i, y + 5 + j), shade(pouch, 0.85 if i else 1.0))
    if heavy:
        arm = box_faces(40, 16, 4, 12, 4)
        for name in ('front', 'back', 'left', 'right', 'top'):
            x, y, w, h = arm[name]
            for i in range(w):
                for j in range(min(h, 4)):
                    img.putpixel((x + i, y + j), shade(color, 0.95))
    return img


EQUIPMENT = {
    'helmet_soft': lambda: helmet_layer(rgb('#34495E')),
    'helmet_light': lambda: helmet_layer(rgb('#5E6B3C')),
    'helmet_heavy': lambda: helmet_layer(rgb('#3E4D33'), visor=rgb('#22313F')),
    'helmet_full': lambda: helmet_layer(rgb('#1E1F22'), visor=rgb('#C0A040'), full=True),
    'vest_soft': lambda: vest_layer(rgb('#2C3E50'), rgb('#22313F')),
    'vest_tactical': lambda: vest_layer(rgb('#5E6B3C'), rgb('#4A5530')),
    'vest_heavy': lambda: vest_layer(rgb('#2E3236'), rgb('#3B3F45'), heavy=True),
}
