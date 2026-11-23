"""Medical items in use (#41): a solo proving-ground raid, each item used with right mouse held and
shot at 15, 35, 60 and 85% of its use time (items.yml med.use), in the first person and from the
front (F5 twice: the item in the hand of the player model, as others see it).

Arg: an item id (bandage); without one, all nine. Before each use the player is healed and given a
light bleed, a broken leg and 40 damage, so every item has something to treat; the self-revive
injector goes last: the player carries it, takes a lethal hit, goes down and uses it lying there,
in the first person only (F5 puts the camera of a player lying down inside the body). The scene
`medothers` shows every item as other players see it.
Shots: meduse-<item>-<fp|front>-<15|35|60|85> (quick shots, so the times hold). Night vision keeps
the held item lit wherever the player stands.
"""
import re
import time
from pathlib import Path

ITEMS = ['bandage', 'hemostat', 'splint', 'medkit_s', 'medkit_l', 'surgery', 'painkiller', 'adrenaline', 'revive_kit']
SHARES = (0.15, 0.35, 0.6, 0.85)
ITEMS_YML = Path(__file__).resolve().parents[3] / 'src' / 'main' / 'resources' / 'items.yml'


def use_seconds(item):
    line = re.search(rf'(?m)^  {item}:.*med: {{ use: ([\d.]+)', ITEMS_YML.read_text(encoding='utf-8'))
    return float(line.group(1)) if line else 3.0


def run(s):
    items = [s.arg] if s.arg else ITEMS
    s.lobby()
    before = s.raids()
    s.cmd('cv queue proving NORMAL SOLO')
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    mine = s.raids() - before
    try:
        s.wait(5)
        s.rcon(f'effect give {s.name} minecraft:night_vision 600 0 true')
        s.look(0, 10)
        for item in items:
            for view in ('fp',) if item == 'revive_kit' else ('fp', 'front'):
                use(s, item, view)
    finally:
        s.rcon(f'effect clear {s.name} minecraft:night_vision')
        s.extract('proving', mine)


def wound(s, item):
    s.rcon(f'effect give {s.name} minecraft:instant_health 1 10 true')
    s.wait(0.6)
    s.rcon(f'cv injure {s.name} left_leg light')
    s.rcon(f'cv injure {s.name} left_leg fracture')
    if item == 'revive_kit':
        s.rcon(f'damage {s.name} 500 minecraft:generic')   # carrying the injector: down, not dead
    else:
        s.rcon(f'damage {s.name} 40 minecraft:generic')
    s.wait(1.2)


def use(s, item, view):
    s.hold(item)
    wound(s, item)
    s.clear_chat()
    if view == 'front':
        s.key('F5', 'F5')
        s.wait(0.6)
    seconds = use_seconds(item)
    with s.mouse(3):
        start = time.time()
        for share in SHARES:
            delay = start + seconds * share - time.time()
            if delay > 0:
                s.wait(delay)
            s.shot(f'meduse-{item}-{view}-{int(share * 100)}', quick=True)
        s.wait(max(0.0, start + seconds + 0.4 - time.time()))
    if view == 'front':
        s.key('F5')
    s.wait(1.0)
    s.develop()
