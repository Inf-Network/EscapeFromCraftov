"""Cooking and throwing frag grenades on the proving ground (issue #42).

Shots:
  grenade-cook     right mouse held for a second, up the open spawn bay: the fuse counting down in
                   the action bar and the dotted path to the red dot where it would go off;
  grenade-flight   just after letting go, the grenade on its way;
  grenade-bounce   held while facing the wall between two spawn bays five blocks off: the dots run
                   to the wall and back, the red dot lies in front of it;
  grenade-rest     that grenade thrown, come back off the wall and lying in front of it;
  grenade-fence    right mouse held with the crosshair on a spruce fence post two blocks off (the
                   client clicks the post, which it does not use, then uses the grenade): the pin is
                   out all the same, with the countdown and the dots.

The player steps back out of the blast after each throw, and leaves through the always-open extract
afterwards, as in the raid scene.
"""


def run(s):
    s.lobby()
    if s.count('frag') < 2:
        s.give('frag', 2)
    before = s.raids()
    s.cmd('cv queue proving NORMAL SOLO')
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    mine = s.raids() - before

    def stand(x, z, yaw, pitch):
        s.rcon(f'execute as {s.name} at @s run tp @s {x} 65 {z} {yaw} {pitch}')

    try:
        s.wait(6)  # the insertion camera and spawn protection are over
        stand(45.5, 94.5, 180, -12)  # north, up the open bay
        s.hold('frag')
        s.clear_chat()
        s.wait(1.5)
        with s.mouse(3):
            s.wait(1.2)
            s.shot('grenade-cook', quick=True)
        s.wait(0.35)
        s.shot('grenade-flight', quick=True)
        s.wait(3)  # it goes off 15 blocks up the bay

        stand(54.5, 92.5, -90, 4)  # east, at the wall between this bay and the next
        s.hold('frag')
        s.clear_chat()
        s.wait(1.5)
        with s.mouse(3):
            s.wait(0.8)
            s.shot('grenade-bounce', quick=True)
        s.wait(0.25)  # the release has reached the server: it flew where the dots showed
        stand(54.5, 92.5, -90, 30)  # look down at where it ends up
        s.clear_chat()
        s.wait(0.7)
        s.shot('grenade-rest', quick=True)
        stand(54.5, 80.5, 180, 0)  # out of the blast
        s.wait(2.5)

        stand(45.5, 94.5, 180, 24)  # north again, down at a fence post two blocks off
        s.rcon(f'execute as {s.name} at @s run setblock ~ ~ ~-2 minecraft:spruce_fence')
        s.hold('frag')
        s.clear_chat()
        s.wait(1.5)
        with s.mouse(3):
            s.wait(1.2)
            s.shot('grenade-fence', quick=True)
        s.wait(0.25)
        stand(54.5, 80.5, 180, 0)  # out of the blast (the raid's world, fence and all, goes with it)
        s.wait(2.5)
        s.develop()
    finally:
        s.extract('proving', mine)
