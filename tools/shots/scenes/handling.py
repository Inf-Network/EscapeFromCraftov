"""Handling (issue #117): 操控速度 on the tooltips and in the gunsmith. No arg.

In the lobby, with an SV9, an M4C and an AWR on the hotbar and an 8x scope, a red dot, a vertical grip
and a suppressor in a backpack (parts only go in a rig or a backpack): handling-tooltip-sv9 / -m4c / -awr, each gun's tooltip (the 操控
bar among the stat bars, and the 操控速度 line under them: its number and what it comes to, the ads,
draw and sprint-out times); handling-tooltip-8x, the scope's tooltip (操控速度 -24). Then the gunsmith
with the M4C: handling-gunsmith-a..d, the pointer on each part in the bottom row, whose preview shows
the bars it would move (the 8x the 操控 bar in red, the grip in green).

The window is 1600x900 at GUI scale 3 (shots.py's default size), which the slot positions assume.
"""

SCALE = 3
GUNS = ('sv9', 'm4c', 'awr')
PARTS = ('optic_8x', 'optic_reddot', 'att_vgrip', 'att_suppressor')


def screen(gx, gy):
    return gx * SCALE, gy * SCALE


def hotbar_in_inventory(i):
    """Centre of hotbar slot i in the player inventory screen (176x166, centred on 533x300)."""
    left, top = (533 - 176) // 2, (300 - 166) // 2
    return screen(left + 8 + 18 * i + 8, top + 142 + 8)


def main_in_inventory(slot):
    """Centre of main inventory slot 9..35 in the player inventory screen."""
    left, top = (533 - 176) // 2, (300 - 166) // 2
    k = slot - 9
    return screen(left + 8 + 18 * (k % 9) + 8, top + 84 + 18 * (k // 9) + 8)


def chest_slot(k):
    """Centre of slot k of a six-row chest menu (176x222, centred)."""
    left, top = (533 - 176) // 2, (300 - 222) // 2
    return screen(left + 8 + 18 * (k % 9) + 8, top + 18 + 18 * (k // 9) + 8)


def run(s):
    s.lobby()
    s.rcon(f'clear {s.name}')
    s.wait(0.5)
    s.give('pack_2')  # worn at once: the parts go in it
    s.wait(0.5)
    for item in GUNS + PARTS:
        s.give(item)
    s.wait(1.2)
    s.hold('m4c')
    s.wait(1)
    s.clear_chat()

    s.key('e')
    s.wait(1)
    try:
        for gun in GUNS:
            s.mouse_to(*hotbar_in_inventory(s.hotbar_slot(gun)))
            s.wait(0.8)
            s.shot('handling-tooltip-' + gun)
        scope = next((slot for slot in range(36) if s.item_in(slot) == 'optic_8x'), None)
        if scope is not None:
            s.mouse_to(*(hotbar_in_inventory(scope) if scope < 9 else main_in_inventory(scope)))
            s.wait(0.8)
            s.shot('handling-tooltip-8x')
    finally:
        s.park_mouse()
        s.key('Escape')
    s.wait(0.5)

    s.cmd('cv mod')
    s.wait(1.5)
    try:
        for k, suffix in enumerate('abcd'):
            s.mouse_to(*chest_slot(45 + k))
            s.wait(0.9)
            s.shot('handling-gunsmith-' + suffix)
    finally:
        s.park_mouse()
        s.key('Escape')
    s.wait(0.5)
    s.rcon(f'clear {s.name}')
