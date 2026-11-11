"""Procedural 32x32 item icons. Every function draws onto a fresh Canvas."""
import math
from px import Canvas, rgb, shade, mix

K = rgb('#1E1F22'); MD = rgb('#2E3236'); M = rgb('#4A5056'); ST = rgb('#8A9198'); LS = rgb('#C3C9CF')
BR = rgb('#C9A24A'); CU = rgb('#B8733D'); WD = rgb('#7A5230'); OL = rgb('#5E6B3C'); TN = rgb('#B7A27A')
RD = rgb('#C0392B'); WH = rgb('#E8E8E8'); GN = rgb('#3F8F4F'); BL = rgb('#3D6FB6'); YL = rgb('#E2B33C')
GD = rgb('#F2C14E'); PU = rgb('#8E5BD8'); OR = rgb('#E07B2E'); CY = rgb('#4FC3D9'); PK = rgb('#E7A6B8')


def bullet(c, x, y, h, case, tip, w=4):
    c.box(x, y + 3, x + w - 1, y + h, case)
    c.rect(x, y + h - 1, x + w - 1, y + h, shade(case, 0.7))
    c.ellipse(x, y, x + w - 1, y + 5, tip)
    c.rect(x, y + 3, x + w - 1, y + 3, shade(case, 1.3))


TIPS = {1: CU, 2: ST, 3: GN, 4: BL, 5: RD, 6: PU}


def ammo(pen, case=BR, long=False, shell=None):
    def draw(c):
        if shell:
            for i, x in enumerate((6, 13, 20)):
                c.box(x, 6 + i * 2, x + 6, 24 + i * 2 - 4, shell)
                c.box(x, 24 + i * 2 - 4, x + 6, 27 + i * 2 - 4, BR)
                c.rect(x + 1, 6 + i * 2, x + 5, 7 + i * 2, shade(shell, 1.3))
            return
        h = 22 if long else 17
        for i, x in enumerate((7, 14, 21)):
            bullet(c, x, 4 + (2 - i) * 2 + (0 if long else 4), h, case, TIPS.get(pen, CU), 5)
    return draw


def helmet(color, visor=None, rails=False, full=False):
    def draw(c):
        c.ellipse(5, 6, 27, 28, color)
        c.rect(4, 18, 28, 31, (0, 0, 0, 0))
        c.box(4, 17, 28, 20, shade(color, 0.85))
        c.ellipse(8, 8, 18, 14, shade(color, 1.25))
        if rails:
            c.rect(6, 15, 9, 17, MD)
            c.rect(23, 15, 26, 17, MD)
        if full:
            c.box(8, 18, 24, 27, shade(color, 0.8))
        if visor:
            c.box(9, 18, 23, 23, visor)
            c.rect(10, 19, 15, 19, shade(visor, 1.5))
    return draw


def vest(color, trim=None, pouches=0, collar=False):
    def draw(c):
        c.poly([(7, 6), (12, 6), (14, 10), (18, 10), (20, 6), (25, 6), (27, 12), (27, 28), (5, 28), (5, 12)], color)
        c.rect(5, 26, 27, 28, shade(color, 0.75))
        c.line([(14, 10), (18, 10)], shade(color, 0.6))
        if collar:
            c.box(9, 4, 23, 8, shade(color, 0.8))
        for i in range(pouches):
            x = 7 + i * 6
            c.box(x, 16, x + 4, 22, shade(color, 0.85))
            c.rect(x, 16, x + 4, 17, shade(color, 1.2))
        if trim:
            c.line([(5, 12), (5, 28)], trim)
            c.line([(27, 12), (27, 28)], trim)
            c.line([(5, 25), (27, 25)], trim)
    return draw


def rig(color, n):
    def draw(c):
        c.box(6, 8, 26, 12, shade(color, 0.85))
        for i in range(n):
            x = 6 + i * (21 // n)
            c.box(x, 12, x + max(3, 21 // n - 2), 24, color)
            c.rect(x, 12, x + max(3, 21 // n - 2), 14, shade(color, 1.25))
        c.line([(9, 8), (12, 3)], shade(color, 0.7), 2)
        c.line([(23, 8), (20, 3)], shade(color, 0.7), 2)
    return draw


def backpack(color, size):
    def draw(c):
        top = 10 - size * 3
        c.box(8, top, 24, 28, color)
        c.box(8, top, 24, top + 5, shade(color, 1.15))
        c.box(11, 18, 21, 26, shade(color, 0.85))
        c.rect(11, 18, 21, 19, shade(color, 1.2))
        c.line([(14, top - 2), (14, top)], shade(color, 0.6), 2)
        c.line([(18, top - 2), (18, top)], shade(color, 0.6), 2)
        if size >= 2:
            c.box(9, 26, 23, 30, rgb('#6E5B3A'))
    return draw


def cross(c, cx, cy, s, col):
    c.rect(cx - s, cy - 1 - s // 3, cx + s, cy + 1 + s // 3, col)
    c.rect(cx - 1 - s // 3, cy - s, cx + 1 + s // 3, cy + s, col)


def syringe(body, liquid):
    def draw(c):
        c.line([(6, 26), (24, 8)], body, 5)
        c.line([(8, 24), (20, 12)], liquid, 3)
        c.line([(24, 8), (28, 4)], ST, 1)
        c.line([(4, 28), (7, 25)], M, 3)
        c.line([(20, 6), (26, 12)], M, 2)
    return draw


def pill_bottle(c):
    c.box(10, 10, 22, 28, OR)
    c.box(9, 6, 23, 10, WH)
    c.box(11, 15, 21, 22, WH)
    c.rect(13, 18, 19, 18, RD)


def frag(c):
    c.ellipse(8, 9, 24, 29, OL)
    for y in (14, 19, 24):
        c.line([(9, y), (23, y)], shade(OL, 0.75))
    c.box(13, 5, 19, 9, M)
    c.line([(19, 6), (25, 14)], ST, 2)
    c.ellipse(9, 4, 13, 8, (0, 0, 0, 0))
    c.line([(10, 4), (13, 6)], LS)


def can(color, band, holes=False):
    def draw(c):
        c.box(10, 7, 22, 28, color)
        c.box(9, 5, 23, 8, M)
        c.rect(10, 14, 22, 17, band)
        if holes:
            for y in (20, 24):
                for x in (12, 16, 20):
                    c.rect(x, y, x + 1, y + 1, K)
        c.line([(23, 6), (27, 12)], ST, 2)
    return draw


def key(color, tag=None):
    def draw(c):
        c.ellipse(4, 4, 16, 16, color)
        c.ellipse(8, 8, 12, 12, (0, 0, 0, 0))
        c.line([(14, 14), (27, 27)], color, 3)
        c.line([(22, 22), (25, 19)], color, 2)
        c.line([(25, 25), (28, 22)], color, 2)
        if tag:
            c.box(2, 18, 9, 27, tag)
            c.line([(6, 18), (8, 14)], ST)
    return draw


def keycard(color):
    def draw(c):
        c.box(4, 9, 28, 25, color)
        c.rect(4, 12, 28, 14, K)
        c.box(7, 17, 12, 22, GD)
        c.rect(15, 18, 25, 18, shade(color, 1.4))
        c.rect(15, 21, 22, 21, shade(color, 1.4))
    return draw


def box_item(color, label=None, lid=None):
    def draw(c):
        c.box(6, 10, 26, 27, color)
        if lid:
            c.box(5, 8, 27, 12, lid)
        if label:
            c.box(11, 15, 21, 22, label)
    return draw


def gem(color, cut=6):
    def draw(c):
        pts = [(16, 3), (27, 11), (23, 28), (9, 28), (5, 11)]
        c.poly(pts, color)
        c.poly([(16, 3), (27, 11), (16, 13), (5, 11)], shade(color, 1.35))
        c.line([(16, 13), (16, 28)], shade(color, 0.75))
        c.line([(16, 13), (9, 28)], shade(color, 0.85))
        c.line([(16, 13), (23, 28)], shade(color, 0.85))
        c.px(12, 7, WH); c.px(13, 7, WH); c.px(12, 8, WH)
    return draw


def chip(color, pins=GD):
    def draw(c):
        for i in range(5):
            c.rect(9 + i * 3, 4, 10 + i * 3, 8, pins)
            c.rect(9 + i * 3, 24, 10 + i * 3, 28, pins)
            c.rect(4, 9 + i * 3, 8, 10 + i * 3, pins)
            c.rect(24, 9 + i * 3, 28, 10 + i * 3, pins)
        c.box(8, 8, 24, 24, color)
        c.box(12, 12, 20, 20, shade(color, 1.4))
    return draw


def board(c):
    c.box(4, 7, 28, 25, GN)
    for x, y in ((7, 10), (16, 12), (22, 18), (9, 19)):
        c.box(x, y, x + 4, y + 3, K)
    c.line([(6, 22), (14, 22), (14, 16)], GD)
    c.line([(20, 9), (20, 15), (26, 15)], GD)
    c.rect(24, 9, 26, 11, ST)


def laptop(c):
    c.box(6, 5, 26, 19, MD)
    c.box(8, 7, 24, 17, rgb('#2F6EA5'))
    c.rect(9, 8, 15, 9, CY)
    c.poly([(3, 21), (29, 21), (27, 27), (5, 27)], M)
    c.rect(6, 22, 26, 24, shade(M, 1.3))


def radio(c):
    c.box(8, 9, 24, 29, OL)
    c.line([(20, 9), (22, 1)], K, 2)
    c.box(10, 12, 22, 18, rgb('#2B2F24'))
    c.rect(11, 13, 16, 14, rgb('#9BE58A'))
    for y in (21, 24):
        for x in (11, 15, 19):
            c.rect(x, y, x + 2, y + 1, K)


def phone(c):
    c.box(10, 6, 22, 29, K)
    c.box(12, 9, 20, 17, rgb('#3D5A73'))
    c.line([(19, 6), (21, 0)], M, 2)
    for y in (20, 23, 26):
        for x in (12, 15, 18):
            c.rect(x, y, x + 1, y + 1, ST)


def flask(color):
    def draw(c):
        c.box(13, 4, 19, 12, LS)
        c.poly([(13, 12), (19, 12), (26, 28), (6, 28)], (200, 230, 240, 160))
        c.poly([(10, 19), (22, 19), (26, 28), (6, 28)], color)
        c.rect(12, 3, 20, 5, CU)
    return draw


def battery(color, big=False):
    def draw(c):
        if big:
            c.box(5, 9, 27, 27, color)
            c.box(8, 6, 12, 9, ST)
            c.box(20, 6, 24, 9, ST)
            c.rect(7, 13, 25, 15, YL)
        else:
            c.box(11, 7, 21, 28, color)
            c.box(14, 4, 18, 7, ST)
            c.rect(11, 18, 21, 28, rgb('#2E2E2E'))
    return draw


def gpu(c):
    c.box(3, 9, 29, 23, MD)
    c.ellipse(6, 11, 15, 21, M)
    c.ellipse(17, 11, 26, 21, M)
    c.ellipse(9, 14, 12, 17, K)
    c.ellipse(20, 14, 23, 17, K)
    c.rect(3, 23, 20, 25, GD)


def drone(c):
    for x, y in ((3, 6), (21, 6), (3, 20), (21, 20)):
        c.ellipse(x, y, x + 8, y + 4, ST)
    c.line([(8, 9), (24, 23)], MD, 2)
    c.line([(24, 9), (8, 23)], MD, 2)
    c.box(12, 12, 20, 20, M)
    c.ellipse(14, 15, 18, 19, K)


def lens(c):
    c.ellipse(4, 6, 26, 28, MD)
    c.ellipse(7, 9, 23, 25, rgb('#3B6E8F'))
    c.ellipse(10, 12, 16, 17, rgb('#9FD3E8'))
    c.box(22, 10, 29, 24, M)


def gold_bar(c):
    c.poly([(3, 24), (8, 14), (24, 14), (29, 24)], GD)
    c.poly([(8, 14), (24, 14), (22, 10), (10, 10)], shade(GD, 1.3))
    c.line([(3, 24), (29, 24)], shade(GD, 0.6))
    c.rect(12, 17, 20, 19, shade(GD, 0.8))


def drive(c):
    c.box(5, 6, 27, 26, LS)
    c.ellipse(9, 9, 23, 23, ST)
    c.ellipse(14, 14, 18, 18, MD)
    c.line([(16, 16), (24, 22)], K)
    c.rect(5, 26, 27, 27, RD)


def folder(c):
    c.box(4, 8, 28, 27, rgb('#C9A86A'))
    c.box(4, 6, 14, 9, rgb('#C9A86A'))
    c.rect(8, 14, 24, 19, RD)
    c.rect(10, 16, 22, 17, WH)


def reactor(c):
    c.box(9, 5, 23, 29, MD)
    c.box(11, 8, 21, 26, rgb('#1C3A2A'))
    c.rect(12, 10, 20, 24, rgb('#5CF28F'))
    c.rect(13, 12, 19, 22, rgb('#B9FFCF'))
    c.rect(8, 4, 24, 6, ST)
    c.rect(8, 28, 24, 30, ST)


def watch(c):
    c.line([(16, 1), (16, 6)], GD, 2)
    c.ellipse(5, 6, 27, 28, GD)
    c.ellipse(8, 9, 24, 25, rgb('#F4EEDC'))
    c.line([(16, 17), (16, 11)], K)
    c.line([(16, 17), (21, 19)], K)
    c.ellipse(14, 3, 18, 7, GD)


def teaset(c):
    c.ellipse(4, 12, 20, 28, GD)
    c.box(9, 9, 15, 13, GD)
    c.line([(19, 16), (25, 12)], GD, 2)
    c.ellipse(21, 20, 29, 28, shade(GD, 0.9))
    c.rect(6, 18, 18, 19, RD)


def crown(c):
    c.poly([(4, 26), (4, 10), (10, 18), (16, 6), (22, 18), (28, 10), (28, 26)], GD)
    c.rect(4, 22, 28, 26, shade(GD, 0.85))
    for x, col in ((8, RD), (16, BL), (24, GN)):
        c.ellipse(x - 2, 22, x + 1, 25, col)


def knife(c):
    c.poly([(5, 27), (21, 9), (26, 4), (24, 11), (9, 27)], rgb('#6D6A75'))
    c.line([(8, 24), (21, 10)], rgb('#A59FB3'))
    c.line([(4, 24), (9, 29)], BR, 2)
    c.line([(2, 30), (6, 26)], WD, 3)


def lion(c):
    c.box(6, 22, 26, 28, shade(GD, 0.8))
    c.ellipse(8, 8, 24, 24, GD)
    c.ellipse(11, 11, 21, 21, shade(GD, 1.2))
    c.rect(13, 15, 14, 16, K)
    c.rect(18, 15, 19, 16, K)
    c.rect(15, 18, 17, 19, shade(GD, 0.6))


def gramophone(c):
    c.box(6, 20, 26, 28, WD)
    c.line([(16, 20), (16, 14)], GD, 2)
    c.poly([(14, 15), (6, 2), (28, 2), (18, 15)], GD)
    c.ellipse(6, 1, 28, 7, shade(GD, 0.7))


def necklace(c):
    for i in range(11):
        a = math.pi * (0.1 + 0.8 * i / 10)
        x = 16 + math.cos(a) * 11
        y = 8 + math.sin(a) * 15
        c.ellipse(int(x) - 2, int(y) - 2, int(x) + 2, int(y) + 2, rgb('#F3F0EA'))
    c.ellipse(13, 23, 19, 29, rgb('#B9E6F2'))


def master_chip(c):
    chip(rgb('#2A1F3D'), GD)(c)
    c.box(13, 13, 19, 19, RD)


def star_crystal(c):
    gem(rgb('#4FA3FF'))(c)
    c.line([(16, 1), (16, 3)], WH)


def combat_knife(c):
    # Clip-point blade from the top right down to a guard and a ribbed polymer grip.
    c.poly([(13, 19), (26, 5), (29, 3), (28, 7), (16, 22)], rgb('#9AA3AB'))
    c.line([(15, 20), (27, 6)], rgb('#D7DDE2'))
    c.line([(24, 6), (29, 3)], rgb('#5D646B'))
    c.line([(10, 17), (18, 25)], K, 2)
    c.poly([(4, 27), (11, 20), (14, 23), (7, 30)], rgb('#2E3236'))
    for i in range(3):
        c.line([(6 + i * 2, 25 - i * 2), (8 + i * 2, 27 - i * 2)], rgb('#4A5056'))
    c.rect(3, 28, 5, 30, ST)


def screws(c):
    c.box(6, 12, 26, 26, rgb('#A0522D'))
    c.rect(6, 12, 26, 14, shade(rgb('#A0522D'), 1.3))
    for x in (9, 14, 19):
        c.rect(x, 7, x + 2, 13, ST)
        c.rect(x - 1, 6, x + 3, 7, LS)


def tape(c):
    c.ellipse(5, 7, 27, 27, rgb('#2B2B2B'))
    c.ellipse(11, 13, 21, 21, rgb('#C8B48A'))
    c.ellipse(13, 15, 19, 19, (0, 0, 0, 0))
    c.rect(24, 17, 30, 20, rgb('#2B2B2B'))


def lighter(c):
    c.box(10, 10, 22, 29, RD)
    c.box(10, 5, 22, 10, ST)
    c.ellipse(13, 0, 18, 6, OR)


def canned(c):
    c.box(8, 9, 24, 27, LS)
    c.rect(8, 13, 24, 22, rgb('#C0392B'))
    c.rect(11, 16, 21, 19, YL)
    c.ellipse(8, 6, 24, 11, ST)


def coil(c):
    for i in range(6):
        c.ellipse(6, 6 + i * 3, 26, 12 + i * 3, CU)
        c.ellipse(9, 7 + i * 3, 23, 11 + i * 3, shade(CU, 0.6))
    c.ellipse(9, 22, 23, 26, CU)


def water_tabs(c):
    c.box(7, 8, 25, 26, rgb('#5DADE2'))
    for y in (11, 17, 23):
        for x in (10, 15, 20):
            c.ellipse(x, y - 2, x + 3, y + 1, WH)


def toolset(c):
    c.box(4, 13, 28, 27, RD)
    c.box(11, 9, 21, 13, MD)
    c.rect(4, 18, 28, 19, shade(RD, 0.7))
    c.rect(14, 18, 18, 21, ST)


def cigarettes(c):
    c.box(4, 10, 28, 24, WH)
    c.rect(4, 10, 28, 14, RD)
    c.rect(4, 20, 28, 21, GD)


def coffee(c):
    c.box(8, 10, 24, 28, rgb('#6B3E26'))
    c.box(7, 6, 25, 10, RD)
    c.box(11, 15, 21, 23, rgb('#E8D3B0'))


def energy(c):
    c.box(10, 6, 22, 29, rgb('#1E8449'))
    c.rect(10, 6, 22, 8, ST)
    c.poly([(17, 11), (13, 19), (16, 19), (14, 26), (20, 16), (17, 16)], YL)


def multimeter(c):
    c.box(8, 4, 24, 29, YL)
    c.box(10, 6, 22, 13, rgb('#9CC98F'))
    c.ellipse(11, 15, 21, 25, K)
    c.line([(16, 20), (19, 17)], WH)


def ration(c):
    c.box(5, 8, 27, 26, rgb('#6B7B4A'))
    c.rect(5, 13, 27, 15, shade(rgb('#6B7B4A'), 0.7))
    c.box(10, 17, 22, 23, TN)


def ssd(c):
    c.box(5, 9, 27, 24, MD)
    c.rect(7, 11, 20, 17, ST)
    c.rect(24, 12, 27, 21, GD)


def terminal(c):
    c.box(7, 3, 25, 29, rgb('#3A3F47'))
    c.box(9, 6, 23, 19, rgb('#1F4E5F'))
    c.rect(10, 8, 18, 9, CY)
    c.rect(10, 11, 21, 12, CY)
    for x in (10, 15, 20):
        c.rect(x, 22, x + 2, 24, ST)


def nv_module(c):
    c.box(5, 10, 27, 24, OL)
    c.ellipse(7, 12, 15, 22, K)
    c.ellipse(17, 12, 25, 22, K)
    c.ellipse(9, 14, 13, 18, rgb('#3CF07A'))
    c.ellipse(19, 14, 23, 18, rgb('#3CF07A'))


def sample_case(c):
    c.box(3, 9, 29, 26, LS)
    c.box(12, 5, 20, 9, MD)
    c.rect(3, 16, 29, 18, YL)
    c.ellipse(13, 12, 19, 18, (0, 0, 0, 0))
    c.poly([(16, 11), (19, 16), (13, 16)], K)


def bandage(c):
    c.ellipse(6, 8, 26, 28, WH)
    c.ellipse(11, 13, 21, 23, shade(WH, 0.8))
    c.ellipse(14, 16, 18, 20, shade(WH, 0.6))
    c.rect(22, 18, 29, 22, WH)


def hemostat(c):
    c.box(5, 6, 27, 26, WH)
    c.rect(5, 6, 27, 9, RD)
    cross(c, 16, 17, 4, RD)


def splint(c):
    c.line([(6, 26), (22, 4)], TN, 4)
    c.line([(11, 28), (27, 7)], TN, 4)
    for t in (0.3, 0.65):
        x = 8 + t * 18
        y = 27 - t * 22
        c.line([(int(x) - 3, int(y) - 1), (int(x) + 5, int(y) + 3)], WH, 3)


def medkit_s(c):
    c.box(5, 9, 27, 27, RD)
    c.box(5, 9, 27, 12, shade(RD, 1.2))
    cross(c, 16, 19, 4, WH)


def medkit_l(c):
    c.box(3, 10, 29, 28, WH)
    c.box(11, 5, 21, 10, M)
    c.rect(3, 15, 29, 15, ST)
    cross(c, 16, 21, 5, RD)


def surgery(c):
    c.box(3, 8, 29, 27, rgb('#2F4F3A'))
    c.line([(3, 12), (29, 12)], GD)
    cross(c, 16, 19, 4, WH)
    c.rect(25, 10, 28, 14, ST)


ICONS = {
    # ammo
    'ammo_9_fmj': ammo(1), 'ammo_9_ap': ammo(3, ST), 'ammo_57_sb': ammo(2), 'ammo_57_ss': ammo(4, ST),
    'ammo_556_m855': ammo(3), 'ammo_556_a1': ammo(4), 'ammo_556_m995': ammo(5, ST),
    'ammo_762_ps': ammo(3, ST), 'ammo_762_bp': ammo(4, ST), 'ammo_762_ap': ammo(5, ST),
    'ammo_308_m80': ammo(3, BR, True), 'ammo_308_m61': ammo(5, BR, True),
    'ammo_338_fmj': ammo(4, BR, True), 'ammo_338_ap': ammo(6, ST, True),
    'ammo_12_buck': ammo(1, shell=RD), 'ammo_12_ap': ammo(3, shell=GN),
    'ammo_50_fmj': ammo(3, BR), 'ammo_50_ap': ammo(4, ST),
    # helmets & armor
    'helmet_1': helmet(rgb('#34495E')), 'helmet_2': helmet(OL), 'helmet_3': helmet(TN, rails=True),
    'helmet_4': helmet(rgb('#3E4D33'), visor=rgb('#22313F'), rails=True), 'helmet_5': helmet(rgb('#7F8C8D'), visor=rgb('#2C3E50'), rails=True),
    'helmet_6': helmet(K, visor=rgb('#C0A040'), full=True),
    'armor_1': vest(rgb('#7F8C8D')), 'armor_2': vest(rgb('#2C3E50'), pouches=2), 'armor_3': vest(OL, pouches=3),
    'armor_4': vest(TN, pouches=3), 'armor_5': vest(rgb('#3B3F45'), pouches=3, collar=True),
    'armor_6': vest(K, trim=GD, pouches=3, collar=True),
    'rig_1': rig(rgb('#7D7A6A'), 2), 'rig_2': rig(OL, 3), 'rig_3': rig(rgb('#2E2F2B'), 4),
    'pack_1': backpack(rgb('#6E7273'), 0), 'pack_2': backpack(OL, 1), 'pack_3': backpack(rgb('#4A5A3A'), 2),
    # medical
    'bandage': bandage, 'hemostat': hemostat, 'splint': splint, 'medkit_s': medkit_s, 'medkit_l': medkit_l,
    'surgery': surgery, 'painkiller': pill_bottle, 'adrenaline': syringe(LS, YL), 'revive_kit': syringe(GD, RD),
    # throwables
    'frag': frag, 'smoke': can(ST, GN), 'flash': can(K, YL, True),
    # keys
    'key_dorm': key(BR), 'key_wh': key(LS, BL), 'key_hq': keycard(RD),
    # valuables
    'screws': screws, 'tape': tape, 'battery': battery(BL), 'lighter': lighter, 'canned_food': canned,
    'copper_coil': coil, 'water_tabs': water_tabs, 'circuit': board, 'toolset': toolset, 'cigarettes': cigarettes,
    'coffee': coffee, 'energy_drink': energy, 'multimeter': multimeter, 'ration': ration, 'ssd': ssd,
    'radio': radio, 'reagent': flask(rgb('#7FD35C')), 'battery_pack': battery(OL, True), 'nv_module': nv_module,
    'terminal': terminal, 'laptop': laptop, 'sat_phone': phone, 'sample_case': sample_case, 'gpu': gpu,
    'drone': drone, 'optics': lens, 'gold_bar': gold_bar, 'server_drive': drive, 'dossier': folder,
    'reactor_cell': reactor,
    # collectibles
    'pocket_watch': watch, 'gilded_teaset': teaset, 'star_crystal': star_crystal, 'gold_crown': crown,
    'quantum_cpu': chip(rgb('#1B4F72'), CY), 'meteor_knife': knife, 'lion_statue': lion, 'gramophone': gramophone,
    'pearl_necklace': necklace, 'master_chip': master_chip,
    # melee
    'knife_starter': combat_knife,
}


def render(item_id):
    fn = ICONS.get(item_id)
    c = Canvas()
    if fn is None:
        c.box(8, 8, 24, 24, M)
        return c.outline().img
    fn(c)
    return c.outline().img
