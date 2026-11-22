"""Knife inspect (F) in first person: the knife at rest, then six frames through the inspect.

Arg: a knife id (knife_karambit); without one, all four knives. Shots: inspect-<knife>-0 (at rest)
to inspect-<knife>-6, taken 0.3, 0.65, 1.0, 1.4, 1.8 and 2.2 s after F (quick shots, so the times
hold); the inspect runs for combat.melee.inspect-ticks (50, 2.5 s). The knife goes into the melee
slot (hotbar key 3) in place of the one there. The lobby is dim and a held item takes the light
where the player stands, so the player gets night vision meanwhile to keep the blade readable.
"""
import time

KNIVES = ['knife_starter', 'knife_tactical', 'knife_machete', 'knife_karambit']
TIMES = (0.3, 0.65, 1.0, 1.4, 1.8, 2.2)


def hold_knife(s, knife):
    if s.item_in(2) != knife:
        s.rcon(f'item replace entity {s.name} hotbar.2 with minecraft:air')
        s.wait(0.3)
        s.give(knife)
        s.wait_until(lambda: s.item_in(2) == knife, 10, f'{knife} in the melee slot')
    s.select(2)


def run(s):
    knives = [s.arg] if s.arg else KNIVES
    s.lobby()
    s.rcon(f'effect give {s.name} minecraft:night_vision 120 0 true')
    try:
        for knife in knives:
            inspect(s, knife)
    finally:
        s.rcon(f'effect clear {s.name} minecraft:night_vision')


def inspect(s, knife):
    hold_knife(s, knife)
    s.clear_chat()
    s.wait(1.5)
    s.shot(f'inspect-{knife}-0')
    s.focus()
    s.xdo('key', 'f')
    pressed = time.time()
    for i, at in enumerate(TIMES, 1):
        s.wait(max(0.0, pressed + at - time.time()))
        s.shot(f'inspect-{knife}-{i}', quick=True)
    s.wait(1.0)
