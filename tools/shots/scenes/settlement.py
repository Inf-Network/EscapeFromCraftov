"""The settlement screen after a raid (#6): a solo proving-ground raid left through the always-open
extract (settlement-ok), then a second raid ended by dying (settlement-fail). The second leaves the
player with nothing but the knife, so run it on a throwaway profile (`run --fresh settlement`)."""


def raid(s):
    before = s.raids()
    s.cmd('cv queue proving NORMAL SOLO')
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    s.wait(5)
    return s.raids() - before


def run(s):
    s.lobby()
    mine = raid(s)
    s.extract('proving', mine)
    s.wait(1.5)  # the curtain lifts and the screen opens 30 ticks after arriving
    s.park_mouse()
    s.shot('settlement-ok')
    s.key('Escape')
    s.wait(1)
    mine = raid(s)
    s.rcon(f'damage {s.name} 1000 minecraft:generic')
    s.wait_until(s.in_lobby, 40, 'the way home after dying')
    s.wait(3)
    s.park_mouse()
    s.shot('settlement-fail')
    s.key('Escape')
