"""Knife skins (#106): the 外观 menu, and every skin in first person at rest and mid-inspect.

Arg: `menu` (the menu only), a knife id (that knife's skins only) or a skin id (that one), or nothing
(the menu and all twelve skins). Shots: skins-menu-<knife> (the menu on each knife's tab, pointer
parked) and skins-menu-card (the pointer on a skin card, its tooltip showing); skin-<id>-rest (the
skin worn, knife in hand) and skin-<id>-inspect (after F, where that knife's inspect holds it flat
to the camera). Skins go on through the menu (a left click on the card), so the scene also shows
that equipping works; every knife gets its own look back at the end. The lobby is dim and a held item takes the
light where the player stands, so the player has night vision meanwhile (as in the inspect scene).
"""
import re
import time
from pathlib import Path

KNIVES = ['knife_starter', 'knife_tactical', 'knife_machete', 'knife_karambit']
CARDS = [10, 12, 14, 16]   # a cell of each card (row 1): the knife's own look, then its three skins
TAB = {k: 9 + 9 * i for i, k in enumerate(KNIVES)}
# Seconds after F when each knife's inspect (tools/art/knife_inspect.py) shows the flat of the blade.
INSPECT_AT = {'knife_starter': 0.7, 'knife_tactical': 1.35, 'knife_machete': 0.9, 'knife_karambit': 1.8}


def catalogue():
    """skin id -> knife, in menu order, from skins.yml in the repository."""
    text = (Path(__file__).resolve().parents[3] / 'src' / 'main' / 'resources' / 'skins.yml').read_text(encoding='utf-8')
    return dict((sid, knife) for sid, knife in re.findall(r'(?m)^  (\w+):\s*\n    knife: (\w+)', text))


def hold_knife(s, knife):
    if s.item_in(2) != knife:
        s.rcon(f'item replace entity {s.name} hotbar.2 with minecraft:air')
        s.wait(0.3)
        s.give(knife)
        s.wait_until(lambda: s.item_in(2) == knife, 10, f'{knife} in the melee slot')
    s.select(2)


def model_in_hand(s):
    return (s.data('Inventory[{Slot:2b}].components."minecraft:item_model"') or '').strip('"')


def open_menu(s):
    s.cmd('cv skins')
    s.wait(1.5)


def menu(s):
    hold_knife(s, 'knife_starter')
    open_menu(s)
    try:
        for knife in KNIVES:
            s.click_slot(TAB[knife], 6)
            s.park_mouse()
            s.wait(0.6)
            s.shot('skins-menu-' + knife)
        s.click_slot(TAB['knife_tactical'], 6)
        s.mouse_to(*s.slot_center(CARDS[2], 6))
        s.wait(0.8)
        s.shot('skins-menu-card')
    finally:
        s.key('Escape')
        s.wait(0.5)


def wear(s, knife, card):
    """Puts the look on card `card` (0: the knife's own) on through the menu."""
    open_menu(s)
    try:
        s.click_slot(TAB[knife], 6)
        s.click_slot(CARDS[card], 6)
    finally:
        s.key('Escape')
        s.wait(0.8)


def skin(s, sid, knife, card):
    hold_knife(s, knife)
    wear(s, knife, card)
    s.wait_until(lambda: model_in_hand(s) == 'craftov:knife_skin/' + sid, 10, f'{sid} on the knife')
    s.select(2)
    s.clear_chat()
    s.wait(1.5)
    s.shot(f'skin-{sid}-rest')
    s.focus()
    s.xdo('key', 'f')
    pressed = time.time()
    s.wait(max(0.0, pressed + INSPECT_AT[knife] - time.time()))
    s.shot(f'skin-{sid}-inspect', quick=True)
    s.wait(3.0 - INSPECT_AT[knife])


def run(s):
    skins = catalogue()
    s.lobby()
    s.rcon(f'effect give {s.name} minecraft:night_vision 600 0 true')
    try:
        if s.arg in (None, 'menu'):
            menu(s)
        if s.arg == 'menu':
            return
        for knife in KNIVES:
            own = [sid for sid, k in skins.items() if k == knife]
            for card, sid in enumerate(own, 1):
                if s.arg in (None, knife, sid):
                    skin(s, sid, knife, card)
            if s.arg in (None, knife) or s.arg in own:
                wear(s, knife, 0)
    finally:
        s.rcon(f'effect clear {s.name} minecraft:night_vision')
        hold_knife(s, 'knife_starter')
