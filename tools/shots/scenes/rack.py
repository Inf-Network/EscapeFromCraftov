"""The private range's weapon rack after a run of refused clicks (#161).

On the range, /cv opens the rack. Every gun and attachment on it is taken once, which leaves no room
for another gun; then the button in the arg's slot (default 0, the first gun) is clicked six times
within the two seconds its tooltip shows the reason. Shots: rack-refused (the pointer on that
button: one line saying why, however many clicks), rack-refused-later (three seconds on: the line
is gone). The player's inventory under the rack shows what was taken and where it went.
"""

import pathlib
import time

# The range helpers of the optics scene (scenes are run as plain files, not imported as a package).
_optics = {'__file__': str(pathlib.Path(__file__).with_name('optics.py'))}
exec(compile(pathlib.Path(_optics['__file__']).read_text(encoding='utf-8'), _optics['__file__'], 'exec'), _optics)
enter_range = _optics['enter_range']

ROWS = 5


def run(s):
    slot = int(s.arg or 0)
    enter_range(s)
    s.clear_chat()
    s.cmd('cv')
    s.wait(1.5)
    try:
        for index in range(36):
            s.mouse_to(*s.slot_center(index, ROWS))
            time.sleep(0.12)
            s.xdo('click', '1')
            time.sleep(0.15)
        s.wait(2.5)  # the last take's own line leaves its button
        s.mouse_to(*s.slot_center(slot, ROWS))
        time.sleep(0.2)
        for _ in range(6):
            s.xdo('click', '1')
            time.sleep(0.2)
        s.wait(0.4)
        s.shot('rack-refused')
        s.wait(3)
        s.shot('rack-refused-later')
    finally:
        s.park_mouse()
        s.key('Escape')
    s.lobby()
