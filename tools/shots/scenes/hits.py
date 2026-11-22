"""Hit markers on another player: the knock-down ring, plain hits and the kill cross. No arg.

The camera player and a puppet (tools/bot/puppet.js, so tools/bot needs its node_modules) queue a
solo raid on the proving ground each. The puppet carries a self-revive injector, so a lethal hit
knocks it down although it is solo. Brought down to 1 health by RCON (no wound, so it cannot bleed
down first), it is shot with a K47 on semi-auto (hits-knock: the orange ring), then shot again at
the crawling body until it dies (hits-downed-N: white crosses, the last of them the red kill
cross; if the bleed-out runs out between two shots, the last one stays white). A marker shows for
4 ticks, so six captures start 0.08 s apart right after the trigger (-a to -f). The camera then
leaves through the extract.
"""

import threading
import time

AREA = ('39 64 85 52 64 100 minecraft:stone', '39 65 85 52 70 100 minecraft:air')


def run(s):
    s.lobby()
    s.hold('k47')
    ammo = s.ammo_for('k47')
    if ammo and s.count(ammo) < 60:
        s.give(ammo, 60)
    with s.puppet() as mate:
        s.rcon(f'op {mate.name}')  # the proving ground is a test map
        before = s.raids()
        s.cmd('cv queue proving NORMAL SOLO')
        mate.send('chat /cv queue proving NORMAL SOLO')
        s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
        mine = s.raids() - before
        try:
            s.wait_until(lambda: 'craftov_raid' in (s.data('Dimension', mate.name) or ''), 30, 'the puppet in the raid')
            s.wait(5)  # the insertion camera and spawn protection are over
            run_shots(s, mate)
        finally:
            s.rcon(f'deop {mate.name}')
            s.extract('proving', mine)


def run_shots(s, mate):
    def soldiers():
        for kind in ('zombie', 'husk', 'drowned', 'bee'):
            s.rcon(f'kill @e[type=minecraft:{kind}]')

    for fill in AREA:
        s.rcon(f'execute at {s.name} run fill {fill}')
    soldiers()
    for slot in ('head', 'chest'):
        s.rcon(f'item replace entity {mate.name} armor.{slot} with minecraft:air')
    s.give('revive_kit', who=mate.name)
    mate.send('slot 2')  # the knife: nothing in its hands to fire
    s.rcon(f'execute as {mate.name} at @s run tp @s 45.5 65 94.5 180 0')
    s.rcon(f'execute as {s.name} at @s run tp @s 45.5 65 88.5 0 4')
    s.hold('k47')
    s.wait(1)
    s.key('f')  # AUTO -> SEMI: one round per click, so the marker is not covered by the next one
    s.clear_chat()

    def fire(name, pitch):
        soldiers()
        s.look(0, pitch)  # back on target after the last round's recoil
        s.wait(0.6)
        with s.mouse(1):
            s.wait(0.08)
        # One capture takes about 0.3 s, longer than the marker shows: start one every 0.08 s.
        shots = []
        for suffix in 'abcdef':
            shots.append(threading.Thread(target=s.shot, args=(f'{name}-{suffix}',)))
            shots[-1].start()
            time.sleep(0.08)
        for shot in shots:
            shot.join()

    s.rcon(f'damage {mate.name} 99 minecraft:generic')
    fire('hits-knock', 4)
    s.wait(1.5)
    # Lower, at the crawling body: each body shot takes 15 to 21 s off the 60 s bleed-out.
    for i in range(1, 6):
        fire(f'hits-downed-{i}', 12)
        s.wait(0.5)
        if (s.data('playerGameType', mate.name) or '').strip() == '3':
            break  # dead
