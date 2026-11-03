"""Interface pictograms, the sniper scope overlay and HUD images."""
import math
from PIL import Image, ImageDraw
from px import Canvas, rgb, shade

W = rgb('#E8E4D8'); D = rgb('#1E2126'); G = rgb('#8A8F98'); Y = rgb('#F2C94C'); GR = rgb('#5BD16B')
R = rgb('#FF4D4D'); B = rgb('#4FA3FF'); P = rgb('#B66BFF'); O = rgb('#FF9F43'); C = rgb('#7FE0D0')


def plate(c, color=rgb('#2A2E35')):
    c.box(1, 1, 30, 30, color, 1.15, 0.6)


def icon(draw_fn, bg=True):
    c = Canvas()
    if bg:
        plate(c)
    draw_fn(c)
    return c.img


def person(c, x, col):
    c.ellipse(x - 3, 7, x + 3, 13, col)
    c.poly([(x - 6, 26), (x - 5, 16), (x + 5, 16), (x + 6, 26)], col)


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
for tab, base in (('weapon', 'icon_deploy'), ('ammo', 'icon_ammo'), ('armor', 'icon_secure'), ('helmet', 'icon_operator'),
                  ('carry', 'icon_stash'), ('medical', 'icon_help'), ('throwable', 'icon_cancel_small'), ('service', 'icon_repair'),
                  ('market', 'icon_market')):
    UI['tab_' + tab] = UI[base]


def medical_tab():
    c = Canvas()
    plate(c)
    c.rect(13, 7, 18, 24, R)
    c.rect(7, 13, 24, 18, R)
    return c.img


UI['tab_medical'] = medical_tab


def scope_overlay(w=960, h=540):
    img = Image.new('RGBA', (w, h), (0, 0, 0, 255))
    d = ImageDraw.Draw(img)
    r = int(h * 0.46)
    cx, cy = w // 2, h // 2
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(0, 0, 0, 0))
    for k in range(10):
        rr = r - k
        d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], outline=(0, 0, 0, 230 - k * 22))
    d.line([(cx - r, cy), (cx - 8, cy)], fill=(10, 10, 10, 255), width=2)
    d.line([(cx + 8, cy), (cx + r, cy)], fill=(10, 10, 10, 255), width=2)
    d.line([(cx, cy - r), (cx, cy - 8)], fill=(10, 10, 10, 255), width=2)
    d.line([(cx, cy + 8), (cx, cy + r)], fill=(10, 10, 10, 255), width=2)
    for i in range(1, 6):
        d.line([(cx - 4, cy + i * 18), (cx + 4, cy + i * 18)], fill=(10, 10, 10, 255), width=2)
        d.line([(cx + i * 22, cy - 3), (cx + i * 22, cy + 3)], fill=(10, 10, 10, 255), width=2)
        d.line([(cx - i * 22, cy - 3), (cx - i * 22, cy + 3)], fill=(10, 10, 10, 255), width=2)
    d.ellipse([cx - 2, cy - 2, cx + 2, cy + 2], fill=(220, 40, 40, 255))
    return img
