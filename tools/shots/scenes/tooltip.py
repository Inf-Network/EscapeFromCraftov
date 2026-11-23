"""Tooltips: items framed in their rarity colour, interface buttons in the plain dark style.

Hands out one item of each rarity, opens the stash and hovers each where it landed in the player's
inventory, then a toolbar button. Shots: tooltip-<rarity> and tooltip-button.
"""

ITEMS = [('white', 'bandage'), ('green', 'p18'), ('purple', 'armor_4'), ('gold', 'gold_bar'), ('red', 'star_crystal')]


def menu_index(slot):
    """Menu slot of a player inventory slot under a 6-row chest (main rows 54..80, hotbar 81..89)."""
    return 54 + (slot - 9) if slot >= 9 else 81 + slot


def run(s):
    s.lobby()
    for _, item in ITEMS:
        s.give(item)
    s.wait(1)
    found = {}
    for slot in range(36):
        item = s.item_in(slot)
        if item and item not in found:
            found[item] = slot
    s.clear_chat()
    s.cmd('cv stash')
    s.wait(1.5)
    try:
        for rarity, item in ITEMS:
            if item not in found:
                continue
            s.mouse_to(*s.slot_center(menu_index(found[item]), 6))
            s.wait(0.7)
            s.shot('tooltip-' + rarity)
        s.mouse_to(*s.slot_center(49, 6))
        s.wait(0.7)
        s.shot('tooltip-button')
    finally:
        s.park_mouse()
        s.key('Escape')
