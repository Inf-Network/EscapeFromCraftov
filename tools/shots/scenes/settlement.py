"""The settlement screen after a raid (#6): a solo proving-ground raid left through the always-open
extract (settlement-ok), then a second raid ended by dying (settlement-fail). The second leaves the
player with nothing but the knife, so run it last or on a throwaway profile (`run --fresh`)."""


def raid(s):
    before = s.raids()
    s.cmd('cv queue proving NORMAL SOLO')
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    s.wait(5)
    return s.raids() - before


def run(s):
    s.lobby()
    raid(s)
    spot = s.always_extract('proving')
    s.rcon(f'execute as {s.name} at @s run tp @s {spot[0]} {spot[1]} {spot[2]}')
    s.wait_until(s.in_lobby, 40, 'the extract')
    s.wait(2.5)  # the curtain lifts and the screen opens 30 ticks after arriving
    s.park_mouse()
    s.shot('settlement-ok')
    s.rcon(f'cv close {s.name}')
    s.wait(1)
    raid(s)
    s.rcon(f'damage {s.name} 1000 minecraft:generic')
    s.wait_until(s.in_lobby, 40, 'the way home after dying')
    s.wait(2.5)
    s.park_mouse()
    s.shot('settlement-fail')
    s.rcon(f'cv close {s.name}')
    s.wait(0.5)
