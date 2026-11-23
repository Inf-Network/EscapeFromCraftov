"""The new-player guide (#7) as a new player sees it; run with `--fresh`.

guide-start: the lobby with the first objective (the terminal) under the money panel.
guide-terminal: the terminal, pointer on the guide's checklist (slot 46), the range button shining.
guide-rack: back in the lobby facing the range rack: the objective with its direction and distance,
the compass with the amber marker, and the floating arrow over the rack.
"""


def run(s):
    s.select(0)
    s.lobby()
    s.clear_chat()
    s.look(180, 0)
    s.wait(3)
    s.shot('guide-start')
    s.cmd('cv')
    s.wait(1.5)
    try:
        s.mouse_to(*s.slot_center(46, 6))
        s.wait(0.8)
        s.shot('guide-terminal')
    finally:
        s.key('Escape')
        s.wait(0.5)
    s.clear_chat()
    # From the spawn the range rack is ahead and to the right (about 9 east, 17 north).
    s.look(-152, -4)
    s.wait(3)
    s.shot('guide-rack')
