"""Flashed and then out of the raid at once (issue #61): the white-out must not follow the player.

Shots: flashhome-white with the screen fully white on the proving ground, then flashhome-lobby just
after `/cv lobby` and flashhome-lobby-1s a second later, both clear (the white-out used to fade over
the lobby for up to four seconds). Arg `death` instead has the player killed while white
(flashhome-dead-white): flashhome-dead on the death camera, clear and not blind. A death loses
what the player carried, so it only runs when asked for.
"""


def flashed(s, shot):
    """Solo into the proving ground and a flashbang a few blocks ahead, facing it, until full white."""
    s.lobby()
    if s.count('flash') < 1:
        s.give('flash', 1)
    before = s.raids()
    s.cmd('cv queue proving NORMAL SOLO')
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    mine = s.raids() - before
    s.wait(4)  # the insertion camera is over
    s.hold('flash')
    s.look(0, 35)
    s.wait(1)
    with s.mouse(3):  # right click throws
        s.wait(0.15)
    s.wait(2.2)  # the 2 s fuse from the pull: fully white now
    s.shot(shot, quick=True)
    return mine


def run(s):
    if s.arg == 'death':
        flashed(s, 'flashhome-dead-white')
        s.rcon(f'damage {s.name} 500 minecraft:generic')
        s.wait(0.6)
        s.shot('flashhome-dead', quick=True)
        # Five seconds on the death camera, then the lobby and the settlement screen.
        s.wait_until(s.in_lobby, 30, 'the lobby after the death camera')
        s.wait(2.5)
        s.rcon(f'cv close {s.name}')
        s.wait(0.5)
        return
    mine = flashed(s, 'flashhome-white')
    try:
        s.cmd('cv lobby')
        s.wait_until(s.in_lobby, 10, 'the lobby')
        s.wait(0.3)
        s.shot('flashhome-lobby', quick=True)
        s.wait(1)
        s.shot('flashhome-lobby-1s', quick=True)
    finally:
        if not s.in_lobby():
            s.extract('proving', mine)
