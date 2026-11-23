"""Drawing and reloading (issue #4) on the private range. Arg: the gun drawn (default k47).

draw-<gun>-a..h: from the P18 over to the gun, captured about 80 ms apart from the moment of the
switch: the gun shows low and canted at once (the holster mark, the draw's first frame), then comes
up over its draw time (12 ticks for a rifle) with a small overshoot, and is at rest by the last.
reload-<gun>-a..h: Q pressed with a few rounds gone, captured about 0.3 s apart: the reload frames,
one a tick at most. draw-p18-a..d: back to the pistol (6 ticks).
"""

import pathlib
import time

# The range helpers of the optics scene (scenes are run as plain files, not imported as a package).
_optics = {'__file__': str(pathlib.Path(__file__).with_name('optics.py'))}
exec(compile(pathlib.Path(_optics['__file__']).read_text(encoding='utf-8'), _optics['__file__'], 'exec'), _optics)
enter_range, face_dummy = _optics['enter_range'], _optics['face_dummy']


def frames(s, prefix, count, every):
    for suffix in 'abcdefghijkl'[:count]:
        s.shot(f'{prefix}-{suffix}', quick=True)
        time.sleep(every)


def run(s):
    gun = s.arg or 'k47'
    enter_range(s)
    s.cmd('cv fov 70 100')
    s.wait(0.5)
    s.rcon(f'clear {s.name}')
    s.wait(0.4)
    s.rcon(f'cv give {s.name} p18 1')
    s.rcon(f'cv give {s.name} {gun} 1')
    s.wait(0.8)
    pistol = s.hotbar_slot('p18')
    rifle = s.hotbar_slot(gun)
    face_dummy(s)
    s.select(pistol)
    s.wait(1.5)
    s.select(rifle)
    frames(s, f'draw-{gun}', 8, 0.06)
    s.wait(1.0)
    # A few rounds off, then Q: the reload.
    with s.mouse(1):
        s.wait(0.3)
    s.wait(0.6)
    s.key('q')
    frames(s, f'reload-{gun}', 8, 0.28)
    s.wait(1.5)
    s.select(pistol)
    frames(s, 'draw-p18', 4, 0.06)
    s.develop()
    s.wait(0.5)
    s.rcon(f'clear {s.name}')
    s.lobby()
