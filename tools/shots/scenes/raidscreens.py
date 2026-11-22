"""The designed in-raid screens (#6): a container being searched (the armory crate on the proving
ground, with its "搜索中…" title) and, through the raid terminal, the health screen with a fractured
leg and a bleeding chest. Shots: screen-loot, screen-health."""


def run(s):
    s.lobby()
    before = s.raids()
    s.cmd('cv queue proving NORMAL SOLO')
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    mine = s.raids() - before
    try:
        s.wait(5)
        s.select(0)
        # Face the armory crate in front of the test spawn and open it with right mouse.
        s.rcon(f'execute as {s.name} at @s run tp @s 8.5 65 78.9 180 55')
        s.wait(1.5)
        s.xdo('click', '3')
        s.wait(1.2)
        s.park_mouse()
        s.wait(2.5)  # a few items revealed, the next one spinning
        s.shot('screen-loot')
        s.key('Escape')
        s.wait(0.8)
        s.rcon(f'cv injure {s.name} left_leg fracture')
        s.rcon(f'cv injure {s.name} chest light')
        s.select(8)  # the raid terminal
        s.wait(0.5)
        s.xdo('click', '3')
        s.wait(1.2)
        s.click_slot(4, 3)  # 伤势
        s.wait(1.2)
        s.park_mouse()
        s.shot('screen-health')
        s.key('Escape')
        s.wait(0.5)
        s.select(0)
    finally:
        s.extract('proving', mine)
