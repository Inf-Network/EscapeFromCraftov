"""Interface pictograms, the sniper scope overlay and HUD images.

Pictograms are drawn in the design palette (palette.py) on a raised plate: light strokes for the
object, amber for what matters (money, the main action), the semantic colours for state. Every key
has a picture of its own; no two menu buttons share one.
"""
import math
from PIL import Image, ImageDraw
import palette as PAL
from px import Canvas, rgb, shade

W = rgb(PAL.TEXT); D = rgb('#0F1318'); G = rgb(PAL.MUTED); Y = rgb(PAL.ACCENT); GR = rgb(PAL.OK)
R = rgb(PAL.DANGER); B = rgb(PAL.INFO); P = rgb('#9C8CF0'); O = shade(Y, 0.85); C = rgb('#7FC8D1')
PLATE = rgb(PAL.RAISED)


def plate(c, color=PLATE):
    c.box(1, 1, 30, 30, color, 1.35, 0.55)


def icon(draw_fn, bg=True):
    c = Canvas()
    if bg:
        plate(c)
    draw_fn(c)
    return c.img


def person(c, x, col):
    c.ellipse(x - 3, 7, x + 3, 13, col)
    c.poly([(x - 6, 26), (x - 5, 16), (x + 5, 16), (x + 6, 26)], col)


def occupied_cell():
    """A secure-container cell taken by the big item to its left (SecureMenu): a sunken, hatched
    plate with an arrow pointing back at the item."""
    c = Canvas()
    plate(c, shade(PLATE, 0.75))
    hatch = shade(rgb(PAL.DIM), 0.7)
    for k in range(6, 58, 5):
        x0, x1 = max(3, k - 28), min(28, k - 3)
        if x0 <= x1:
            c.line([(x0, k - x0), (x1, k - x1)], hatch)
    c.poly([(6, 16), (13, 9), (13, 23)], G)
    c.rect(13, 14, 24, 18, G)
    return c.img


UI = {
    'blank': lambda: Image.new('RGBA', (16, 16), (0, 0, 0, 0)),
    'locked': lambda: icon(lambda c: (c.box(10, 14, 21, 24, G), c.ellipse(11, 7, 20, 17, (0, 0, 0, 0)),
                                     c.line([(12, 14), (12, 10), (14, 8), (17, 8), (19, 10), (19, 14)], G, 2)), True),
    'search_hidden': lambda: icon(lambda c: (c.line([(13, 11), (14, 9), (18, 9), (19, 11), (19, 13), (16, 16), (16, 19)], G, 2),
                                            c.rect(15, 22, 17, 24, G)), True),
    'search_active': lambda: icon(lambda c: (c.ellipse(8, 7, 20, 19, Y), c.ellipse(10, 9, 18, 17, rgb('#2A2E35')),
                                            c.line([(19, 18), (25, 24)], Y, 3)), True),
    'terminal': lambda: icon(lambda c: (c.box(7, 4, 25, 28, D), c.box(9, 7, 23, 22, rgb('#1F4E5F')), c.rect(11, 10, 20, 11, C),
                                       c.rect(11, 14, 18, 15, C), c.rect(11, 18, 21, 19, Y), c.rect(14, 24, 18, 25, G)), False),
    'icon_deploy': lambda: icon(lambda c: (c.ellipse(7, 7, 24, 24, R), c.ellipse(9, 9, 22, 22, rgb('#2A2E35')),
                                          c.line([(16, 4), (16, 27)], W), c.line([(4, 16), (27, 16)], W), c.rect(15, 15, 17, 17, R))),
    'icon_stash': lambda: icon(lambda c: (c.box(6, 9, 25, 25, rgb('#7A5230')), c.rect(6, 15, 25, 16, rgb('#4F3420')),
                                         c.rect(14, 13, 17, 18, Y))),
    'icon_trader': lambda: icon(lambda c: (c.ellipse(7, 7, 24, 24, Y), c.ellipse(10, 10, 21, 21, shade(Y, 0.8)),
                                          c.poly([(16, 11), (20, 16), (16, 21), (12, 16)], shade(Y, 1.3)))),
    'icon_market': lambda: icon(lambda c: (c.line([(6, 24), (12, 16), (17, 19), (25, 8)], GR, 2), c.rect(6, 25, 26, 26, G),
                                          c.poly([(25, 8), (21, 8), (25, 12)], GR))),
    'icon_operator': lambda: icon(lambda c: (c.ellipse(8, 6, 24, 22, O), c.rect(8, 15, 24, 18, shade(O, 0.7)),
                                            c.poly([(9, 26), (10, 20), (22, 20), (23, 26)], shade(O, 0.8)))),
    'icon_secure': lambda: icon(lambda c: (c.box(7, 7, 24, 25, P), c.ellipse(12, 11, 19, 18, D), c.rect(15, 14, 16, 15, P),
                                          c.rect(20, 12, 22, 14, W))),
    'secure_used': occupied_cell,
    'icon_escort': lambda: icon(lambda c: (c.poly([(16, 5), (25, 9), (24, 18), (16, 26), (8, 18), (7, 9)], C),
                                          c.poly([(16, 9), (21, 11), (20, 17), (16, 22), (12, 17), (11, 11)], shade(C, 0.6)))),
    'icon_collection': lambda: icon(lambda c: c.poly([(16, 5), (19, 13), (27, 13), (21, 18), (23, 26), (16, 21), (9, 26), (11, 18),
                                                      (5, 13), (13, 13)], R)),
    'icon_rank': lambda: icon(lambda c: (c.poly([(9, 6), (23, 6), (21, 15), (16, 18), (11, 15)], Y), c.rect(15, 18, 17, 22, Y),
                                        c.box(11, 22, 21, 25, shade(Y, 0.8)), c.line([(9, 8), (6, 8), (7, 12), (10, 13)], Y),
                                        c.line([(23, 8), (26, 8), (25, 12), (22, 13)], Y))),
    'icon_party': lambda: icon(lambda c: (person(c, 11, G), person(c, 21, W))),
    'icon_solo': lambda: icon(lambda c: person(c, 16, W)),
    'icon_fill': lambda: icon(lambda c: (person(c, 12, W), c.rect(20, 14, 27, 16, GR), c.rect(23, 11, 24, 19, GR))),
    'icon_repair': lambda: icon(lambda c: (c.line([(9, 23), (21, 11)], G, 3), c.ellipse(18, 6, 26, 14, G),
                                          c.ellipse(20, 8, 24, 12, rgb('#2A2E35')))),
    'icon_help': lambda: icon(lambda c: (c.line([(12, 11), (13, 8), (19, 8), (20, 11), (20, 13), (16, 16), (16, 20)], W, 2),
                                        c.rect(15, 23, 17, 25, W))),
    'icon_map': lambda: icon(lambda c: (c.poly([(5, 8), (12, 6), (20, 9), (27, 7), (27, 24), (20, 26), (12, 23), (5, 25)], rgb('#C9B27E')),
                                       c.line([(12, 6), (12, 23)], shade(rgb('#C9B27E'), 0.7)), c.line([(20, 9), (20, 26)], shade(rgb('#C9B27E'), 0.7)),
                                       c.ellipse(14, 12, 18, 16, R))),
    'icon_value': lambda: icon(lambda c: (c.poly([(16, 5), (26, 16), (16, 27), (6, 16)], Y), c.poly([(16, 10), (21, 16), (16, 22), (11, 16)], shade(Y, 0.7)))),
    'icon_start': lambda: icon(lambda c: c.poly([(11, 7), (24, 16), (11, 25)], GR)),
    'icon_cancel': lambda: icon(lambda c: (c.line([(9, 9), (23, 23)], R, 3), c.line([(23, 9), (9, 23)], R, 3))),
    'icon_sort': lambda: icon(lambda c: (c.poly([(10, 6), (15, 12), (5, 12)], W), c.rect(9, 12, 11, 25, W),
                                        c.poly([(22, 26), (27, 20), (17, 20)], W), c.rect(21, 7, 23, 20, W))),
    'icon_filter': lambda: icon(lambda c: c.poly([(6, 8), (26, 8), (18, 17), (18, 25), (14, 23), (14, 17)], W)),
    'icon_mode': lambda: icon(lambda c: (c.ellipse(7, 7, 24, 24, B), c.ellipse(10, 10, 21, 21, rgb('#2A2E35')),
                                        c.poly([(20, 5), (26, 9), (19, 12)], B))),
    'icon_mail': lambda: icon(lambda c: (c.box(5, 9, 26, 23, W), c.line([(5, 9), (16, 17), (26, 9)], G))),
    'icon_upgrade': lambda: icon(lambda c: (c.poly([(16, 5), (25, 15), (20, 15), (20, 26), (12, 26), (12, 15), (7, 15)], GR))),
    'icon_ammo': lambda: icon(lambda c: [(c.box(x, 11, x + 4, 25, rgb('#C9A24A')), c.ellipse(x, 6, x + 4, 13, rgb('#B8733D'))) for x in (8, 14, 20)]),
    'icon_sell': lambda: icon(lambda c: (c.ellipse(5, 9, 20, 24, Y), c.poly([(19, 7), (27, 13), (19, 19)], GR))),
    'icon_profile': lambda: icon(lambda c: (c.box(4, 7, 27, 25, rgb('#3A3F47')), c.ellipse(7, 10, 14, 17, W),
                                           c.poly([(6, 23), (7, 18), (14, 18), (15, 23)], W), c.rect(17, 11, 24, 12, G), c.rect(17, 15, 22, 16, G))),
    'icon_cancel_small': lambda: icon(lambda c: c.line([(10, 10), (21, 21)], R, 3)),
    'arrow_left': lambda: icon(lambda c: c.poly([(20, 7), (10, 16), (20, 25)], W)),
    'arrow_right': lambda: icon(lambda c: c.poly([(12, 7), (22, 16), (12, 25)], W)),
    'collection_unknown': lambda: icon(lambda c: (c.line([(12, 11), (13, 8), (19, 8), (20, 11), (20, 13), (16, 16), (16, 20)], G, 2),
                                                 c.rect(15, 23, 17, 25, G)), True),
    'report_ok': lambda: icon(lambda c: c.line([(7, 16), (13, 22), (25, 9)], GR, 3)),
    'report_fail': lambda: icon(lambda c: (c.line([(9, 9), (23, 23)], R, 3), c.line([(23, 9), (9, 23)], R, 3))),
}


def tier_badge(color, stars):
    def make():
        c = Canvas()
        c.poly([(16, 3), (28, 9), (26, 22), (16, 29), (6, 22), (4, 9)], color)
        c.poly([(16, 7), (24, 11), (22, 20), (16, 25), (10, 20), (8, 11)], shade(color, 0.6))
        for i in range(stars):
            x = 16 + (i - (stars - 1) / 2) * 5
            c.poly([(x, 11), (x + 2, 16), (x, 21), (x - 2, 16)], shade(color, 1.4))
        return c.outline().img
    return make


def operator_badge(color, glyph):
    def make():
        c = Canvas()
        plate(c, shade(color, 0.45))
        c.ellipse(9, 5, 23, 19, color)
        c.poly([(5, 29), (7, 19), (25, 19), (27, 29)], color)
        glyph(c)
        return c.img
    return make


UI.update({
    'tier_normal': tier_badge(GR, 1), 'tier_confidential': tier_badge(B, 2), 'tier_secret': tier_badge(R, 3),
    'op_vanguard': operator_badge(O, lambda c: c.poly([(12, 12), (20, 12), (16, 8)], D)),
    'op_medic': operator_badge(GR, lambda c: (c.rect(14, 9, 17, 16, W), c.rect(12, 11, 19, 14, W))),
    'op_recon': operator_badge(B, lambda c: (c.ellipse(12, 9, 20, 15, D), c.ellipse(15, 11, 17, 13, W))),
    'op_engineer': operator_badge(rgb('#C8CCD2'), lambda c: c.rect(11, 10, 21, 14, D)),
})


def rifle(c, y=15, col=W):
    """A side-on rifle: stock, receiver, magazine, barrel."""
    c.poly([(4, y + 1), (9, y - 1), (9, y + 4), (5, y + 5)], col)
    c.rect(9, y - 2, 22, y + 1, col)
    c.rect(22, y - 1, 28, y, col)
    c.poly([(14, y + 1), (17, y + 1), (16, y + 6), (13, y + 6)], col)
    c.rect(18, y + 1, 19, y + 3, col)
    c.rect(12, y - 4, 16, y - 3, col)


def tab_weapon(c):
    rifle(c)


def tab_ammo(c):
    # A curved box magazine with a round showing at the top.
    c.poly([(11, 9), (19, 9), (21, 26), (14, 27)], G)
    c.poly([(12, 11), (18, 11), (19, 24), (15, 25)], shade(G, 0.75))
    c.ellipse(13, 5, 17, 10, rgb('#C9A24A'))


def tab_armor(c):
    c.poly([(8, 6), (12, 6), (16, 9), (20, 6), (24, 6), (26, 11), (25, 26), (7, 26), (6, 11)], G)
    c.rect(11, 13, 21, 22, shade(G, 0.7))
    c.rect(12, 15, 20, 16, W)


def tab_helmet(c):
    c.d.chord([6, 7, 26, 31], 180, 360, fill=G)
    c.rect(5, 18, 27, 20, shade(G, 0.75))
    c.rect(22, 13, 25, 15, W)


def tab_carry(c):
    c.d.rounded_rectangle([8, 7, 24, 27], 4, fill=G)
    c.rect(11, 16, 21, 23, shade(G, 0.7))
    c.line([(12, 5), (20, 5)], G, 2)
    c.rect(11, 18, 21, 18, W)


def tab_throwable(c):
    # A frag grenade: ribbed body, spoon and pin ring.
    c.ellipse(9, 11, 23, 27, rgb('#6E7A55'))
    for y in (15, 19, 23):
        c.line([(10, y), (22, y)], shade(rgb('#6E7A55'), 0.7))
    c.rect(13, 7, 19, 11, G)
    c.line([(19, 8), (24, 13)], G, 2)
    c.d.ellipse([20, 3, 26, 9], outline=Y)


def tab_service(c):
    # A spanner over a cog.
    for a in range(0, 360, 45):
        x, y = 16 + 9 * math.cos(math.radians(a)), 16 + 9 * math.sin(math.radians(a))
        c.ellipse(x - 2, y - 2, x + 2, y + 2, G)
    c.ellipse(9, 9, 23, 23, G)
    c.ellipse(13, 13, 19, 19, PLATE)
    c.line([(8, 25), (22, 11)], W, 3)
    c.ellipse(19, 6, 27, 14, W)
    c.ellipse(21, 8, 25, 12, PLATE)


def tab_market(c):
    # Candles on a baseline.
    for x, lo, hi, col in ((8, 16, 24, R), (14, 12, 20, GR), (20, 8, 17, GR), (26, 11, 18, R)):
        c.line([(x, lo - 3), (x, hi + 3)], G)
        c.rect(x - 2, lo, x + 2, hi, col)
    c.rect(5, 26, 27, 26, G)


for _tab in (tab_weapon, tab_ammo, tab_armor, tab_helmet, tab_carry, tab_throwable, tab_service, tab_market):
    UI[_tab.__name__] = (lambda fn: lambda: icon(fn))(_tab)


def clipboard(c, col=W):
    c.box(8, 6, 24, 28, shade(G, 0.55))
    c.rect(10, 9, 22, 26, col)
    c.rect(13, 4, 19, 8, G)


UI.update({
    # Season pass: three rank chevrons under a star.
    'icon_season': lambda: icon(lambda c: (c.poly([(16, 4), (18, 8), (22, 8), (19, 11), (20, 15), (16, 12), (12, 15), (13, 11),
                                                   (10, 8), (14, 8)], Y),
                                          [c.poly([(7, y), (16, y + 5), (25, y), (25, y + 3), (16, y + 8), (7, y + 3)], W)
                                           for y in (15, 20)])),
    # Bounty board: a skull under crosshair ticks.
    'icon_bounty': lambda: icon(lambda c: (c.ellipse(9, 7, 23, 20, W), c.rect(12, 19, 20, 24, W),
                                          c.ellipse(11, 11, 15, 15, PLATE), c.ellipse(17, 11, 21, 15, PLATE),
                                          c.rect(14, 21, 14, 24, PLATE), c.rect(17, 21, 17, 24, PLATE),
                                          c.rect(15, 2, 16, 5, R), c.rect(15, 26, 16, 29, R), c.rect(3, 15, 6, 16, R),
                                          c.rect(26, 15, 29, 16, R))),
    # Hideout: a bunker door set into a mound, with an antenna.
    'icon_hideout': lambda: icon(lambda c: (c.d.chord([3, 10, 29, 40], 180, 360, fill=rgb('#5B6B4E')),
                                           c.rect(12, 16, 20, 25, D), c.rect(13, 17, 19, 25, G), c.rect(17, 20, 18, 21, Y),
                                           c.line([(23, 13), (23, 4)], G), c.line([(21, 6), (25, 6)], G))),
    # Free supply drop: a crate under a parachute.
    'icon_freekit': lambda: icon(lambda c: (c.d.chord([6, 3, 26, 17], 180, 360, fill=GR), c.line([(7, 10), (13, 18)], G),
                                           c.line([(25, 10), (19, 18)], G), c.box(11, 17, 21, 27, rgb('#8A6A3E')),
                                           c.rect(11, 21, 21, 22, shade(rgb('#8A6A3E'), 0.7)))),
    # Loadout presets: a clipboard with ticked rows.
    'icon_preset': lambda: icon(lambda c: (clipboard(c), [c.rect(14, y, 21, y + 1, G) for y in (12, 17, 22)],
                                          [c.line([(11, y + 1), (12, y + 2), (13, y)], GR) for y in (12, 17, 22)])),
    # Daily tasks: a list with one tick and two boxes.
    'icon_tasks': lambda: icon(lambda c: (clipboard(c, rgb('#D9D4C6')), c.line([(11, 13), (12, 14), (14, 11)], GR, 2),
                                         c.rect(16, 12, 21, 13, G), c.d.rectangle([11, 17, 13, 19], outline=G),
                                         c.rect(16, 18, 21, 19, G), c.d.rectangle([11, 22, 13, 24], outline=G),
                                         c.rect(16, 23, 21, 24, G))),
    # Gunsmith: a scope over a spanner.
    'icon_gunsmith': lambda: icon(lambda c: (c.line([(7, 26), (24, 9)], G, 3), c.ellipse(20, 4, 28, 12, G),
                                            c.ellipse(22, 6, 26, 10, PLATE), c.box(5, 9, 21, 14, W), c.box(3, 8, 7, 15, shade(W, 0.6)),
                                            c.rect(21, 10, 22, 13, B))),
    # Test armory: a rack of three rifles standing up.
    'icon_catalog': lambda: icon(lambda c: ([(c.rect(x, 6, x + 2, 24, W), c.rect(x - 1, 20, x + 3, 26, G)) for x in (8, 15, 22)],
                                           c.rect(5, 27, 27, 28, Y))),
    'report_fail': lambda: icon(lambda c: (c.d.ellipse([6, 6, 26, 26], outline=R, width=2), c.line([(12, 12), (20, 20)], R, 2),
                                          c.line([(20, 12), (12, 20)], R, 2))),
})


def medical_tab():
    c = Canvas()
    plate(c)
    c.rect(13, 7, 18, 24, R)
    c.rect(7, 13, 24, 18, R)
    return c.img


UI['tab_medical'] = medical_tab

def attachment_tab():
    c = Canvas()
    plate(c)
    c.box(7, 13, 25, 17, rgb('#3A3E44'))
    c.box(5, 12, 9, 18, rgb('#1E2023'))
    c.box(23, 11, 27, 19, rgb('#1E2023'))
    c.rect(12, 17, 13, 21, rgb('#3A3E44'))
    c.rect(19, 17, 20, 21, rgb('#3A3E44'))
    c.rect(26, 13, 26, 17, rgb('#7FB8E0'))
    return c.img


UI['tab_attachment'] = attachment_tab


# Scope reticles are drawn for the default 70 degree field of view at each scope's power (OpticSpec):
# the overlay is stretched over the whole window, so a 16:9 window shows the marks at their true
# angles (a 16:10 one about a tenth narrower across). Only at 70 degrees: at another declared field
# of view (/cv fov) the scope keeps its power relative to that view, so the scoped view is wider or
# narrower than these marks assume (at 90 every angle is about 1.4 times what the marks read). The
# scope's round mask is an item model drawn with the hand (models.scope_mask_model), which stays
# round at any window shape.
SCOPE_POWER = {'3x': 3, '4x': 4, '8x': 8}
SCOPE_HOLE = {'3x': 0.47, '4x': 0.45, '8x': 0.43}   # mask radius, a share of the screen height (models.MASK_HOLE)


def scope_px_per_mil(style, h=1080, fov=70.0):
    """Pixels per milliradian near the centre of a 1080-pixel-high overlay for this scope."""
    n = SCOPE_POWER[style]
    half = math.radians(fov / 2)
    zoomed_half = math.atan(math.tan(half) / n)
    return (h / 2) * 0.001 / math.tan(zoomed_half)


def scope_overlay(style='4x', w=1920, h=1080):
    """The reticle seen while aiming through a magnified optic, on clear: 1920x1080, every mark placed
    by its angle (milliradians at the scope's power and a 70 degree field of view). Nothing else: the
    dark ring around it is the item model drawn with the hand. The lines and posts run on to the
    picture's edges: the overlay is stretched to the window but the ring keeps its size by the height,
    so on a window narrower than 16:9 a post ending at the ring's radius would stop short of it, while
    the ink, as black as the ring, does not show over it.

    3x: illuminated chevron over hash marks every 5 mil. 4x: duplex crosshair, thin within 40 mil of the
    centre. 8x: thin crosshair with mil-dots every 5 mil out to 20, thick posts from 30.
    """
    img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    mil = scope_px_per_mil(style, h)
    r = int(h * SCOPE_HOLE[style])
    cx, cy = w // 2, h // 2
    ink = (0, 0, 0, 255)
    red = (235, 60, 40, 255)
    if style == '3x':
        gap = int(round(4 * mil))
        d.line([(0, cy), (cx - gap * 2, cy)], fill=ink, width=3)
        d.line([(cx + gap * 2, cy), (w, cy)], fill=ink, width=3)
        d.line([(cx, cy + gap * 3), (cx, h)], fill=ink, width=3)
        size = max(10, int(round(2.5 * mil)))
        d.polygon([(cx, cy - 2), (cx - size, cy + int(size * 1.4)), (cx - size // 2, cy + int(size * 1.4)), (cx, cy + size // 2),
                   (cx + size // 2, cy + int(size * 1.4)), (cx + size, cy + int(size * 1.4))], fill=red)
        for i in range(1, 5):
            y = cy + int(round(i * 5 * mil))
            half = max(6, int(round((5 - i) * 0.6 * mil)))
            if y < cy + r:
                d.line([(cx - half, y), (cx + half, y)], fill=ink, width=3)
    elif style == '4x':
        thin = int(round(40 * mil))
        d.line([(0, cy), (cx - thin, cy)], fill=ink, width=12)
        d.line([(cx + thin, cy), (w, cy)], fill=ink, width=12)
        d.line([(cx, cy + thin), (cx, h)], fill=ink, width=12)
        d.line([(cx, 0), (cx, cy - thin)], fill=ink, width=12)
        d.line([(cx - thin, cy), (cx + thin, cy)], fill=ink, width=3)
        d.line([(cx, cy - thin), (cx, cy + thin)], fill=ink, width=3)
        d.ellipse([cx - 4, cy - 4, cx + 4, cy + 4], fill=red)
    else:
        d.line([(0, cy), (w, cy)], fill=ink, width=2)
        d.line([(cx, 0), (cx, h)], fill=ink, width=2)
        dot = max(3, int(round(0.45 * mil)))
        for i in range(1, 5):
            off = int(round(i * 5 * mil))
            for sx, sy in ((cx + off, cy), (cx - off, cy), (cx, cy + off), (cx, cy - off)):
                d.ellipse([sx - dot, sy - dot, sx + dot, sy + dot], fill=ink)
        post = int(round(30 * mil))
        for x0, y0, x1, y1 in ((0, cy, cx - post, cy), (cx + post, cy, w, cy), (cx, cy + post, cx, h)):
            d.line([(x0, y0), (x1, y1)], fill=ink, width=9)
        d.ellipse([cx - 2, cy - 2, cx + 2, cy + 2], fill=red)
    return img


# The range's calibration target (RangeService): CALIBRATION_SIZE blocks square, set CALIBRATION_DISTANCE
# blocks down the range at eye height. Its ring is 40 mil across the radius, where the 4x reticle's thin
# centre ends and its posts begin (scope_overlay): with the zoom right (4x at a 70 degree field of view
# and FOV Effects at 100%), the posts just touch the ring. At another field of view the reticle's marks
# no longer hold (see scope_overlay), and neither does this. Ticks every 5 mil match the 3x hash marks
# and the 8x mil-dots.
CALIBRATION_SIZE = 5.0
CALIBRATION_DISTANCE = 50.0


def calibration_target(px=512):
    img = Image.new('RGBA', (px, px), (226, 222, 210, 255))
    d = ImageDraw.Draw(img)
    per_block = px / CALIBRATION_SIZE
    mil = CALIBRATION_DISTANCE * 0.001 * per_block  # pixels per milliradian at that distance
    c = px / 2
    ink = (20, 20, 22, 255)
    d.rectangle([0, 0, px - 1, px - 1], outline=(90, 90, 92, 255), width=6)
    r = 40 * mil
    for w in range(5):
        d.ellipse([c - r + w, c - r + w, c + r - w, c + r - w], outline=ink)
    r2 = 20 * mil
    for w in range(2):
        d.ellipse([c - r2 + w, c - r2 + w, c + r2 - w, c + r2 - w], outline=(120, 120, 120, 255))
    d.line([(c - r, c), (c + r, c)], fill=ink, width=2)
    d.line([(c, c - r), (c, c + r)], fill=ink, width=2)
    for i in range(1, 9):
        off = i * 5 * mil
        half = 7 if i % 2 == 0 else 4
        for sx in (-1, 1):
            d.line([(c + sx * off, c - half), (c + sx * off, c + half)], fill=ink, width=2)
            d.line([(c - half, c + sx * off), (c + half, c + sx * off)], fill=ink, width=2)
    d.ellipse([c - 5, c - 5, c + 5, c + 5], fill=(220, 40, 30, 255))
    return img


def search_spinner(frames=12):
    """Animated search indicator: a dot chasing round a ring, with a fading trail and a lens in the middle."""
    out = []
    for f in range(frames):
        c = Canvas()
        plate(c)
        for i in range(frames):
            a = 2 * math.pi * i / frames - math.pi / 2
            x, y = 16 + 9.5 * math.cos(a), 16 + 9.5 * math.sin(a)
            age = (f - i) % frames
            if age == 0:
                col, r = Y, 2.2
            elif age < 5:
                col, r = shade(Y, 1.0 - 0.16 * age), 1.8 - 0.2 * age
            else:
                col, r = rgb('#4A4F57'), 1.0
            c.ellipse(x - r, y - r, x + r, y + r, col)
        c.ellipse(12, 11, 19, 18, W)
        c.ellipse(13.5, 12.5, 17.5, 16.5, rgb('#2A2E35'))
        c.line([(18, 17), (21, 20)], W, 2)
        out.append(c.img)
    return out


# Body-part silhouettes for the health screen. Layer 0 is the plate, layer 1 the part (tinted by dye colour).
BODY_SHAPES = {
    'head': lambda c: c.ellipse(10, 6, 21, 17, W),
    'chest': lambda c: (c.rect(8, 6, 23, 18, W), c.rect(10, 19, 21, 21, W)),
    'stomach': lambda c: (c.rect(9, 8, 22, 20, W), c.rect(11, 21, 20, 23, W)),
    'arm': lambda c: (c.rect(12, 4, 19, 26, W), c.ellipse(11, 23, 20, 29, W)),
    'leg': lambda c: (c.rect(11, 3, 20, 25, W), c.rect(11, 25, 23, 28, W)),
}


def body_plate():
    c = Canvas()
    plate(c, rgb('#22262C'))
    return c.img


def body_shape(name):
    c = Canvas()
    BODY_SHAPES[name](c)
    return c.img


def hit_overlay(w=640, h=360):
    """Camera overlay for the few ticks after taking a hit: a hard red rim, the middle left clear."""
    import numpy as np
    yy, xx = np.mgrid[0:h, 0:w]
    nx = xx / (w - 1) * 2 - 1
    ny = yy / (h - 1) * 2 - 1
    r = np.sqrt(nx ** 2 * 0.8 + ny ** 2 * 1.2)
    a = np.clip((r - 0.62) / 0.5, 0, 1) ** 1.3
    alpha = (a * 150).astype(np.uint8)
    rgb_ = np.zeros((h, w, 3), np.uint8)
    rgb_[..., 0] = 170
    rgb_[..., 1] = 18
    rgb_[..., 2] = 16
    return Image.fromarray(np.dstack([rgb_, alpha]))


# Blood edge strength per step (BLOOD_STEPS in HudService): health under 35, 24 and 12.
BLOOD_ALPHA = (95, 145, 200)


def blood_overlay(level, w=640, h=360):
    """Camera overlay while badly hurt: dark blood seeping in from the corners and edges, uneven, the
    middle of the screen always clear. Level 1..3 grows as health drops."""
    import numpy as np
    rng = np.random.default_rng(11)
    yy, xx = np.mgrid[0:h, 0:w]
    nx = xx / (w - 1) * 2 - 1
    ny = yy / (h - 1) * 2 - 1
    ang = np.arctan2(ny, nx)
    wobble = 0.07 * np.sin(ang * 5 + 1.3) + 0.05 * np.sin(ang * 11 + 0.4) + 0.03 * np.sin(ang * 23)
    r = np.sqrt(nx ** 2 * 0.75 + ny ** 2 * 1.25) + wobble
    reach = (0.95, 0.85, 0.75)[level - 1]
    a = np.clip((r - reach) / 0.45, 0, 1) ** 1.1
    grain = rng.uniform(0.85, 1.0, (h, w))
    alpha = (a * grain * BLOOD_ALPHA[level - 1]).astype(np.uint8)
    rgb_ = np.zeros((h, w, 3), np.uint8)
    rgb_[..., 0] = 92
    rgb_[..., 1] = 6
    rgb_[..., 2] = 8
    return Image.fromarray(np.dstack([rgb_, alpha]))


def suppression_overlay(w=640, h=360):
    """Camera overlay while bullets snap past: dark, slightly blurred-looking edges, clear centre."""
    import numpy as np
    yy, xx = np.mgrid[0:h, 0:w]
    nx = (xx / (w - 1) * 2 - 1)
    ny = (yy / (h - 1) * 2 - 1)
    r = np.sqrt(nx ** 2 * 0.85 + ny ** 2 * 1.15)
    a = np.clip((r - 0.45) / 0.75, 0, 1) ** 1.6
    rng = np.random.default_rng(7)
    grain = rng.uniform(0.92, 1.0, (h, w))
    alpha = (a * grain * 215).astype(np.uint8)
    rgb = np.zeros((h, w, 3), np.uint8)
    rgb[..., 0] = 14
    rgb[..., 1] = 12
    rgb[..., 2] = 10
    return Image.fromarray(np.dstack([rgb, alpha]))


def strobe_overlay(w=320, h=180):
    """White-out frame for someone blinded by a strobe: nearly opaque, a touch brighter in the middle."""
    import numpy as np
    yy, xx = np.mgrid[0:h, 0:w]
    r = np.sqrt(((xx / (w - 1)) * 2 - 1) ** 2 + ((yy / (h - 1)) * 2 - 1) ** 2)
    alpha = np.clip(245 - 25 * r, 0, 255).astype(np.uint8)
    rgb = np.full((h, w, 3), (250, 250, 244), np.uint8)
    return Image.fromarray(np.dstack([rgb, alpha]))


# Opacity of the flashbang white-out, one camera overlay per fade step: 4 blots the screen out
# completely, 1 is a faint haze. HudService.flash steps down through them as the flash wears off.
FLASH_ALPHA = (70, 140, 205, 255)


def flash_overlay(level, w=32, h=18):
    """Plain white camera overlay for a flashbanged player at fade step ``level`` (1..4)."""
    return Image.new('RGBA', (w, h), (255, 255, 255, FLASH_ALPHA[level - 1]))
