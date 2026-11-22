"""Container skin and designed menu screens (#6).

The vanilla light-grey containers are replaced with a dark tactical skin in the design palette
(palette.py): generic_54.png for every chest-style menu and inventory.png for the player's own
inventory screen, plus equipment silhouettes for the four armour slots (helmet, body armour, rig in
LEGS, backpack in FEET). Vanilla prints the inventory and crafting labels in a fixed #404040 that
would vanish on a dark window, so those lang strings are emptied and the labels are drawn here.

Designed screens sit on top of the skin: each is one image drawn as a glyph of the craftov:ui font in
the menu title (gui/UiFont.java), so it lines up with the window at every GUI scale. Images cover the
window from its top-left corner; their slot wells stay transparent so items always show.
"""
import os

from PIL import Image, ImageDraw

import palette as P
import pixelfont
from px import rgb, mix

W = 176                       # every chest-style window is this wide
OUTLINE = rgb('#07090C')
HILITE = rgb('#262D37')
SHADOW = rgb('#0A0D10')
WELL_DARK = rgb('#080A0D')
HATCH = rgb('#1E242C')


def window(img, x0, y0, x1, y1, fill=rgb(P.PANEL)):
    """A window body with a 1 px outline and a soft bevel, corners cut like vanilla's."""
    d = ImageDraw.Draw(img)
    d.rectangle([x0 + 1, y0 + 1, x1 - 1, y1 - 1], fill=fill)
    d.line([x0 + 2, y0, x1 - 2, y0], fill=OUTLINE)
    d.line([x0 + 2, y1, x1 - 2, y1], fill=OUTLINE)
    d.line([x0, y0 + 2, x0, y1 - 2], fill=OUTLINE)
    d.line([x1, y0 + 2, x1, y1 - 2], fill=OUTLINE)
    for cx, cy in ((x0 + 1, y0 + 1), (x1 - 1, y0 + 1), (x0 + 1, y1 - 1), (x1 - 1, y1 - 1)):
        d.point((cx, cy), fill=OUTLINE)
    d.line([x0 + 2, y0 + 1, x1 - 2, y0 + 1], fill=HILITE)
    d.line([x0 + 1, y0 + 2, x0 + 1, y1 - 2], fill=HILITE)
    d.line([x0 + 2, y1 - 1, x1 - 2, y1 - 1], fill=SHADOW)
    d.line([x1 - 1, y0 + 2, x1 - 1, y1 - 2], fill=SHADOW)


def well(img, x, y, size=18, fill=rgb(P.BG)):
    """A sunken slot: dark top-left edge, light bottom-right edge, like vanilla's but in the palette."""
    d = ImageDraw.Draw(img)
    d.rectangle([x, y, x + size - 1, y + size - 1], fill=fill)
    d.line([x, y, x + size - 2, y], fill=WELL_DARK)
    d.line([x, y, x, y + size - 2], fill=WELL_DARK)
    d.line([x + 1, y + size - 1, x + size - 1, y + size - 1], fill=rgb(P.LINE))
    d.line([x + size - 1, y + 1, x + size - 1, y + size - 1], fill=rgb(P.LINE))


def hatch(img, x, y, w, h, color=HATCH, step=4):
    """Diagonal stripes: a slot or area that cannot be used."""
    d = ImageDraw.Draw(img)
    for i in range(-h, w, step):
        d.line([x + i, y + h - 1, x + i + h - 1, y], fill=color)
    # Clip what spilled outside by redrawing the surroundings is not needed: callers hatch inside wells.


def hatched_well(img, x, y, size=18):
    well(img, x, y, size)
    tile = Image.new('RGBA', (size - 2, size - 2), (0, 0, 0, 0))
    hatch(tile, 0, 0, size - 2, size - 2)
    img.alpha_composite(tile, (x + 1, y + 1))


def label(img, x, y, text, color=P.MUTED):
    """Pixel-font text with its cell top at y (glyph ink starts one row lower)."""
    pixelfont.draw(img, x, y, text, rgb(color))


def title_marker(img, x, y):
    """The amber tick in front of every window title."""
    ImageDraw.Draw(img).rectangle([x, y, x + 1, y + 7], fill=rgb(P.ACCENT))


# ---------------------------------------------------------------- vanilla container textures

def generic_54():
    """Chest-style menus, 1 to 6 rows: the client draws rows*18+17 px from the top of this texture,
    then the 96 px from y=126 (the player's inventory) right under it."""
    img = Image.new('RGBA', (256, 256), (0, 0, 0, 0))
    window(img, 0, 0, W - 1, 125 + 3)
    # The top part ends at a different row for every size, so its bottom edge is never drawn here;
    # the lower part below starts with a divider that reads as the window's middle seam.
    title_marker(img, 4, 6)
    for r in range(6):
        for c in range(9):
            well(img, 7 + c * 18, 17 + r * 18)
    low = Image.new('RGBA', (W, 96), (0, 0, 0, 0))
    window(low, 0, -4, W - 1, 95)
    d = ImageDraw.Draw(low)
    d.line([1, 0, W - 2, 0], fill=rgb(P.LINE))
    label(low, 8, 1, '随身物品')
    for r in range(3):
        for c in range(9):
            well(low, 7 + c * 18, 13 + r * 18)
    for c in range(9):
        well(low, 7 + c * 18, 71)
    img.paste(low, (0, 126))
    return img


def inventory():
    """The player's inventory screen (E): armour column, model, offhand, the 2x2 crafting grid
    (unused in Craftov, so hatched), rig row, backpack rows and hotbar."""
    img = Image.new('RGBA', (256, 256), (0, 0, 0, 0))
    window(img, 0, 0, W - 1, 165)
    d = ImageDraw.Draw(img)
    for i in range(4):
        well(img, 7, 7 + i * 18)
    # Player model: a darker recess.
    d.rectangle([25, 7, 76, 78], fill=WELL_DARK)
    d.rectangle([26, 8, 75, 77], fill=rgb('#0F1318'))
    well(img, 76, 61)                       # offhand
    for c in range(2):                      # 2x2 crafting, then the result slot
        for r in range(2):
            hatched_well(img, 97 + c * 18, 17 + r * 18)
    hatched_well(img, 153, 27)
    label(img, 97, 4, '合成不可用', P.DIM)  # above the grid: the recipe-book button sits below it
    # Rig row, backpack rows, hotbar; a 2 px tag in the left margin tells them apart.
    for r in range(3):
        for c in range(9):
            well(img, 7 + c * 18, 83 + r * 18)
    for c in range(9):
        well(img, 7 + c * 18, 141)
    d.rectangle([3, 84, 4, 99], fill=rgb(P.MUTED))      # rig
    d.rectangle([3, 102, 4, 135], fill=rgb(P.DIM))      # backpack
    d.rectangle([3, 142, 4, 157], fill=rgb(P.ACCENT))   # hotbar
    return img


def silhouette(kind):
    """16x16 empty-slot hints for the armour column: helmet, body armour, rig and backpack."""
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    c = rgb('#3A424E')
    if kind == 'helmet':
        d.chord([2, 3, 13, 16], 180, 360, fill=c)
        d.rectangle([2, 9, 13, 10], fill=c)
        d.rectangle([1, 10, 3, 12], fill=c)
        d.rectangle([12, 10, 14, 12], fill=c)
    elif kind == 'chestplate':          # plate carrier
        d.polygon([(3, 2), (6, 2), (8, 4), (10, 2), (13, 2), (14, 5), (13, 14), (3, 14), (2, 5)], fill=c)
        d.rectangle([5, 7, 11, 11], fill=rgb('#2A3039'))
    elif kind == 'leggings':            # chest rig: pouches on a harness
        d.line([4, 1, 6, 6], fill=c, width=2)
        d.line([12, 1, 10, 6], fill=c, width=2)
        d.rectangle([2, 6, 14, 13], fill=c)
        for x in (3, 7, 11):
            d.rectangle([x, 7, x + 2, 12], fill=rgb('#2A3039'))
    else:                               # backpack
        d.rounded_rectangle([3, 2, 12, 14], 3, fill=c)
        d.rectangle([5, 8, 10, 12], fill=rgb('#2A3039'))
        d.line([5, 1, 10, 1], fill=c)
    return img


LANG = {'container.inventory': '', 'container.crafting': ''}


# ---------------------------------------------------------------- designed screens

def chest_window(rows):
    """A full chest window image (top part and inventory) as the client lays it out."""
    top = generic_54()
    h = rows * 18 + 17
    img = Image.new('RGBA', (W, h + 96), (0, 0, 0, 0))
    img.paste(top.crop((0, 0, W, h)), (0, 0))
    img.paste(top.crop((0, 126, W, 222)), (0, h))
    return img


def contrast_report():
    """Title and label colours against the window body: WCAG ratios, all must be at least 4.5."""
    out = {}
    for name in ('TEXT', 'MUTED', 'ACCENT', 'OK', 'DANGER', 'INFO'):
        out[name] = round(P.contrast(getattr(P, name), P.PANEL), 2)
    return out


def slot_xy(index):
    """Top-left of the 18x18 cell of chest slot `index`, in window coordinates."""
    return 7 + (index % 9) * 18, 17 + (index // 9) * 18


# Screen id -> function returning (image, pixels it reaches left of the window, pixels above it).
SCREENS = {}
