"""A solo raid on the proving ground: the in-raid HUD, firing and reloading. Arg: item id (m4c).

The proving ground is a test map, so the server needs test.enabled (shots.py setup turns it on) or
an op player (the harness ops its player). The player leaves through the always-open extract, so
it keeps its gear and the next run starts in the lobby instead of reconnecting into the raid.
"""


def run(s):
    gun = s.arg or 'm4c'
    s.lobby()
    s.hold(gun)
    ammo = s.ammo_for(gun)
    if ammo and s.count(ammo) < 60:
        s.give(ammo, 60)  # spare rounds, so the reload has something to load
    before = s.raids()
    s.cmd('cv queue proving NORMAL SOLO')
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    mine = s.raids() - before
    try:
        s.wait(4)  # the insertion camera is over; let the HUD settle
        s.hold(gun)
        s.wait(1.5)
        s.shot('raid-hud')
        with s.mouse(1):  # trigger down: muzzle flash, tracers, the ammo count dropping
            s.wait(0.3)
            s.shot('raid-fire')
        s.wait(0.8)
        s.key('q')  # reload: the animation runs for the gun's reload time
        s.wait(0.6)
        s.shot('raid-reload')
    finally:
        s.extract('proving', mine)
