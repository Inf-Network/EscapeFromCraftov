"""Items taken out of a crate leave their cells at once (#160).

A solo proving-ground raid: the armory weapons case is opened and searched to the end (crate-open),
then the first gun is taken with a left click and the second with a right click, and the screen is
shot with the pointer parked (crate-taken): both cells must be empty, the guns in the backpack
below. Before the fix a gun taken this way could stay on show in its cell beside the one now in the
backpack (the right-clicked rifle did) until the cell was clicked again or the case reopened. The
raid is left through the always-open extract, so the guns go home with the player.

The clicks wait for the search to finish: while a cell is being searched the screen changes every
few ticks, a click then usually carries an out-of-date state id, and the server answers that with
the whole window, which hides a ghost.
"""

ROWS = 6  # the search screen
SEARCH_SECONDS = 55  # the 27-cell weapons case takes about 45 s


def run(s):
    s.lobby()
    before = s.raids()
    s.cmd('cv queue proving NORMAL SOLO')
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    mine = s.raids() - before
    try:
        s.wait(5)
        s.select(2)  # the knife: a right click on the case opens it instead of aiming
        s.rcon(f'execute as {s.name} at @s run tp @s 8.5 65 78.9 180 55')
        s.wait(1.5)
        s.xdo('click', '3')
        s.wait(1.2)
        s.park_mouse()
        s.wait(SEARCH_SECONDS)
        s.shot('crate-open')
        s.click_slot(0, ROWS)            # left click: into the backpack
        s.click_slot(1, ROWS, button=3)  # right click: a gun is not worn, so it is taken the same way
        s.park_mouse()
        s.wait(0.5)
        s.shot('crate-taken')
        s.key('Escape')
        s.wait(0.8)
        s.look(0, 0)
    finally:
        s.extract('proving', mine)
