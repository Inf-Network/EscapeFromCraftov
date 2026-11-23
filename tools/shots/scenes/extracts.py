"""The extract markers on the proving ground's extract strip: every extract's ring of dust at its
radius (green usable now, orange not yet: the timed one opens two minutes in, the switch one needs
its lever) and the beam over those open this raid (#40).

The proving ground is a test map, so the server needs test.enabled (shots.py setup turns it on) or
an op player (the harness ops its player). The player leaves through the always-open extract.
"""


def run(s):
    s.lobby()
    before = s.raids()
    s.cmd('cv queue proving NORMAL SOLO')
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    mine = s.raids() - before
    try:
        s.wait(4)  # the insertion camera is over
        s.hold('m4c')
        # The pads run north to south along x 106: always (z 8), timed (17), paid (26), switch (35),
        # no-backpack (44), limited (53). From the north-east end, looking south down the strip.
        s.rcon(f'execute as {s.name} at @s run tp @s 111.5 65 1.5 25 18')
        s.wait(2)
        s.shot('extracts-strip')
        # Close by: the timed pad (orange, not open yet) and the paid one (green) behind it.
        s.rcon(f'execute as {s.name} at @s run tp @s 111.5 65 13.5 50 30')
        s.wait(2)
        s.shot('extracts-close')
    finally:
        s.extract('proving', mine)
