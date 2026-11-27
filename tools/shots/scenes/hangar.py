"""The hangar's north-east corner, where the old firing lane and its three dummies stood (#164).

Shots: hangar-corner (from beside the briefing table, looking at the corner: the range entrance by
the east wall, plain hangar floor behind it) and hangar-spawn (the corner from the spawn point).
"""


def run(s):
    s.lobby()
    s.select(2)  # the knife: no gun model in the way
    for name, where in (('hangar-corner', '4.5 65 4.5 -152 8'), ('hangar-spawn', '0.5 65 12.5 -160 4')):
        s.rcon(f'execute in minecraft:craftov_lobby run tp {s.name} {where}')
        s.wait(2.5)  # the item name and control hints that come with switching slots fade out
        s.clear_chat()
        s.wait(0.3)
        s.shot(name)
