"""What #192 left in pixel style: the labels on the menus (terminal, deploy, the inventory screen,
a searched crate) and, in a proving-ground raid at the test spawn, the compass letters and the info
panel, whose extract line is the longest the map gives (限员撤离点, about 100 m away).

Arg: a label that starts every shot name (default: px). Shots are <label>-gui<scale>-main / -deploy /
-inventory / -hud / -loot, so before / after runs and GUI scales sit side by side in tools/shots/out/.
"""


def run(s):
    prefix = f'{s.arg or "px"}-gui{s.gui_scale()}-'
    s.lobby()
    s.clear_chat()
    for command, name in (('menu', 'main'), ('deploy', 'deploy')):
        s.cmd('cv ' + command)
        s.wait(1.5)
        try:
            s.park_mouse()
            s.shot(prefix + name)
        finally:
            s.key('Escape')
            s.wait(0.5)
    s.key('e')
    s.wait(1)
    try:
        s.park_mouse()
        s.shot(prefix + 'inventory')
    finally:
        s.key('Escape')
        s.wait(0.5)
    before = s.raids()
    s.cmd('cv queue proving NORMAL SOLO')
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    mine = s.raids() - before
    try:
        s.wait(4)
        s.select(0)
        # Facing south-east from the test spawn: E, SE and S on the compass.
        s.rcon(f'execute as {s.name} at @s run tp @s 8.5 65 78.9 -45 0')
        s.wait(2)
        s.clear_chat()
        s.shot(prefix + 'hud')
        # The armory crate in front of the spawn (raidscreens.py).
        s.rcon(f'execute as {s.name} at @s run tp @s 8.5 65 78.9 180 55')
        s.wait(1.5)
        s.xdo('click', '3')
        s.wait(1.2)
        s.park_mouse()
        s.wait(2)
        s.shot(prefix + 'loot')
        s.key('Escape')
        s.wait(0.8)
        s.look(0, 0)
    finally:
        s.extract('proving', mine)
