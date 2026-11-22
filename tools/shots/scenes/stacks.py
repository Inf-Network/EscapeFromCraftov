"""Stackable items in the inventory (#43): stacks given 23 + 37 rounds apart make one full stack, and
every stack's tooltip gives one unit's weight and price, the same whatever its size. Shots the
tooltips of the full ammo stack, of a 5-round stack next to it, and of a stack of screw boxes.
The argument picks the ammo (default ammo_556_m855). Clears the camera player's inventory first."""

import math


def slot_center(s, slot):
    """Screen pixel of an inventory slot (0-8 hotbar, 9-35 main rows) on the player inventory screen."""
    width, height = (int(v) for v in s.cfg.size.split('x'))
    scale = max(1, min(width // 320, height // 240))  # the client's automatic GUI scale
    left = (math.ceil(width / scale) - 176) // 2
    top = (math.ceil(height / scale) - 166) // 2
    if slot < 9:
        x, y = 8 + 18 * slot, 142
    else:
        x, y = 8 + 18 * ((slot - 9) % 9), 84 + 18 * ((slot - 9) // 9)
    return (left + x + 8) * scale, (top + y + 8) * scale


def run(s):
    ammo = s.arg or 'ammo_556_m855'
    s.lobby()
    s.rcon(f'clear {s.name}')
    s.wait(1.5)
    for item, amount in ((ammo, 23), (ammo, 37), (ammo, 5), ('screws', 1), ('screws', 2)):
        s.give(item, amount)
        s.wait(0.4)
    s.wait(1)
    slots = [slot for slot in range(36) if s.item_in(slot) in (ammo, 'screws')]
    counts = {slot: s.data(f'Inventory[{{Slot:{slot}b}}].count') for slot in slots}
    ammo_slots = [slot for slot in slots if s.item_in(slot) == ammo]
    screw_slots = [slot for slot in slots if s.item_in(slot) == 'screws']
    print(f'stacks: {ammo} in {[(slot, counts[slot]) for slot in ammo_slots]}, '
          f'screws in {[(slot, counts[slot]) for slot in screw_slots]}', flush=True)
    s.clear_chat()
    s.key('e')
    s.wait(1)
    try:
        names = ['stacks-ammo-full', 'stacks-ammo-small']
        for name, slot in zip(names, ammo_slots):
            s.mouse_to(*slot_center(s, slot))
            s.wait(0.8)
            s.shot(name)
        if screw_slots:
            s.mouse_to(*slot_center(s, screw_slots[0]))
            s.wait(0.8)
            s.shot('stacks-screws')
    finally:
        s.park_mouse()
        s.key('Escape')
