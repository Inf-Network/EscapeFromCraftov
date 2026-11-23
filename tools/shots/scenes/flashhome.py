"""Flashed and then out of the raid at once (issue #61): the white-out must not follow the player.

Shots: flashhome-white with the screen fully white on the proving ground, then flashhome-lobby just
after `/cv lobby` and flashhome-lobby-1s a second later, both clear (the white-out used to fade over
the lobby for up to four seconds). Arg `death` instead has the player killed while white
(flashhome-dead-white): flashhome-dead on the death camera, clear and not blind. Arg `blast` has
the player, at a tenth of their health, hold a frag until it goes off in the hand: a death by a
weapon, which used to put the red rim of a hit back on the death camera for 0.3 s; frames
flashhome-blast-0..5 a tenth of a second apart from the moment it goes off, all without the rim.
The red edge of low health (HealthService's world-border tint, renewed every half second) can
outlast any death at low health by up to that long, strongest over the white of the explosion; it
keeps the red channel at full and takes green and blue down alike, where the hit rim darkens red
too. A death loses what the player carried, so these only run when asked for.
"""
import re


def deploy(s, need=None):
    """Solo into the proving ground, with at least one `need` taken along; returns the raid it started."""
    s.lobby()
    if need and s.count(need) < 1:
        s.give(need, 1)
    before = s.raids()
    s.cmd('cv queue proving NORMAL SOLO')
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    mine = s.raids() - before
    s.wait(4)  # the insertion camera is over
    return mine


def flashed(s, shot):
    """Solo into the proving ground and a flashbang a few blocks ahead, facing it, until full white."""
    mine = deploy(s, 'flash')
    s.hold('flash')
    s.look(0, 35)
    s.wait(1)
    with s.mouse(3):  # right click throws
        s.wait(0.15)
    s.wait(2.2)  # the 2 s fuse from the pull: fully white now
    s.shot(shot, quick=True)
    return mine


def blast(s):
    deploy(s, 'frag')
    s.hold('frag')
    s.look(0, 35)
    # A frag going off in the hand takes about two thirds of full health through a vest.
    found = re.search(r'([\d.]+)\s*$', s.rcon(f'attribute {s.name} minecraft:max_health get'))
    s.rcon(f'damage {s.name} {round(float(found.group(1)) * 0.9) if found else 18} minecraft:generic')
    s.wait(1)
    with s.mouse(3):  # held past the 3.5 s fuse
        s.wait(3.0)
        s.shot('flashhome-blast-cooking', quick=True)
        s.wait(0.45)
        for i in range(6):
            s.shot(f'flashhome-blast-{i}', quick=True)
            s.wait(0.08)
    s.wait_until(s.in_lobby, 30, 'the lobby after the death camera')
    s.wait(2.5)
    s.rcon(f'cv close {s.name}')
    s.wait(0.5)


def run(s):
    if s.arg == 'blast':
        blast(s)
        return
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
