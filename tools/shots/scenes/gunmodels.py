"""Gun models (issue #4): each gun from the hip, aimed, and in third person, on the private range.

Arg: a comma list of gun ids (default m4c,k47,p18), optionally led by a tag that prefixes every
shot's name (`gunmodels:before,m4c,k47` -> before-m4c-hip ...). Per gun: <gun>-hip (looking down
the range at the 10 m dummy), <gun>-ads (right mouse held until the sights are up), <gun>-back
(F5, from behind) and <gun>-front (F5 again, facing the player), the HUD hidden (F1) for the two
third-person views so the model is in the clear. The range keeps the guns: the camera only goes
back to the lobby at the end.
"""

import pathlib

# The range helpers of the optics scene (scenes are run as plain files, not imported as a package).
_optics = {'__file__': str(pathlib.Path(__file__).with_name('optics.py'))}
exec(compile(pathlib.Path(_optics['__file__']).read_text(encoding='utf-8'), _optics['__file__'], 'exec'), _optics)
arm, enter_range, face_dummy = _optics['arm'], _optics['enter_range'], _optics['face_dummy']

GUNS = ('p18', 'd50', 'sv9', 'v57', 'k47', 'm4c', 'scr', 'kl7', 'r12', 'dm14', 'awr')


def run(s):
    words = [w for w in (s.arg or 'm4c,k47,p18').split(',') if w]
    tag = words.pop(0) + '-' if words and words[0] not in GUNS else ''
    guns = words or ['m4c', 'k47', 'p18']
    enter_range(s)
    s.cmd('cv fov 70 100')
    s.wait(0.5)
    for gun in guns:
        arm(s, gun, None)
        face_dummy(s)
        s.wait(0.8)
        s.shot(f'{tag}{gun}-hip')
        with s.mouse(3):
            s.wait(1.6)
            s.shot(f'{tag}{gun}-ads')
        s.wait(0.6)
        s.key('F1')
        try:
            s.key('F5')
            s.wait(1.0)
            s.shot(f'{tag}{gun}-back')
            s.key('F5')
            s.wait(1.0)
            s.shot(f'{tag}{gun}-front')
            s.key('F5')
        finally:
            s.key('F1')
        s.wait(0.5)
    s.rcon(f'clear {s.name}')
    s.lobby()
