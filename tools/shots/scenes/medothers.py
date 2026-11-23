"""Medical items in another player's hands, as everyone else sees them use one (#41). Arg: an item
id (medkit_l); without one, all nine.

The other player is a mineflayer bot (tools/bot/puppet.js), so tools/bot needs its node_modules.
Both queue for the proving ground and land in the same raid. Before each use the bot is healed and
given a light bleed, a broken leg and 40 damage, so every item has something to treat; then it
holds right mouse while the camera player watches from in front, a little to its right (the side
of the hand holding the item), and the frames are shot at 15, 35, 60 and 85% of the use time.
The self-revive injector goes last: the bot carries it, takes a lethal hit, goes down and uses it
lying there, watched from its right side. The HUD is hidden (F1).
Shots: medothers-<item>-<15|35|60|85>.
"""
import re
import time
from pathlib import Path

ITEMS = ['bandage', 'hemostat', 'splint', 'medkit_s', 'medkit_l', 'surgery', 'painkiller', 'adrenaline', 'revive_kit']
SHARES = (0.15, 0.35, 0.6, 0.85)
ITEMS_YML = Path(__file__).resolve().parents[3] / 'src' / 'main' / 'resources' / 'items.yml'
SPOT = (45.5, 65, 91.0)


def use_seconds(item):
    line = re.search(rf'(?m)^  {item}:.*med: {{ use: ([\d.]+)', ITEMS_YML.read_text(encoding='utf-8'))
    return float(line.group(1)) if line else 3.0


def run(s):
    items = [s.arg] if s.arg else ITEMS
    s.lobby()
    with s.puppet() as mate:
        s.rcon(f'op {mate.name}')
        try:
            before = s.raids()
            s.cmd('cv queue proving NORMAL SOLO')
            mate.send('chat /cv queue proving NORMAL SOLO')
            s.wait_until(lambda: s.in_raid() and 'craftov_raid' in (s.data('Dimension', mate.name) or ''), 150,
                         'both players in the proving ground raid')
            mine = s.raids() - before
            s.wait(6)   # insertion camera and spawn protection
            s.rcon(f'effect give {mate.name} minecraft:night_vision 600 0 true')
            s.key('F1')
            try:
                for item in items:
                    use(s, mate, item)
            finally:
                s.key('F1')
                mate.send('chat /cv lobby')
        finally:
            s.rcon(f'deop {mate.name}')
    s.extract('proving', mine)


def face(s, mate):
    """Puts the bot on SPOT facing north, its body too: other clients turn a standing player's body
    after its head only slowly and never all the way (orbit places the camera by the head), but
    turn it to where the player walks. So the bot walks the last two blocks, in small teleports."""
    x, y, z = SPOT
    # Absolute steps: a step along the bot's own facing (^) would follow whatever it looks at.
    for i in range(9):   # 0.25 block every other tick: clearly walking
        s.rcon(f'execute as {mate.name} at @s run tp @s {x} {y} {z + 2 - 0.25 * i} 180 0')
        s.wait(0.3 if i == 0 else 0.1)
    s.wait(0.5)


def use(s, mate, item):
    mate.hold(item)
    face(s, mate)
    s.rcon(f'effect give {mate.name} minecraft:instant_health 1 10 true')
    s.wait(0.6)
    s.rcon(f'cv injure {mate.name} left_leg light')
    s.rcon(f'cv injure {mate.name} left_leg fracture')
    if item == 'revive_kit':
        s.rcon(f'damage {mate.name} 500 minecraft:generic')   # carrying the injector: down, not dead
        s.wait(1.5)
        mate.orbit(90, distance=2.2)
        s.rcon(f'execute as {s.name} at @s run tp @s ~ ~ ~ ~ 35')   # down at the body on the floor
        s.wait(0.5)
    else:
        s.rcon(f'damage {mate.name} 40 minecraft:generic')
        s.wait(1.0)
        mate.orbit(25, distance=2.6)
    seconds = use_seconds(item)
    mate.send('use on')
    start = time.time() - 0.3   # send() waits 0.3 s after writing the command
    for share in SHARES:
        delay = start + seconds * share - time.time()
        if delay > 0:
            s.wait(delay)
        s.shot(f'medothers-{item}-{int(share * 100)}', quick=True)
    s.wait(max(0.0, start + seconds + 0.4 - time.time()))
    mate.send('use off')
    s.wait(1.0)
    s.develop()
