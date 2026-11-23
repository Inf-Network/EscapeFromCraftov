"""Shooting a soldier, as the shooter sees it (#134): nothing but Craftov's own hit marker should
show, in particular not BetterHud's sample "Health: x / y" popup at the top left. No arg.

A solo proving-ground raid. The camera stands on cleared stone with a guard posted six blocks
south, and fires single K47 rounds into its chest; after each round quick frames catch the screen
while a popup would still be up (enemy-hit-N-a, -b: 0.1 s and 0.5 s after the trigger). Then the
soldiers are killed and the camera leaves through the extract.
"""

AREA = ('39 64 85 52 64 100 minecraft:stone', '39 65 85 52 70 100 minecraft:air')
SOLDIERS = ('zombie', 'husk', 'drowned', 'bee')


def run(s):
    s.lobby()
    s.hold('k47')
    ammo = s.ammo_for('k47')
    if ammo and s.count(ammo) < 30:
        s.give(ammo, 30)
    before = s.raids()
    s.cmd('cv queue proving NORMAL SOLO')
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    mine = s.raids() - before
    try:
        s.wait(5)  # the insertion camera and spawn protection are over
        for fill in AREA:
            s.rcon(f'execute at {s.name} run fill {fill}')
        for kind in SOLDIERS:
            s.rcon(f'kill @e[type=minecraft:{kind}]')
        # The guard fires back; the camera only has to live through a few seconds of it.
        s.rcon(f'effect give {s.name} minecraft:resistance 60 4 true')
        s.rcon(f'execute as {s.name} at @s run tp @s 45.5 65 88.5 0 4')
        s.rcon(f'cv ai spawn guard 45.5 65 94.5 {next(iter(mine))}')
        s.hold('k47')
        s.wait(1)
        s.key('f')  # AUTO -> SEMI: one round per click
        s.clear_chat()
        for i in range(1, 4):
            s.look(0, 4)  # back on the chest after the last round's recoil
            s.wait(0.6)
            with s.mouse(1):
                s.wait(0.08)
            s.wait(0.05)
            s.shot(f'enemy-hit-{i}-a', quick=True)
            s.wait(0.3)
            s.shot(f'enemy-hit-{i}-b', quick=True)
            s.wait(0.8)
    finally:
        for kind in SOLDIERS:
            s.rcon(f'kill @e[type=minecraft:{kind}]')
        s.rcon(f'effect clear {s.name}')
        s.extract('proving', mine)
