"""The landing briefing (#7): the one chat message on landing in a raid (map, difficulty, time, the
extracts on the compass, night, bounties, insurance, and the guide's extract step for a player
still on the guide). A solo proving-ground raid, left through the always-open extract.

landing: just after landing, the briefing in chat under the HUD.
"""


def run(s):
    s.lobby()
    before = s.raids()
    s.cmd('cv queue proving NORMAL SOLO')
    s.wait(1)
    s.clear_chat()  # what the lobby said before; the briefing comes on landing
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    mine = s.raids() - before
    s.wait(2.5)
    s.shot('landing')
    s.extract('proving', mine)
