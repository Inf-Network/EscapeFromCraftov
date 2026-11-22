"""Gun feel (issue #8): stat bars, the kick and flash of real shots, the crosshair and the kill feed. No arg.

In the lobby: the K47's tooltip in the inventory (feel-tooltip: the seven stat bars) and the gunsmith
with a vertical grip to fit, the pointer on the grip (feel-gunsmith: the accuracy bar it would add,
in green). Then a solo raid on the proving ground with the K47: the crosshair standing (feel-cross-
stand) and strafing (feel-cross-strafe: wider), none while aiming (feel-ads); a 0.4 s full-auto
burst captured every 0.07 s (feel-burst-a..f: each round snaps the gun back with the flash, the
frames between show it settling); one AWR shot captured through its 0.3 s kick (feel-awr-a..e). Last
two soldiers frozen at 1 health are shot one after the other (feel-feed: two lines in the kill
feed, top right, and the 双杀 badge over the crosshair). The camera leaves through the extract.

The window is 1600x900 at GUI scale 3 (shots.py's default size), which the slot positions assume.
"""

import threading
import time

AREA = ('39 64 85 52 64 100 minecraft:stone', '39 65 85 52 70 100 minecraft:air')
SCALE = 3


def screen(gx, gy):
    """GUI pixel -> screen pixel, at GUI scale 3."""
    return gx * SCALE, gy * SCALE


def hotbar_in_inventory(i):
    """Centre of hotbar slot i in the player inventory screen (176x166, centred on 533x300)."""
    left, top = (533 - 176) // 2, (300 - 166) // 2
    return screen(left + 8 + 18 * i + 8, top + 142 + 8)


def chest_slot(k):
    """Centre of slot k of a six-row chest menu (176x222, centred)."""
    left, top = (533 - 176) // 2, (300 - 222) // 2
    return screen(left + 8 + 18 * (k % 9) + 8, top + 18 + 18 * (k // 9) + 8)


def burst(s, prefix, count, every):
    """Captures `count` screenshots `every` seconds apart: one capture takes about 0.3 s."""
    threads = []
    for suffix in 'abcdefgh'[:count]:
        threads.append(threading.Thread(target=s.shot, args=(f'{prefix}-{suffix}',)))
        threads[-1].start()
        time.sleep(every)
    for thread in threads:
        thread.join()


def run(s):
    s.lobby()
    for item, amount in (('k47', 1), ('awr', 1), ('att_vgrip', 1)):
        if s.count(item) < 1:
            s.give(item, amount)
    for gun in ('k47', 'awr'):
        ammo = s.ammo_for(gun)
        if ammo and s.count(ammo) < 40:
            s.give(ammo, 60)
    slot = s.hold('k47')
    s.wait(1)
    s.clear_chat()

    # The tooltip: stat bars.
    s.key('e')
    s.wait(1)
    try:
        s.mouse_to(*hotbar_in_inventory(slot))
        s.wait(1)
        s.shot('feel-tooltip')
    finally:
        s.key('Escape')
    s.wait(0.5)

    # The gunsmith: the grip's preview.
    s.cmd('cv mod')
    s.wait(1.5)
    try:
        s.mouse_to(*chest_slot(45))
        s.wait(1)
        s.shot('feel-gunsmith')
    finally:
        s.key('Escape')
    s.wait(0.5)

    before = s.raids()
    s.cmd('cv queue proving NORMAL SOLO')
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    mine = s.raids() - before
    try:
        s.wait(5)  # the insertion camera and spawn protection are over
        for fill in AREA:
            s.rcon(f'execute at {s.name} run fill {fill}')
        raid(s)
    finally:
        s.extract('proving', mine)


def soldiers(s, keep=''):
    for kind in ('zombie', 'husk', 'drowned', 'bee'):
        s.rcon(f'execute at {s.name} run kill @e[type=minecraft:{kind},distance=..2000{keep}]')


def raid(s):
    # Two soldiers for the kill feed, frozen at 1 health, out of the way until then.
    for tag in ('gf_a', 'gf_b'):
        for _ in range(40):
            s.rcon(f'execute at {s.name} run tag @e[type=minecraft:zombie,tag=!gf_keep,distance=..2000,limit=1,'
                   f'nbt={{BukkitValues:{{"craftov:ai":"patrol"}}}}] add {tag}')
            if 'passed' in s.rcon(f'execute if entity @e[tag={tag}]'):
                break
            time.sleep(0.5)
        s.rcon(f'tag @e[tag={tag}] add gf_keep')
        s.rcon(f'data merge entity @e[tag={tag},limit=1] {{NoAI:1b,Health:1f}}')
        s.rcon(f'execute at {s.name} run tp @e[tag={tag},limit=1] 45.5 65 120.5')
    soldiers(s, ',tag=!gf_keep')

    s.rcon(f'execute as {s.name} at @s run tp @s 45.5 65 86.5 0 0')
    s.hold('k47')
    s.wait(1.5)
    s.clear_chat()
    s.wait(0.3)
    s.shot('feel-cross-stand')
    s.down('d')
    s.wait(0.7)
    s.shot('feel-cross-strafe')
    s.up('d')
    s.rcon(f'execute as {s.name} at @s run tp @s 45.5 65 86.5 0 0')
    s.clear_chat()
    s.wait(0.8)
    with s.mouse(3):
        s.wait(1.2)
        s.shot('feel-ads')
    s.wait(0.8)

    # A short full-auto burst at the wall: captures in a row show kick and flash per round.
    soldiers(s, ',tag=!gf_keep')
    s.look(0, 0)
    s.clear_chat()  # the teleports' feedback
    s.wait(0.6)
    with s.mouse(1):
        s.wait(0.12)
        burst(s, 'feel-burst', 6, 0.07)
    s.wait(1.5)

    # One AWR shot: its kick lasts 6 ticks.
    s.hold('awr')
    s.wait(1.6)  # the draw
    s.look(0, 0)
    s.clear_chat()
    s.wait(0.6)
    with s.mouse(1):
        s.wait(0.03)
        burst(s, 'feel-awr', 5, 0.06)
    s.wait(1.5)

    # Two soldiers in a row: the kill feed and the double-kill badge.
    s.hold('k47')
    s.key('f')  # AUTO -> SEMI: one round per click
    s.wait(1.2)
    s.rcon(f'execute at {s.name} run tp @e[tag=gf_a,limit=1] 44.5 65 92.5 180 0')
    s.rcon(f'execute at {s.name} run tp @e[tag=gf_b,limit=1] 46.5 65 92.5 180 0')
    s.clear_chat()
    for tag, yaw in (('gf_a', 9.5), ('gf_b', -9.5)):
        s.look(yaw, 4)
        s.wait(0.5)
        for _ in range(4):
            with s.mouse(1):
                s.wait(0.06)
            s.wait(0.25)
            if 'passed' not in s.rcon(f'execute if entity @e[tag={tag}]'):
                break
    s.look(0, 0)
    s.clear_chat()
    s.wait(0.2)
    s.shot('feel-feed')
    s.wait(2.6)
    s.shot('feel-feed-later')
