"""A quick re-aim through a scope (issue #5): the AWR's 8x up on the range, right mouse let go and
pressed again moments later. reaim-up: the scope before letting go. reaim-a..d: from just after
pressing again, about 100 ms apart. Pressed within weapons.ads.resume-ticks of the server lowering
the sights, the scope comes straight back; later, the gun rises from the hip again. Either way no
frame may show the scope's reticle over an unzoomed view without the round mask. An arg is the
pause between letting go and pressing again, in seconds (default 0.3).
"""

import pathlib

# The range helpers of the optics scene (scenes are run as plain files, not imported as a package).
_optics = {'__file__': str(pathlib.Path(__file__).with_name('optics.py'))}
exec(compile(pathlib.Path(_optics['__file__']).read_text(encoding='utf-8'), _optics['__file__'], 'exec'), _optics)
arm, enter_range, face_dummy = _optics['arm'], _optics['enter_range'], _optics['face_dummy']


def run(s):
    pause = float(s.arg) if s.arg else 0.3
    enter_range(s)
    s.cmd('cv fov 70 100')
    s.wait(0.5)
    arm(s, 'awr', None)
    face_dummy(s)
    with s.mouse(3):
        s.wait(1.6)
        s.shot('reaim-up', quick=True)
    s.wait(pause)
    with s.mouse(3):
        for k in 'abcd':
            s.wait(0.1)
            s.shot('reaim-' + k, quick=True)
        s.wait(0.6)
    s.develop()
    s.rcon(f'clear {s.name}')
    s.lobby()
