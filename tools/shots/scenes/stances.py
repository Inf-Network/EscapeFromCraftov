"""Every stance another player is seen in (StanceService), on the proving ground. Arg: gun id (k47).

ShotCam and a puppet (tools/bot/puppet.js, needs tools/bot/node_modules) queue as one squad and
land together; ShotCam then walks round the puppet with the HUD hidden:

  stance-low-front / -side      the gun carried low in both hands, muzzle down
  stance-low-crouch             the same crouching (the gun turned back against the body's lean)
  stance-shoulder-front / -side aiming: the gun shouldered along the look
  stance-shoulder-crouch        aiming crouched
  stance-reload                 reloading: the support arm comes up to the magazine
  stance-revive                 the puppet working on ShotCam, who is down (seen from ShotCam's back)
  stance-downed                 the puppet knocked down: crawling with empty hands
  stance-revived                ShotCam, gun in hand, has held right click on the puppet: it is up again
  stance-throw                  a smoke grenade cooked overhead (last: the smoke hides the yard)

Both leave through the always-open extract at the end, so neither reconnects into the raid later.
"""

MAP = 'proving'


def raid(s, mate):
    s.cmd('cv party invite ' + mate.name)
    s.wait(1)
    mate.send('chat /cv party accept')
    s.wait(1.5)
    before = s.raids()
    s.cmd(f'cv queue {MAP} NORMAL TRIO')
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    s.wait_until(lambda: 'craftov_raid' in (s.data('Dimension', mate.name) or ''), 30, f'{mate.name} in the raid')
    return s.raids() - before


def run(s):
    gun = s.arg or 'k47'
    s.lobby()
    with s.puppet() as mate:
        mine = raid(s, mate)
        try:
            s.wait(6)  # the landing
            s.rcon(f'execute as {mate.name} at {s.name} run tp @s ~ ~ ~ 0 0')
            mate.hold(gun)
            s.give(s.ammo_for(gun) if gun not in ('k47', 'kl7') else 'ammo_762_bp', 60, mate.name)
            s.give('smoke', 1, mate.name)
            s.hold(gun)
            s.wait(2)  # the guns come up
            s.key('F1')
            try:
                mate.orbit(30, distance=2.6)
                s.shot('stance-low-front')
                mate.orbit(90, distance=2.6)
                s.shot('stance-low-side')
                mate.send('sneak on')
                s.wait(0.8)
                mate.orbit(40, distance=2.6)
                s.shot('stance-low-crouch')
                mate.send('use on')
                s.wait(1.2)
                s.shot('stance-shoulder-crouch')
                mate.send('sneak off')
                s.wait(0.8)
                mate.orbit(30, distance=2.6)
                s.shot('stance-shoulder-front')
                mate.orbit(90, distance=2.6)
                s.shot('stance-shoulder-side')
                mate.send('use off')
                s.wait(1.8)  # the shoulder holds a moment after aiming stops
                mate.orbit(40, distance=2.6)
                mate.send('drop')
                s.wait(0.9)
                s.shot('stance-reload')
                s.wait(3)
                # ShotCam goes down and the puppet works on it, seen over ShotCam's shoulder.
                mate.orbit(0, distance=1.6)
                s.rcon(f'damage {s.name} 500 minecraft:generic')
                s.wait(1.5)
                mate.send(f'revive {s.name} on')
                s.key('F5')
                s.wait(1.5)
                s.shot('stance-revive')
                s.wait(4.5)
                mate.send(f'revive {s.name} off')
                s.key('F5', 'F5')
                s.wait(1.5)
                # Now the puppet goes down, and ShotCam, gun in hand, gets it up.
                s.rcon(f'damage {mate.name} 500 minecraft:generic')
                s.wait(2)
                mate.orbit(70, distance=3.2)
                s.rcon(f'execute as {s.name} at @s anchored eyes facing entity {mate.name} feet run tp @s ~ ~ ~ ~ ~')
                s.wait(0.5)
                s.shot('stance-downed')
                mate.orbit(0, distance=1.5)
                s.rcon(f'execute as {s.name} at @s anchored eyes facing entity {mate.name} feet run tp @s ~ ~ ~ ~ ~')
                with s.mouse(3):
                    s.wait(6)
                s.wait(1.5)
                mate.orbit(30, distance=2.6)
                s.shot('stance-revived')
                mate.send(f'slot {s.hotbar_slot("smoke", mate.name)}')
                mate.orbit(40, distance=2.6)
                s.wait(0.3)
                mate.send('hold on')  # the smoke's fuse is 1.5 s from here
                s.wait(0.5)
                s.shot('stance-throw')
                s.rcon(f'execute as {mate.name} at @s run tp @s ~ ~ ~ 180 -35')
                mate.send('hold off')
                s.wait(1)
            finally:
                s.key('F1')
        finally:
            spot = s.always_extract(MAP)
            if spot:
                s.rcon(f'execute as {mate.name} at @s run tp @s {spot[0]} {spot[1]} {spot[2]}')
            s.extract(MAP, mine)
