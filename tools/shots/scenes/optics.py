"""Sights (issue #5): the same view from the hip and through every sight, on the private range.

Looking down the range at the 10 m dummy: optics-hip (K47 from the hip), then right mouse held until
the sights are up: optics-iron (K47 iron sights, 1.25x), optics-reddot and optics-holo (1.5x: the
window about a fifth of the screen wide, the dot or ring on the centre), optics-3x and optics-4x (on
the K47), optics-dm14 (its built-in 4x), optics-8x (the AWR) and optics-p18 (pistol iron sights,
1.1x). optics-rise-a..f catch the K47's iron sights coming up, about 70 ms apart. The zoom can be
compared between the shots by the dummy's size: 1.25, 1.5, 3, 4 and 8 times the hip view's. Last the
50 m calibration target: optics-cal-hip, optics-cal-4x (the 4x posts just touch its outer ring) and
optics-cal-8x. An arg n takes only the first n sights (0: just the hip and the rise).
"""

import math

SIGHTS = [('optics-iron', 'k47', None), ('optics-reddot', 'k47', 'optic_reddot'), ('optics-holo', 'k47', 'optic_holo'),
          ('optics-3x', 'k47', 'optic_3x'), ('optics-4x', 'k47', 'optic_4x'), ('optics-dm14', 'dm14', None),
          ('optics-8x', 'awr', None), ('optics-p18', 'p18', None)]


def enter_range(s):
    s.lobby()
    s.rcon(f'execute as {s.name} at @s run tp @s 9.5 65 -6 0 10')
    s.wait(1)
    with s.mouse(3):
        s.wait(0.15)
    s.wait_until(lambda: 'craftov_range' in s.dimension(), 20, 'the range')
    s.wait(2)


def arm(s, gun, optic):
    """Just this gun (with the optic on it) in the hand, drawn."""
    s.rcon(f'clear {s.name}')
    s.wait(0.4)
    s.rcon(f'cv give {s.name} {gun} 1' + (f' {optic}' if optic else ''))
    s.wait(0.6)
    s.hold(gun)
    s.wait(1.6)


def face_dummy(s):
    """Straight at the middle dummy's chest, 10 blocks down the range from where the range puts you."""
    s.rcon(f'execute as {s.name} at @s run tp @s ~ ~ ~ 0 0')
    s.wait(0.3)
    yaw = math.degrees(math.atan2(-1.0, -10.0))
    pitch = math.degrees(math.atan2(0.42, 10.05))
    s.look(round(yaw, 2), round(pitch, 2))
    s.wait(0.6)
    s.clear_chat()


def run(s):
    enter_range(s)
    s.cmd('cv fov 70 100')
    s.wait(0.5)
    arm(s, 'k47', None)
    face_dummy(s)
    s.wait(0.6)
    s.shot('optics-hip')
    with s.mouse(3):
        for k in 'abcdef':
            s.wait(0.05)
            s.shot('optics-rise-' + k, quick=True)
        s.wait(1.2)
    s.develop()
    s.wait(0.8)
    for name, gun, optic in SIGHTS[:int(s.arg) if s.arg else None]:
        arm(s, gun, optic)
        face_dummy(s)
        with s.mouse(3):
            s.wait(1.6)
            s.shot(name)
        s.wait(0.8)
    calibration(s)
    s.rcon(f'clear {s.name}')
    s.lobby()


def calibration(s):
    """The 50 m calibration target down the lane: from the hip, and through the K47's 4x (the posts
    just touch the outer ring) and the AWR's 8x (mil-dots on the 5 mil ticks)."""
    for name, gun, optic in (('optics-cal-hip', 'k47', None), ('optics-cal-4x', 'k47', 'optic_4x'), ('optics-cal-8x', 'awr', None)):
        arm(s, gun, optic)
        s.look(180, 0)
        s.wait(0.6)
        s.clear_chat()
        if name.endswith('hip'):
            s.shot(name)
            continue
        with s.mouse(3):
            s.wait(1.6)
            s.shot(name)
        s.wait(0.8)
