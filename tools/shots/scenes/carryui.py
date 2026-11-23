"""The carry interface (#10): the inventory screen, the stash and the search screen.

Needs a player with the starter rig and backpack (run with --fresh the first time). Puts one item of
every rarity on the player and shoots the inventory screen (carry-inventory), the tooltip of the
first locked rig cell (carry-locked) and of the load cell (carry-load); then stores some of it and
shoots the stash with two items picked and the sell button armed (carry-stash); then searches the
armory crate on the proving ground (carry-loot) and extracts. Arg `noraid` skips the raid.
"""

import math

# One of each rarity, cheapest first: white, green, blue, purple, gold, red.
LOOT = ['screws', 'circuit', 'medkit_l', 'laptop', 'optic_8x', 'lion_statue']


def inventory_slot(s, slot):
    """Screen pixel of carry cell `slot` (0-8 hotbar, 9-35 rows) or crafting raw slot ('raw', n) on the
    inventory screen."""
    width, height = (int(v) for v in s.cfg.size.split('x'))
    scale = s.gui_scale()
    left = (math.ceil(width / scale) - 176) // 2
    top = (math.ceil(height / scale) - 166) // 2
    if isinstance(slot, tuple):
        x, y = {0: (154, 28), 1: (98, 18), 2: (116, 18), 3: (98, 36), 4: (116, 36)}[slot[1]]
    elif slot < 9:
        x, y = 8 + 18 * slot, 142
    else:
        x, y = 8 + 18 * ((slot - 9) % 9), 84 + 18 * ((slot - 9) // 9)
    return (left + x + 8) * scale, (top + y + 8) * scale


def first_locked(s, start, count):
    """The first carry cell of a zone holding a locked placeholder."""
    for slot in range(start, start + count):
        tag = s.data(f'Inventory[{{Slot:{slot}b}}].components."minecraft:custom_data".PublicBukkitValues."craftov:ui"')
        if tag and 'locked' in tag:
            return slot
    return None


def run(s):
    s.lobby()
    s.clear_chat()
    for item in LOOT:
        s.give(item)
        s.wait(0.3)
    s.wait(1.5)
    s.key('e')
    s.wait(1.2)
    try:
        s.park_mouse()
        s.shot('carry-inventory')
        locked = first_locked(s, 9, 9)
        if locked is not None:
            s.mouse_to(*inventory_slot(s, locked))
            s.wait(0.8)
            s.shot('carry-locked')
        s.mouse_to(*inventory_slot(s, ('raw', 1)))
        s.wait(0.8)
        s.shot('carry-load')
    finally:
        s.park_mouse()
        s.key('Escape')
        s.wait(0.6)

    s.cmd('cv stash')
    s.wait(1.5)
    try:
        # Store the backpack row (inventory 18..23 = chest-menu slots 63..68) so the stash has the finds.
        for index in range(63, 69):
            s.click_slot(index, 6)
        s.park_mouse()
        s.wait(0.5)
        s.down('shift')
        s.click_slot(9, 6)
        s.click_slot(10, 6)
        s.up('shift')
        s.click_slot(48, 6)
        s.park_mouse()
        s.wait(0.5)
        s.shot('carry-stash')
        s.mouse_to(*s.slot_center(48, 6))
        s.wait(0.8)
        s.shot('carry-stash-sell')
    finally:
        s.park_mouse()
        s.key('Escape')
        s.wait(0.6)

    if s.arg == 'noraid':
        return
    before = s.raids()
    s.cmd('cv queue proving NORMAL SOLO')
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    mine = s.raids() - before
    try:
        s.wait(5)
        s.select(2)
        s.rcon(f'execute as {s.name} at @s run tp @s 8.5 65 78.9 180 55')
        s.wait(1.5)
        s.xdo('click', '3')
        s.wait(1.2)
        s.park_mouse()
        s.wait(3.5)
        s.shot('carry-loot')
        s.mouse_to(*s.slot_center(52, 6))
        s.wait(0.8)
        s.shot('carry-loot-load')
        s.park_mouse()
        s.key('Escape')
        s.wait(0.8)
        s.look(0, 0)
    finally:
        s.extract('proving', mine)
