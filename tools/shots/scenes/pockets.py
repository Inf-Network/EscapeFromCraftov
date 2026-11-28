"""Pockets without a rig or backpack (#176), then the stash's loadout panel (#185)."""

import math

LOOT = ['gold_bar', 'circuit', 'laptop', 'screws', 'lion_statue']


def chest_slot(s, slot, rows=6):
    """Screen pixel of the middle of chest slot `slot` in a `rows`-row chest window."""
    width, height = (int(v) for v in s.cfg.size.split('x'))
    scale = s.gui_scale()
    left = (math.ceil(width / scale) - 176) // 2
    top = (math.ceil(height / scale) - (rows * 18 + 17 + 96)) // 2
    return (left + 8 + 18 * (slot % 9) + 8) * scale, (top + 18 + 18 * (slot // 9) + 8) * scale


def run(s):
    s.lobby()
    s.select(0)
    for part in ('armor.legs', 'armor.feet'):
        s.rcon(f'item replace entity {s.name} {part} with air')
    for k in range(3, 8):
        s.rcon(f'item replace entity {s.name} hotbar.{k} with air')
    for item in LOOT:
        s.give(item)
        s.wait(0.3)
    s.clear_chat()
    s.key('e')
    s.wait(1.2)
    try:
        s.park_mouse()
        s.shot('pockets-inventory')
    finally:
        s.key('Escape')
    s.wait(0.8)
    s.give('rig_1')
    s.give('pack_1')
    s.wait(0.8)
    s.cmd('cv stash')
    s.wait(1.5)
    try:
        s.park_mouse()
        s.shot('stash-loadout')
        s.mouse_to(*chest_slot(s, 24))
        s.wait(0.8)
        s.shot('stash-loadout-tip')
    finally:
        s.key('Escape')
