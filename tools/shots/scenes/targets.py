"""The private range's lanes, targets and operators (#165).

Shots: range-overview and range-overview-west (from the top of the back wall at either end, the GUI
hidden: the corner's silhouette lane, the bullseye lane and the moving lane beside it), range-spawn
(where the range puts you: the three silhouettes), range-bullseye-scope and range-bullseye (the
10 m board after nine DM14 rounds from the hip: holes, the last one outlined, the tally over it;
through the sights and from the firing line), range-standing, range-fall-a..l (the 7 m silhouette
shot in the head with a D50 from its front left, the GUI hidden, grabbed about 45 ms apart as it
tips back), range-down (lying flat), range-rise-a..j (standing up again 3 s later, about 70 ms
apart), range-moving-a/b (the moving lane half a second apart), range-rack-row and range-rack (the
rack's bottom row: 试用干员, the skill, the reset; the first's tooltip), range-operators (the
operator menu on the range) and range-sonar (the recon tried on the range, its sonar marking the
silhouettes; the HUD's range panel with the skill cooling down).
"""

import math
import pathlib
import re
import time

# The range helpers of the optics scene (scenes are run as plain files, not imported as a package).
_optics = {'__file__': str(pathlib.Path(__file__).with_name('optics.py'))}
exec(compile(pathlib.Path(_optics['__file__']).read_text(encoding='utf-8'), _optics['__file__'], 'exec'), _optics)
enter_range = _optics['enter_range']

EYE = 1.62


def place(s, x, z, at, y=65.0):
    """Stands the camera at (x, y, z) on the range looking at the point `at`."""
    dx, dy, dz = at[0] - x, at[1] - (y + EYE), at[2] - z
    yaw = -math.degrees(math.atan2(dx, dz))
    pitch = -math.degrees(math.atan2(dy, math.hypot(dx, dz)))
    s.rcon(f'execute in minecraft:craftov_range run tp {s.name} {x} {y} {z} {yaw:.2f} {pitch:.2f}')


def fire(s):
    """One click of the left mouse button: a round from a semi-automatic gun."""
    with s.mouse(1):
        time.sleep(0.06)


def run(s):
    enter_range(s)
    pos = re.findall(r'(-?[\d.]+)d', s.data('Pos') or '')
    dx = round(float(pos[0]) - 14.5) if pos else 0
    s.cmd('cv fov 70 100')
    s.wait(0.5)
    s.hold('dm14')
    s.wait(1.5)
    s.key('q')  # load it (the range's reserve never runs out)
    s.wait(4)

    # The whole range from the top of the back wall, without the GUI.
    place(s, dx + 21.5, -0.5, (dx - 14, 64, -30), y=73)
    s.wait(2.5)
    s.clear_chat()
    s.key('F1')
    s.wait(0.5)
    s.shot('range-overview')
    place(s, dx - 33.0, -1.5, (dx + 6, 64, -26), y=73)
    s.wait(2.5)
    s.shot('range-overview-west')
    s.key('F1')
    place(s, dx + 14.5, -5.5, (dx + 14.5, 65 + EYE, -30))
    s.wait(2)
    s.clear_chat()
    s.shot('range-spawn')

    # The 10 m bullseye: ten rounds from the hip (some off the paper), then through the sights.
    board = (dx - 4.5, 65 + 0.93 + 0.52, -18.5 + 0.52)
    place(s, dx - 4.5, -7.5, board)
    s.wait(1.5)
    for _ in range(9):
        fire(s)
        s.wait(0.4)
        place(s, dx - 4.5, -7.5, board)  # the recoil taken back out
        s.wait(0.15)
    s.wait(0.8)
    s.clear_chat()
    with s.mouse(3):
        s.wait(1.4)
        s.shot('range-bullseye-scope')
    s.wait(1.2)
    place(s, dx - 3.0, -8.2, (board[0], board[1] + 0.4, board[2]))
    s.wait(1.5)
    s.clear_chat()
    s.shot('range-bullseye')

    # The 7 m silhouette from its front left, shot in the head with a D50 (one round knocks it
    # down), the GUI and the hand hidden (F1) so nothing covers it: it tips back, lies flat, and
    # stands up again 3 s later.
    s.hold('d50')
    s.wait(1.5)
    s.key('q')
    s.wait(3)
    head = (dx + 15.5, 65 + 1.68, -15.5)
    place(s, dx + 12.5, -11.0, head)
    s.wait(1.5)
    s.clear_chat()
    with s.mouse(3):
        s.wait(1.2)
        place(s, dx + 12.5, -11.0, head)
        s.wait(0.4)
        s.key('F1')
        s.wait(0.3)
        s.shot('range-standing')
        fire(s)
        shot_at = time.time()
        for k in 'abcdefghijkl':
            s.wait(0.025)
            s.shot('range-fall-' + k, quick=True)
    s.wait(0.5)
    s.shot('range-down', quick=True)
    # It starts to stand up 3 s after it fell (a tick or two after the round).
    s.wait(max(0.0, shot_at + 2.95 - time.time()))
    for k in 'abcdefghij':
        s.shot('range-rise-' + k, quick=True)
        s.wait(0.05)
    s.develop()
    s.key('F1')
    s.wait(0.5)
    place(s, dx + 12.5, -11.0, (head[0], head[1] + 0.3, head[2]))
    s.wait(0.5)

    # The moving lane: three targets at their speeds, half a second apart.
    place(s, dx - 21.5, -7.0, (dx - 21.5, 65 + 1.2, -30))
    s.wait(2)
    s.clear_chat()
    s.shot('range-moving-a')
    s.wait(0.5)
    s.shot('range-moving-b')

    # The rack's bottom row and the operator menu.
    place(s, dx + 14.5, -5.5, (dx + 14.5, 65 + EYE, -30))
    s.wait(1)
    s.select(2)  # the knife: no gun model in the way of what the sonar marks
    s.wait(0.8)
    s.cmd('cv')
    s.wait(1.5)
    try:
        s.park_mouse()
        s.wait(0.5)
        s.shot('range-rack-row')
        s.mouse_to(*s.slot_center(36, 5))
        s.wait(1)
        s.shot('range-rack')
        s.click_slot(36, 5)
        s.wait(1)
        s.mouse_to(*s.slot_center(14, 3))
        s.wait(1)
        s.shot('range-operators')
        s.click_slot(14, 3)
        s.wait(0.8)
    finally:
        s.park_mouse()
        s.key('Escape')
    s.wait(1)
    # The recon's sonar, sneak + F, on the silhouettes in front.
    s.clear_chat()
    s.down('Shift_L')
    s.wait(0.3)
    s.key('f')
    s.wait(0.3)
    s.up('Shift_L')
    s.wait(1.5)
    s.shot('range-sonar')
    s.lobby()
