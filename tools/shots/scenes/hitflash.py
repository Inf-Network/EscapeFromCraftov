"""The red flash of being hit, and the blood edge with your own helmet still in place (#6): a solo
proving-ground raid with a guard posted five blocks in front of the player. When its first shot
lands, quick frames catch the 6-tick flash (hit-flash). Then the soldiers are gone and health is
at 30: the inventory opened under the blood edge shows the worn helmet in its slot, not a stand-in
item (blood-helmet-inventory), and the third-person view shows it on the head (blood-helmet-f5).
Run it with `--fresh` so the player wears the starter helmet. Shots: hit-flash, hit-flash-2,
blood-helmet-inventory, blood-helmet-f5."""
import re
import time

SOLDIERS = ('zombie', 'husk', 'drowned')


def health(s):
    value = s.data('Health')
    return float(value.rstrip('fdF')) if value else 0.0


def run(s):
    s.lobby()
    before = s.raids()
    s.cmd('cv queue proving NORMAL SOLO')
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    mine = s.raids() - before
    try:
        s.wait(3)
        s.select(2)  # the knife: no gun model in the way of the screen edge
        pos = [float(v) for v in re.findall(r'(-?[\d.]+)d', s.data('Pos') or '')]
        s.look(0, 0)  # facing south (+z), where the guard stands
        s.rcon(f'cv ai spawn guard {pos[0]:.1f} {pos[1]:.1f} {pos[2] + 5:.1f} {next(iter(mine))}')
        s.clear_chat()
        start = health(s)
        hit = False
        end = time.time() + 40  # soldiers hold fire for the first seconds after landing
        while time.time() < end and not hit:
            now = health(s)
            if now < start:
                s.shot('hit-flash', quick=True)
                s.shot('hit-flash-2', quick=True)
                hit = True
            time.sleep(0.05)
        for kind in SOLDIERS:
            s.rcon(f'kill @e[type=minecraft:{kind}]')
        s.develop()
        if not hit:
            print('the guard never hit the player; no hit-flash shot')
        s.wait(2)
        left = health(s)
        if left > 30:
            s.rcon(f'damage {s.name} {left - 30:.0f} minecraft:generic')
        s.wait(1.5)  # past the red flash of that damage (generic damage has none, but be sure)
        s.clear_chat()
        s.key('e')
        s.wait(1)
        s.park_mouse()
        s.shot('blood-helmet-inventory')
        s.key('Escape')
        s.wait(0.5)
        s.key('F5')
        s.wait(1)
        s.shot('blood-helmet-f5')
        s.key('F5', 'F5')
        s.rcon(f'effect give {s.name} minecraft:instant_health 1 10 true')
    finally:
        s.extract('proving', mine)
