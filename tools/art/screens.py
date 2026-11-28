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


# ---------------------------------------------------------------- carry zones (#10)

ZONES = {'rig': P.ZONE_RIG, 'pack': P.ZONE_PACK}
SILHOUETTE = rgb('#252B33')     # empty-slot hints drawn into the wells


def zone_well(img, x, y, zone):
    """A carry cell: a well tinted in its zone's hue with a hairline of it along the top, so the rig
    row and the backpack rows read as two areas even when empty."""
    c = rgb(ZONES[zone])
    well(img, x, y, fill=mix(rgb(P.BG), c, 0.16))
    ImageDraw.Draw(img).line([x + 1, y + 1, x + 16, y + 1], fill=mix(rgb(P.BG), c, 0.45))


# 3x5 digits for the key caps under the hotbar.
DIGITS = {
    '1': ['010', '110', '010', '010', '111'], '2': ['110', '001', '010', '100', '111'],
    '3': ['110', '001', '010', '001', '110'], '4': ['101', '101', '111', '001', '001'],
    '5': ['111', '100', '110', '001', '110'], '6': ['011', '100', '110', '101', '010'],
    '7': ['111', '001', '010', '010', '010'], '8': ['010', '101', '010', '101', '010'],
    '9': ['010', '101', '011', '001', '110'],
}


def digit(img, x, y, ch, color):
    for r, row in enumerate(DIGITS[ch]):
        for c, bit in enumerate(row):
            if bit == '1':
                img.putpixel((x + c, y + r), color)


def hint(img, x, y, kind):
    """A faint 16x16 picture inside an empty hotbar well, saying what the key is for: guns on 1-2,
    the knife on 3, the pockets on 4-8 (anything small), the terminal on 9 (CarryService rules)."""
    t = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(t)
    c = SILHOUETTE
    if kind == 'gun':
        d.polygon([(1, 8), (4, 6), (4, 9), (2, 10)], fill=c)
        d.rectangle([4, 5, 11, 7], fill=c)
        d.rectangle([11, 6, 15, 6], fill=c)
        d.polygon([(6, 8), (8, 8), (8, 12), (6, 12)], fill=c)
        d.rectangle([9, 8, 9, 9], fill=c)
    elif kind == 'knife':
        d.polygon([(3, 13), (11, 4), (13, 3), (12, 6), (5, 14)], fill=c)
        d.rectangle([2, 12, 4, 14], fill=c)
    elif kind == 'pocket':                  # a pouch under its flap, with a button
        d.rectangle([3, 5, 12, 13], fill=c)
        d.polygon([(2, 3), (13, 3), (13, 6), (8, 9), (7, 9), (2, 6)], fill=mix(c, rgb(P.BG), 0.55))
        d.rectangle([7, 7, 8, 8], fill=c)
    else:                                   # terminal
        d.rectangle([4, 2, 11, 13], fill=c)
        d.rectangle([5, 3, 10, 9], fill=mix(c, rgb(P.BG), 0.5))
    img.alpha_composite(t, (x + 1, y + 1))


HOTBAR_HINTS = ['gun', 'gun', 'knife', 'pocket', 'pocket', 'pocket', 'pocket', 'pocket', 'terminal']


def carry_rows(img, top, hotbar_y, keycaps_y):
    """The rig row, the two backpack rows and the hotbar of the player's own inventory, as laid out in
    both the inventory screen and the lower half of every chest window: zone-tinted wells, a tag in the
    zone's colour in the left margin, hints in the empty hotbar wells and the key caps under them."""
    d = ImageDraw.Draw(img)
    for c in range(9):
        zone_well(img, 7 + c * 18, top, 'rig')
        zone_well(img, 7 + c * 18, top + 18, 'pack')
        zone_well(img, 7 + c * 18, top + 36, 'pack')
        well(img, 7 + c * 18, hotbar_y)
        hint(img, 7 + c * 18, hotbar_y, HOTBAR_HINTS[c])
        digit(img, 7 + c * 18 + 8, keycaps_y, str(c + 1), rgb(P.DIM) if c not in (2, 8) else mix(rgb(P.DIM), rgb(P.BG), 0.4))
    d.rectangle([3, top + 1, 4, top + 16], fill=rgb(P.ZONE_RIG))
    d.rectangle([3, top + 19, 4, top + 52], fill=rgb(P.ZONE_PACK))
    d.rectangle([3, hotbar_y + 1, 4, hotbar_y + 16], fill=rgb(P.ACCENT))


def legend(img, x, y, vertical=False):
    """Swatches naming the two carry zones, side by side or one under the other."""
    d = ImageDraw.Draw(img)
    for zone, text in (('rig', '胸挂'), ('pack', '背包')):
        d.rectangle([x, y + 2, x + 4, y + 7], fill=rgb(ZONES[zone]))
        label(img, x + 7, y - 1, text)
        if vertical:
            y += 11
        else:
            x += 7 + pixelfont.width(text) + 6


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
    legend(low, 100, 2)
    carry_rows(low, 13, 71, 90)
    img.paste(low, (0, 126))
    return img


INFO_CELLS = {0: (153, 27), 1: (97, 17), 2: (115, 17), 3: (97, 35), 4: (115, 35)}
"""Inventory-screen raw slot -> well: the crafting result and the 2x2 grid hold the info cells (#10,
gui/CarryInfo.java): protection, load, value, secure container, tidy."""


def inventory():
    """The player's inventory screen (E): armour column (helmet, body armour, rig, backpack), model,
    the five info cells where the crafting grid was, the rig row, the backpack rows and the hotbar.
    The offhand is never used, so its well is left out; the recipe-book button is drawn invisible
    (build.py) and nothing here sits under it (x 104..123, y 61..78)."""
    img = Image.new('RGBA', (256, 256), (0, 0, 0, 0))
    window(img, 0, 0, W - 1, 165)
    d = ImageDraw.Draw(img)
    for i in range(4):
        well(img, 7, 7 + i * 18)
    # Player model: a darker recess.
    d.rectangle([25, 7, 76, 78], fill=WELL_DARK)
    d.rectangle([26, 8, 75, 77], fill=rgb('#0F1318'))
    # The info cells on a raised plate under a label; the result cell sits apart like vanilla's.
    d.rectangle([94, 14, 135, 54], fill=rgb(P.RAISED), outline=rgb(P.LINE))
    d.rectangle([150, 24, 173, 47], fill=rgb(P.RAISED), outline=rgb(P.LINE))
    label(img, 95, 2, '随身概况')
    for x, y in INFO_CELLS.values():
        well(img, x, y)
    legend(img, 140, 57, vertical=True)
    carry_rows(img, 83, 141, 159)
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


# mount.onboard: vanilla's "Press Shift to Dismount" over the hotbar whenever the player is seated,
# which a slide is (SlideService sits the player on an invisible seat) and where Shift does nothing.
LANG = {'container.inventory': '', 'container.crafting': '', 'mount.onboard': ''}


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
    s.wells([36, 37]).wells(slots(1, 2, 2, 4) + [29, 30]).wells(slots(1, 5, 3, 6)).wells([16, 17, 25, 26])
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


STASH_GEAR = ((15, 'helmet'), (24, 'chestplate'), (33, 'leggings'), (42, 'boots'))
"""Stash loadout panel, worn column: helmet, body armour, rig and backpack (StashMenu.GEAR)."""
STASH_KEYS = ((16, 'gun'), (17, 'gun'), (25, 'knife'), (26, 'pocket'), (34, 'pocket'), (35, 'pocket'),
              (43, 'pocket'), (44, 'pocket'))
"""Stash loadout panel, hotbar keys 1-8 two to a row (StashMenu.KEYS)."""


def stash_screen(tab):
    """Stash (#10): nine category tabs along the top with the chosen one lit (one screen per tab, so
    the tab shows even under the pointer), four rows of stored items in six columns, the loadout panel
    on the right (#185: worn helmet, vest, rig and backpack, then keys 1-8 two to a row, each empty
    well showing what goes there), and the control bar (pages, sort, multi-select, sell, capacity,
    mail, secure container, more room)."""
    def make():
        s = Screen(6)
        s.toolbar(0)
        for i in slots(0, 0, 0, 8):
            x, y = slot_xy(i)
            on = i == tab
            s.d.rectangle([x, y, x + 17, y + 17], fill=WELL_DARK)
            s.d.rectangle([x + 1, y + 1, x + 16, y + 17], fill=mix(rgb(P.RAISED), rgb(P.ACCENT), 0.14) if on else rgb(P.PANEL))
            s.d.line([x + 1, y + 1, x + 16, y + 1], fill=rgb(P.LINE))
            if on:
                # The tab's icon covers the inner 16x16: the frame round it shows.
                s.d.rectangle([x, y, x + 17, y + 17], outline=rgb(P.ACCENT))
        s.wells(slots(1, 0, 4, 5))
        # The loadout panel, set off from the stored items by a divider (drawn over the gear wells'
        # left edge, since the cells have no gutter between them).
        x = 7 + 6 * 18
        _, y0 = slot_xy(9)
        _, y1 = slot_xy(36)
        for i, kind in STASH_GEAR:
            cx, cy = slot_xy(i)
            if kind == 'leggings':
                zone_well(s.img, cx, cy, 'rig')
            elif kind == 'boots':
                zone_well(s.img, cx, cy, 'pack')
            else:
                well(s.img, cx, cy)
            s.img.alpha_composite(silhouette(kind), (cx + 1, cy + 1))
        for i, kind in STASH_KEYS:
            cx, cy = slot_xy(i)
            well(s.img, cx, cy)
            hint(s.img, cx, cy, kind)
        s.d.line([x, y0 - 1, x, y1 + 16], fill=mix(rgb(P.ACCENT), rgb(P.PANEL), 0.55))
        # Keys 1-8 sit in a frame in the hotbar's accent, so they read as the keys, not more stash.
        kx0, ky0 = slot_xy(16)
        kx1, ky1 = slot_xy(44)
        s.d.rectangle([kx0 - 1, ky0 - 1, kx1 + 18, ky1 + 16], outline=mix(rgb(P.ACCENT), rgb(P.PANEL), 0.55))
        s.toolbar(5).wells(slots(5, 0, 5, 8))
        return s.done()
    return make


STASH_TABS = 9


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


LOOT_SIZES = (9, 18, 27, 36)
"""Container sizes the loot screen has art for; a larger one (a body) shows 35 cells a page."""
SECURE_CELLS = (7, 8, 16, 17, 25, 26, 34, 35, 43)
SECURE = '#9C8CF0'


def loot_screen(size):
    """The search screen (#10): the container on the left in six columns (`size` cells, the rest of the
    6x6 block blank), "我的装备" on the right: helmet, body armour, rig and backpack, take-all and tidy,
    the secure container (up to nine cells) and the load and value cells. Raw slots as in LootMenu."""
    def make():
        s = Screen(6)
        # A hazard-striped lip over the container half marks it; the title keeps its band clear.
        s.hazard(3, 2, 6 * 18 + 6, 3)
        s.wells([(k // 6) * 9 + k % 6 for k in range(size)])
        if size < 36:
            last = size - 1
            x0, y0 = slot_xy((last // 6) * 9 + last % 6)
            label(s.img, 9, y0 + 20, '容量 %d 格' % size, P.DIM)
        # Gear column and secure container, set off from the container by a divider.
        x = 7 + 6 * 18
        s.d.line([x + 1, 4, x + 1, 6 * 18 + 16], fill=rgb(P.LINE))
        label(s.img, x + 5, 4, '我的装备', P.MUTED)
        for i, kind in zip((6, 15, 24, 33), ('helmet', 'chestplate', 'leggings', 'boots')):
            cx, cy = slot_xy(i)
            if kind == 'leggings':
                zone_well(s.img, cx, cy, 'rig')
            elif kind == 'boots':
                zone_well(s.img, cx, cy, 'pack')
            else:
                well(s.img, cx, cy)
            s.img.alpha_composite(silhouette(kind), (cx + 1, cy + 1))
        s.wells([42, 51])
        x0, y0 = slot_xy(7)
        x1, y1 = slot_xy(43)
        s.d.rectangle([x0 - 2, y0 - 2, x0 + 2 * 18 + 1, y1 + 18 + 1], outline=mix(rgb(SECURE), rgb(P.PANEL), 0.45))
        s.wells(SECURE_CELLS)
        s.wells([44, 52, 53])
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


def settlement_screen(extracted):
    """After a raid: the result in the middle of the top row, a row of figures, the ten most valuable
    items taken out (or lost) on a plate, and back, stash and "again" in the bottom bar."""
    def make():
        s = Screen(6)
        tone = P.OK if extracted else P.DANGER
        # The outcome across the top row; the report itself (hover for map, time, cause) in its last cell.
        x0, y0, x1, y1 = cells(0, 0, 0, 7)
        s.d.rectangle([x0, y0 + 1, x1, y1 - 1], fill=mix(rgb(P.RAISED), rgb(tone), 0.18))
        s.d.line([x0, y1 - 1, x1, y1 - 1], fill=rgb(tone))
        s.d.rectangle([x0, y0 + 4, x0 + 1, y1 - 5], fill=rgb(tone))
        label(s.img, x0 + 5, y0 + 4, '撤离成功' if extracted else '行动失败', tone)
        s.wells([8])
        s.wells(slots(1, 0, 1, 8))
        s.band(2, 0, 8, '带出的物品 · 价值最高的 10 件' if extracted else '损失的物品 · 价值最高的 10 件', tick=tone)
        s.toolbar(5).wells([45, 49])
        s.big(5, 6, 5, 8, ['再来一局'])
        # The plate goes on after the bottom bar and closes on the row boundary: drawn first, its
        # frame and the lower wells' bottom edge vanished under the bar's divider and the second
        # row of items looked cut off.
        x0, y0, x1, y1 = cells(3, 2, 4, 6)
        s.d.rectangle([x0 - 2, y0 - 2, x1 + 2, y1], fill=rgb('#11151A'), outline=rgb(tone))
        s.wells(slots(3, 2, 4, 6))
        s.d.line([x0 - 2, y1, x1 + 2, y1], fill=rgb(tone))
        return s.done()
    return make


def skin_screen(knife, knives, skins):
    """外观 (knife skins, #106) for one knife: the knives as tabs down the left (this one marked), and a
    card per look (the knife's own, then its skins in skins.yml order): the look drawn at twice the
    size of an item with its name under it, above a well for its state (worn, owned, locked: bought
    with 点券) and one for the inspect preview. The card cells hold invisible buttons, so the art
    shows through. The 点券 shop and balance sit at the right end of the top band, back, help and
    close in the bottom bar. `knives` are the tabs in order, `skins` is [(name, sprite)] for this
    knife's skins."""
    import icons
    s = Screen(6)
    s.band(0, 0, 6, '皮肤只改变刀的外观').wells([7, 8])
    # Tabs: one well per knife; this one framed in amber and joined to the cards.
    s.wells([9 + 9 * i for i in range(len(knives))])
    x, y = slot_xy(9 + 9 * knives.index(knife))
    s.d.rectangle([x - 2, y - 2, x + 19, y + 19], outline=rgb(P.ACCENT))
    s.d.rectangle([x + 20, y + 7, x + 22, y + 10], fill=rgb(P.ACCENT))
    looks = [('默认', icons.render(knife))] + list(skins)
    for i, (name, sprite) in enumerate(looks[:4]):
        c0 = 1 + 2 * i
        x0, y0, x1, y1 = cells(1, c0, 3, c0 + 1)
        s.d.rectangle([x0, y0, x1, y1], fill=WELL_DARK)
        s.d.rectangle([x0 + 1, y0 + 1, x1 - 1, y1 - 1], fill=rgb('#11151A'), outline=rgb(P.LINE))
        # A soft light behind the knife, as on a display stand.
        s.d.ellipse([x0 + 4, y0 + 6, x1 - 4, y0 + 38], fill=mix(rgb('#11151A'), rgb(P.RAISED), 0.9))
        s.img.alpha_composite(sprite, ((x0 + x1 + 1 - sprite.width) // 2, y0 + 5))
        s.d.line([x0 + 5, y0 + 40, x1 - 5, y0 + 40], fill=rgb(P.LINE))
        label(s.img, (x0 + x1 + 1 - pixelfont.width(name)) // 2, y0 + 42, name, P.TEXT if i else P.MUTED)
        s.wells([36 + c0, 37 + c0])
    s.toolbar(5).wells([45, 49, 53])
    return s.done()


def skin_screens(catalogue):
    """Screen id -> maker for the 外观 menu, one per knife (SkinMenu): catalogue is skins.yml's skins.
    The tabs are the knives in the order they first come in it, as SkinService.knives() has them; the
    menu has room for four tabs and four cards (the knife's own look and three skins)."""
    import knife_skins
    knives = list(dict.fromkeys(spec['knife'] for spec in catalogue.values()))
    assert len(knives) <= 4, 'the 外观 menu has four tabs, skins.yml has skins for %d knives' % len(knives)
    out = {}
    for knife in knives:
        skins = [(spec['name'], knife_skins.render(sid)) for sid, spec in catalogue.items() if spec['knife'] == knife]
        assert len(skins) <= 3, '%s has %d skins: a tab has room for three' % (knife, len(skins))
        out['skins_' + knife] = (lambda k, sk: lambda: skin_screen(k, knives, sk))(knife, skins)
    return out


def training_settlement_screen(done):
    """After the training raid: the outcome across the top row, the time, the rounds fired and what
    comes next, the six lessons on a plate, and back, stash and "start a raid" in the bottom bar.
    Nothing found on the course is kept, so there are no items to show and no word of losing any."""
    def make():
        s = Screen(6)
        tone = P.OK if done else P.DANGER
        x0, y0, x1, y1 = cells(0, 0, 0, 7)
        s.d.rectangle([x0, y0 + 1, x1, y1 - 1], fill=mix(rgb(P.RAISED), rgb(tone), 0.18))
        s.d.line([x0, y1 - 1, x1, y1 - 1], fill=rgb(tone))
        s.d.rectangle([x0, y0 + 4, x0 + 1, y1 - 5], fill=rgb(tone))
        label(s.img, x0 + 5, y0 + 4, '训练完成' if done else '训练中断', tone)
        s.wells([8])
        s.wells(slots(1, 0, 1, 2))
        s.band(2, 0, 8, '训练课程 · 完成的会发光', tick=tone)
        s.toolbar(5).wells([45, 49])
        s.big(5, 6, 5, 8, ['开始行动'])
        x0, y0, x1, y1 = cells(3, 1, 3, 6)
        s.d.rectangle([x0 - 2, y0 - 2, x1 + 2, y1 + 2], fill=rgb('#11151A'), outline=rgb(tone))
        s.wells(slots(3, 1, 3, 6))
        return s.done()
    return make


def blackout():
    """The transition curtain: one black glyph that a title (drawn at 4x) stretches over the screen."""
    return Image.new('RGBA', (64, 16), (0, 0, 0, 255))


# Screen id -> function returning (image, pixels it reaches left of the window, pixels above it).
SCREENS = {
    'main': main_screen,
    'deploy': deploy_screen,
    'deploy_queued': lambda: deploy_screen(True),
    **{'stash_%d' % t: stash_screen(t) for t in range(STASH_TABS)},
    'trader': trader_screen,
    **{'loot_%d' % n: loot_screen(n) for n in LOOT_SIZES},
    'health': health_screen,
    'gunsmith': gunsmith_screen,
    'settlement_ok': settlement_screen(True),
    'settlement_fail': settlement_screen(False),
    # New screens go last: each one's glyph is its place in this list (build.py, SCREEN_BASE + i).
    'settlement_training_ok': training_settlement_screen(True),
    'settlement_training_fail': training_settlement_screen(False),
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
