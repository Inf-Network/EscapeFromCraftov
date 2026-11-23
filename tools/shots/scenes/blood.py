"""The blood edge of low health (#6): a solo proving-ground raid, health set to 30, 20 and 8 (the
three steps under 35, 24 and 12), then healed and extracted. Shots: blood-1, blood-2, blood-3."""


def run(s):
    s.lobby()
    before = s.raids()
    s.cmd('cv queue proving NORMAL SOLO')
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    mine = s.raids() - before
    try:
        s.wait(5)
        s.select(0)
        for level, damage in ((1, 70), (2, 10), (3, 12)):  # 100 -> 30 -> 20 -> 8
            s.rcon(f'damage {s.name} {damage} minecraft:generic')
            s.wait(1.5)  # past the red hit flash
            s.clear_chat()
            s.shot('blood-%d' % level)
        s.rcon(f'effect give {s.name} minecraft:instant_health 1 10 true')
    finally:
        s.extract('proving', mine)
