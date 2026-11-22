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


def cells(r0, c0, r1, c1):
    """Pixel box (x0, y0, x1, y1) covering the cells of rows r0..r1 and columns c0..c1."""
    return 7 + c0 * 18, 17 + r0 * 18, 7 + (c1 + 1) * 18 - 1, 17 + (r1 + 1) * 18 - 1


def slots(r0, c0, r1, c1):
    return [r * 9 + c for r in range(r0, r1 + 1) for c in range(c0, c1 + 1)]


class Screen:
    """The top part of a chest window (title band and slot area, rows*18+17 px), drawn from scratch:
    wells only where the menu puts something, label bands, big buttons and frames elsewhere. The
    client draws it over the plain skin and under the items."""

    def __init__(self, rows):
        self.rows = rows
        self.img = Image.new('RGBA', (W, rows * 18 + 17), (0, 0, 0, 0))
        window(self.img, 0, 0, W - 1, rows * 18 + 17 + 3)
        title_marker(self.img, 4, 6)
        self.d = ImageDraw.Draw(self.img)

    def wells(self, indices):
        for i in indices:
            well(self.img, *slot_xy(i))
        return self

    def band(self, r, c0, c1, text, color=P.MUTED, tick=P.ACCENT):
        """A label strip across a row of cells that holds no items: a tick, the text, a hairline."""
        x0, y0, x1, y1 = cells(r, c0, r, c1)
        self.d.rectangle([x0, y0 + 2, x1, y1 - 2], fill=rgb(P.RAISED))
        self.d.line([x0, y1 - 2, x1, y1 - 2], fill=rgb(P.LINE))
        self.d.rectangle([x0, y0 + 4, x0 + 1, y1 - 5], fill=rgb(tick))
        label(self.img, x0 + 5, y0 + 4, text, color)
        return self

    def big(self, r0, c0, r1, c1, lines, icon=None, accent=True, color=P.ACCENT):
        """A button spanning several cells; the menu fills those slots with one invisible item each,
        all running the same action."""
        x0, y0, x1, y1 = cells(r0, c0, r1, c1)
        edge = rgb(color) if accent else rgb(P.LINE)
        self.d.rectangle([x0, y0, x1, y1], fill=WELL_DARK)
        self.d.rectangle([x0 + 1, y0 + 1, x1 - 1, y1 - 1], fill=mix(rgb(P.RAISED), edge, 0.12))
        self.d.rectangle([x0 + 1, y0 + 1, x1 - 1, y1 - 1], outline=edge)
        self.d.line([x0 + 2, y0 + 2, x1 - 2, y0 + 2], fill=mix(rgb(P.RAISED), rgb('#FFFFFF'), 0.08))
        block_h = (icon.height + 3 if icon is not None else 0) + len(lines) * 11
        y = y0 + (y1 - y0 + 1 - block_h) // 2
        if icon is not None:
            self.img.alpha_composite(icon, ((x0 + x1 + 1 - icon.width) // 2, y))
            y += icon.height + 3
        for line in lines:
            label(self.img, (x0 + x1 + 1 - pixelfont.width(line)) // 2, y - 1, line, color if accent else P.TEXT)
            y += 11
        return self

    def toolbar(self, r):
        """A raised strip behind a row of buttons, set off from the slots above by a divider."""
        x0, y0, x1, y1 = cells(r, 0, r, 8)
        self.d.rectangle([3, y0 - 1, W - 4, y1 + 1], fill=rgb(P.RAISED))
        self.d.line([3, y0 - 1, W - 4, y0 - 1], fill=rgb(P.LINE))
        return self

    def group(self, r0, c0, r1, c1):
        """A faint frame round a block of cells (drawn in the gutter just outside them)."""
        x0, y0, x1, y1 = cells(r0, c0, r1, c1)
        self.d.rectangle([x0 - 2, y0 - 2, x1 + 2, y1 + 2], outline=rgb(P.LINE))
        return self

    def hazard(self, x0, y0, x1, y1):
        """Amber and black warning stripes (the loot screen's title band)."""
        tile = Image.new('RGBA', (x1 - x0 + 1, y1 - y0 + 1), rgb('#1A1508'))
        td = ImageDraw.Draw(tile)
        for i in range(-tile.height, tile.width, 6):
            td.polygon([(i, tile.height), (i + 3, tile.height), (i + 3 + tile.height, 0), (i + tile.height, 0)],
                       fill=mix(rgb(P.ACCENT), rgb(P.PANEL), 0.45))
        self.img.alpha_composite(tile, (x0, y0))
        return self

    def done(self):
        return self.img, 0, 0


def mini(kind):
    """Small pictograms drawn into screens (not items)."""
    img = Image.new('RGBA', (14, 14), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    a = rgb(P.ACCENT)
    if kind == 'deploy':
        d.ellipse([2, 2, 11, 11], outline=a)
        d.line([7, 0, 7, 13], fill=a)
        d.line([0, 7, 13, 7], fill=a)
        d.rectangle([6, 6, 7, 7], fill=rgb(P.TEXT))
    return img


def main_screen():
    """Tactical terminal, four sections under labelled bands: operations (the big deploy button),
    preparation, progress and social, with help, profile and close in the bottom bar."""
    s = Screen(6)
    s.band(0, 0, 1, '行动').band(0, 2, 4, '备战').band(0, 5, 6, '成长').band(0, 7, 8, '社交')
    s.big(1, 0, 3, 1, ['开始', '行动'], mini('deploy'))
    s.wells([36, 37]).wells(slots(1, 2, 2, 4) + [29, 30]).wells(slots(1, 5, 3, 6)).wells([16, 17, 25])
    s.toolbar(5).wells([45, 49, 53])
    return s.done()


def deploy_screen(queued=False):
    """Deploy: the map row under its band, then a labelled row each for difficulty and squad (the
    labels take the first two cells), and the bottom row with back, party, the start button and the
    gear value. The slots are the ones bots and players already know (insurance 34, start 40)."""
    s = Screen(5)
    s.band(0, 0, 8, '选择地图').wells(slots(1, 1, 1, 7))
    s.band(2, 0, 1, '难度').wells([20, 22, 24])
    s.band(3, 0, 1, '编队').wells([29, 30]).wells([32, 33, 34])
    s.toolbar(4).wells([36, 38, 42])
    if queued:
        s.big(4, 3, 4, 5, ['取消匹配'], color=P.DANGER)
    else:
        s.big(4, 3, 4, 5, ['开始匹配'])
    return s.done()


def stash_screen():
    """Stash: five rows of stored items and the control bar."""
    s = Screen(6)
    s.wells(slots(0, 0, 4, 8))
    s.toolbar(5).wells(slots(5, 0, 5, 8))
    return s.done()


def trader_screen():
    """Trader: goods above, the category tabs as a tab strip below."""
    s = Screen(6)
    s.wells(slots(0, 0, 4, 8))
    s.toolbar(5)
    for i in slots(5, 0, 5, 8):
        x, y = slot_xy(i)
        s.d.rectangle([x, y, x + 17, y + 17], fill=WELL_DARK)
        s.d.rectangle([x + 1, y, x + 16, y + 16], fill=rgb(P.PANEL))
        s.d.line([x + 1, y, x + 16, y], fill=rgb(P.LINE))
    return s.done()


def loot_screen(rows):
    def make():
        s = Screen(rows)
        s.wells(slots(0, 0, rows - 1, 8))
        s.hazard(W - 44, 4, W - 6, 13)
        return s.done()
    return make


def health_screen():
    """Health: the body parts laid out as a figure, status and help on the right, meds below."""
    s = Screen(6)
    # A silhouette behind the part slots (head, arms, torso, legs), so the grid reads as a body.
    body = rgb('#1D252E')
    hx, hy = slot_xy(4)
    s.d.ellipse([hx - 3, hy - 2, hx + 20, hy + 19], fill=body)
    x0, y0, _, _ = cells(1, 3, 1, 3)
    _, _, x1, y1 = cells(2, 5, 2, 5)
    s.d.rounded_rectangle([x0 - 3, y0 - 2, x1 + 3, y1 + 2], 5, fill=body)
    for c in (3, 5):
        lx0, ly0, lx1, ly1 = cells(3, c, 4, c)
        s.d.rounded_rectangle([lx0 - 2, ly0 - 2, lx1 + 2, ly1 + 1], 4, fill=body)
    s.wells([4, 12, 13, 14, 21, 22, 23, 30, 32, 39, 41])
    s.group(0, 7, 1, 7).wells([7, 16])
    s.toolbar(5).wells(slots(5, 0, 5, 8))
    return s.done()


def gunsmith_screen():
    """Gunsmith: carried guns on top, the chosen gun on a display plate with its five attachment
    slots, and the carried attachments at the bottom."""
    s = Screen(6)
    s.wells(slots(0, 0, 0, 8))
    s.band(1, 0, 8, '已选枪械 · 配件位')
    x0, y0, x1, y1 = cells(2, 2, 3, 6)
    s.d.rectangle([x0 - 2, y0 - 2, x1 + 2, y1 + 2], fill=rgb('#11151A'), outline=rgb(P.ACCENT))
    s.wells([22]).wells(slots(3, 2, 3, 6))
    s.band(4, 0, 8, '身上的配件 · 点击安装')
    s.wells(slots(5, 0, 5, 8))
    return s.done()


# Screen id -> function returning (image, pixels it reaches left of the window, pixels above it).
SCREENS = {
    'main': main_screen,
    'deploy': deploy_screen,
    'deploy_queued': lambda: deploy_screen(True),
    'stash': stash_screen,
    'trader': trader_screen,
    **{'loot_%d' % r: loot_screen(r) for r in range(1, 7)},
    'health': health_screen,
    'gunsmith': gunsmith_screen,
}


# ---------------------------------------------------------------- tooltips

def tooltip_background():
    """Tooltip body (nine-slice, 100x100, border 9 like vanilla's): a near-opaque dark panel."""
    img = Image.new('RGBA', (100, 100), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([8, 9, 91, 90], fill=rgb('#0F1317', 246))
    d.rectangle([9, 8, 90, 91], fill=rgb('#0F1317', 246))
    return img


def tooltip_frame(color):
    """Tooltip frame (nine-slice, border 10): a hairline in `color`, brightest along the top, with a
    short solid bar at the top-left corner like the tick in front of every window title."""
    img = Image.new('RGBA', (100, 100), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    c = rgb(color)
    side, bottom = mix(c, rgb(P.PANEL), 0.45), mix(c, rgb(P.PANEL), 0.65)
    d.line([10, 9, 89, 9], fill=c)
    d.line([9, 10, 9, 89], fill=side)
    d.line([90, 10, 90, 89], fill=side)
    d.line([10, 90, 89, 90], fill=bottom)
    d.rectangle([9, 9, 10, 15], fill=c)
    return img
