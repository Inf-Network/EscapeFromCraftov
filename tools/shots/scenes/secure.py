"""The secure container by size and while downed (#37).

In the lobby the container is upgraded to 9 cells at the trader (money from /cv money), then gets a
lion statue (6 cells), a master chip (2) and a box of 60 rounds, of which only 30 fit: secure-cells,
then the tooltips of an occupied cell (secure-occupied), of the statue (secure-tooltip, the weight
line ends with its cells) and of the info button (secure-info). Everything is taken back out.

Then a solo proving-ground raid with a self-revive injector: knocked down, /cv secure is refused on
the bleed-out bar (downed-bar), and the terminal shows the secure container, gunsmith and skill
locked (downed-terminal, downed-terminal-secure with the locked button's tooltip). The injector
stands the player up and the raid is left through the always-open extract.

`secure:lobby` and `secure:raid` run one half each. The upgrade spends 2.63 million of money it
hands out first, so run it with `--fresh` or on a throwaway profile.
"""

ROWS = 2  # the secure container's menu


def menu_index(slot, rows):
    """Menu slot of a player inventory slot under a chest menu (main rows, then the hotbar)."""
    return rows * 9 + (slot - 9) if slot >= 9 else rows * 9 + 27 + slot


def where(s, item):
    return next((slot for slot in range(36) if s.item_in(slot) == item), None)


def put(s, item):
    slot = where(s, item)
    if slot is None:
        raise RuntimeError(f'{item} is not in the inventory')
    s.click_slot(menu_index(slot, ROWS), ROWS)
    s.wait(0.6)


def upgrade(s):
    s.rcon(f'cv money {s.name} 3000000')
    s.cmd('cv trader')
    s.wait(1.5)
    s.click_slot(53, 6)  # the 服务 tab
    s.wait(0.8)
    for _ in range(3):
        s.click_slot(10, 6)  # 升级安全箱 (3 -> 5 -> 7 -> 9 cells; no-op once at the top)
        s.wait(0.6)
    s.park_mouse()
    s.key('Escape')
    s.wait(0.8)


def lobby_part(s):
    s.lobby()
    upgrade(s)
    for item, n in (('lion_statue', 1), ('master_chip', 1), ('ammo_556_m855', 60)):
        s.give(item, n)
    s.wait(1)
    s.clear_chat()
    s.cmd('cv secure')
    s.wait(1.5)
    try:
        for item in ('lion_statue', 'master_chip', 'ammo_556_m855'):
            put(s, item)
        s.park_mouse()
        s.wait(2.5)  # the "only 30 went in" note after the title fades
        s.shot('secure-cells')
        s.mouse_to(*s.slot_center(3, ROWS))  # a cell the statue occupies
        s.wait(0.7)
        s.shot('secure-occupied')
        s.mouse_to(*s.slot_center(0, ROWS))  # the statue itself
        s.wait(0.7)
        s.shot('secure-tooltip')
        s.mouse_to(*s.slot_center(13, ROWS))
        s.wait(0.7)
        s.shot('secure-info')
        for cell in (8, 6, 0):  # everything back out, last first so the indices hold
            s.click_slot(cell, ROWS)
            s.wait(0.5)
    finally:
        s.park_mouse()
        s.key('Escape')
        s.wait(0.8)


def raid_part(s):
    before = s.raids()
    s.cmd('cv queue proving NORMAL SOLO')
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    mine = s.raids() - before
    try:
        s.wait(5)
        # A raid hands new items to the pockets first: the injector is moved to a quick slot by hand.
        if where(s, 'revive_kit') is None:
            s.give('revive_kit')
            s.wait(0.8)
        slot = where(s, 'revive_kit')
        if slot is None:
            raise RuntimeError('the injector did not reach the inventory')
        injector = slot
        if slot >= 9:
            injector = 6
            s.rcon(f'item replace entity {s.name} hotbar.{injector} from entity {s.name} inventory.{slot - 9}')
            s.rcon(f'item replace entity {s.name} inventory.{slot - 9} with minecraft:air')
            s.wait(0.5)
        s.select(0)
        s.look(0, 0)
        s.rcon(f'damage {s.name} 500 minecraft:generic')
        s.wait(4)  # the knock-down title fades
        s.clear_chat()
        s.cmd('cv secure')
        s.wait(0.8)
        s.shot('downed-bar')
        s.cmd('cv')
        s.wait(1.5)
        s.park_mouse()
        s.shot('downed-terminal')
        s.mouse_to(*s.slot_center(10, 3))
        s.wait(0.7)
        s.shot('downed-terminal-secure')
        s.park_mouse()
        s.key('Escape')
        s.wait(0.8)
        s.select(injector)
        s.wait(0.5)
        with s.mouse(3):
            s.wait(5)
        s.wait(1)
    finally:
        s.extract('proving', mine)


def run(s):
    """`secure:lobby` or `secure:raid` runs one half; plain `secure` both."""
    if s.arg != 'raid':
        lobby_part(s)
    if s.arg != 'lobby':
        raid_part(s)
