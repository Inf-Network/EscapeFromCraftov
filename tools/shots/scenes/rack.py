"""The private range's weapon rack and the loaner rig and backpack (#161, #162).

On the range, /cv opens the rack and every gun and attachment on it is taken once. Shots:
rack-taken (the rack with the player's inventory under it: the loaner rig and backpack hold it
all, with room to spare), then the button in the arg's slot (default 0, the first gun) is taken
until the backpack is full and clicked six times more within the two seconds its tooltip shows the
reason: rack-refused (the pointer on that button: one line saying why, however many clicks) and
rack-refused-later (three seconds on: the line is gone). Last range-inventory: the inventory
screen (E) on the range.
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
        s.park_mouse()
        s.wait(2.5)  # the last take's own line leaves its button
        s.shot('rack-taken')
        s.mouse_to(*s.slot_center(slot, ROWS))
        time.sleep(0.2)
        for _ in range(20):  # more of it until the backpack is full (it has room for 8 more guns)
            s.xdo('click', '1')
            time.sleep(0.15)
        s.wait(2.5)
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
    s.wait(0.5)
    s.key('e')
    s.wait(1)
    try:
        s.park_mouse()
        s.shot('range-inventory')
    finally:
        s.key('Escape')
    s.lobby()
