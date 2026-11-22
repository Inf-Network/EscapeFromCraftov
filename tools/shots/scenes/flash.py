"""A flashbang thrown at your own feet in a raid: the white-out and how it fades (issue #15).

Shots: flash-0 just before the blast, then flash-1 .. flash-4 from full white to clear. The flash
fuse is 40 ticks; facing the blast gives combat.flashbang.facing-ticks (80) of white-out. The player
leaves through the always-open extract afterwards, as in the raid scene.
"""


def run(s):
    s.lobby()
    if s.count('flash') < 1:
        s.give('flash', 1)
    before = s.raids()
    s.cmd('cv queue proving NORMAL SOLO')
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    mine = s.raids() - before
    try:
        s.wait(4)  # the insertion camera is over
        s.hold('flash')
        s.look(0, 35)  # a few blocks ahead on the ground, still in view
        s.wait(1)
        with s.mouse(3):  # right click throws
            s.wait(0.15)
        s.wait(1.6)
        s.shot('flash-0')
        s.wait(0.6)  # the fuse ends: full white
        s.shot('flash-1')
        s.wait(1.2)
        s.shot('flash-2')
        s.wait(1.0)
        s.shot('flash-3')
        s.wait(1.2)
        s.shot('flash-4')
    finally:
        s.extract('proving', mine)
