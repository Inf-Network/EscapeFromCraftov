"""The gloved hands in first person (issue #3), on the private range. Arg: a comma list of gun ids
(default k47,p18,r12,scr).

Per gun: hands-<gun>-hip (at rest), -ads (right mouse held until the sights are up), -sprint (forward
and sprint held), -draw (just after switching to it: coming up low) and -reload-a..c (Q with rounds
gone, about 0.45, 0.9 and 1.5 s in: the left hand goes to the magazine, out of view with it and
back). Then the knife: hands-knife (at rest), -stab-a..c (a left click on a dummy 2.5 blocks away: the
8-tick thrust, about 0.06, 0.16 and 0.26 s in; a stab into thin air would show the knife dropping out of
view instead, as the client lowers a weapon after a miss), -inspect-a..c (F).
"""

import pathlib
import time

# The range helpers of the optics scene (scenes are run as plain files, not imported as a package).
_optics = {'__file__': str(pathlib.Path(__file__).with_name('optics.py'))}
exec(compile(pathlib.Path(_optics['__file__']).read_text(encoding='utf-8'), _optics['__file__'], 'exec'), _optics)
arm, enter_range, face_dummy = _optics['arm'], _optics['enter_range'], _optics['face_dummy']


def run(s):
    guns = [g for g in (s.arg or 'k47,p18,r12,scr').split(',') if g]
    enter_range(s)
    s.cmd('cv fov 70 100')
    s.wait(0.5)
    for gun in guns:
        arm(s, gun, None)
        face_dummy(s)
        s.wait(0.8)
        s.shot(f'hands-{gun}-hip')
        with s.mouse(3):
            s.wait(1.6)
            s.shot(f'hands-{gun}-ads')
        s.wait(0.6)
        s.down('w')
        s.down('Control_L')
        s.wait(0.6)
        s.shot(f'hands-{gun}-sprint')
        s.up('Control_L')
        s.up('w')
        s.wait(0.6)
        face_dummy(s)
        # A few rounds off, then Q: the reload.
        with s.mouse(1):
            s.wait(0.3)
        s.wait(0.6)
        s.key('q')
        start = time.time()
        for suffix, at in (('a', 0.45), ('b', 0.9), ('c', 1.5)):
            time.sleep(max(0.0, start + at - time.time()))
            s.shot(f'hands-{gun}-reload-{suffix}', quick=True)
        s.wait(3)
        # Away and back: the draw.
        s.select(8)
        s.wait(0.8)
        s.select(s.slot_of(gun))
        time.sleep(0.12)
        s.shot(f'hands-{gun}-draw', quick=True)
        s.wait(1)
    s.develop()
    s.rcon(f'clear {s.name}')
    s.wait(0.4)
    s.rcon(f'cv give {s.name} knife_tactical 1')
    s.wait(0.6)
    s.hold('knife_tactical')
    face_dummy(s)
    s.wait(0.8)
    s.shot('hands-knife')
    # Up to the nearest dummy, 2.5 blocks in front of it, looking at its chest.
    near = '@e[type=minecraft:zombie,sort=nearest,limit=1]'
    s.rcon(f'execute as {s.name} at @s at {near} run tp @s ^ ^ ^2.5')
    s.wait(0.3)
    s.rcon(f'execute as {s.name} at @s anchored eyes facing entity {near} eyes run tp @s ~ ~ ~ ~ ~12')
    s.clear_chat()
    s.wait(1.2)
    s.shot('hands-knife-close')
    with s.mouse(1):
        start = time.time()
        for suffix, at in (('a', 0.06), ('b', 0.16), ('c', 0.26)):
            time.sleep(max(0.0, start + at - time.time()))
            s.shot(f'hands-knife-stab-{suffix}', quick=True)
    s.wait(1)
    s.key('f')
    start = time.time()
    for suffix, at in (('a', 0.6), ('b', 1.25), ('c', 1.9)):
        time.sleep(max(0.0, start + at - time.time()))
        s.shot(f'hands-knife-inspect-{suffix}', quick=True)
    s.develop()
    s.wait(1)
    s.rcon(f'clear {s.name}')
    s.lobby()
