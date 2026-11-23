"""The new-player guide (#7) as a new player sees it; run with `--fresh`.

guide-start: the lobby with the first objective (the terminal) under the money panel.
guide-terminal: the terminal, pointer on the guide's checklist (slot 46), the range button shining.
guide-rack: back in the lobby facing the range rack: the objective with its direction and distance,
the compass with the amber marker, and the floating arrow over the rack.
guide-range: on the range right after entering it: the objective is the FOV Effects check that the
action bar names next, not the way out.
"""
import re
import time


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
    # Into the range through the rack, as gunaudio.py goes in.
    s.rcon(f'execute as {s.name} at @s run tp @s 9.5 65 -6 0 0')
    time.sleep(1.5)
    out = s.rcon(f'execute as {s.name} at @s run data get entity @e[type=interaction,sort=nearest,limit=1] Pos')
    station = re.search(r'\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]', out)
    if not station:
        raise RuntimeError('no range station near the lobby spawn')
    x, y, z = (float(v) for v in station.groups())
    s.rcon(f'execute as {s.name} at @s run tp @s {x:.3f} {y:.3f} {z - 2.5:.3f} 0 10')
    time.sleep(0.8)
    s.clear_chat()
    with s.mouse(3):
        time.sleep(0.2)
    s.wait_until(lambda: 'craftov_range' in s.dimension(), 20, 'the range')
    s.look(180, 5)
    s.wait(1.5)
    s.shot('guide-range')
    s.lobby()
