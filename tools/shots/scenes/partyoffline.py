"""A teammate who dropped out of a raid, as the party leader sees them back in the lobby (#38).

ShotMate (a puppet, needs tools/bot/node_modules) joins the player's party, both land in a trio raid
on the proving ground, and ShotMate quits there. The player goes back to the lobby (/cv lobby voids
the raid for them) while ShotMate's reconnect window still runs. Shots: party-offline-list (the
/party line, the offline teammate named and tagged) and party-offline-deploy (the deploy screen's
squad button). ShotMate is kicked at the end, so the next scene starts with no party.
"""


def run(s):
    s.lobby()
    with s.puppet() as mate:
        s.cmd(f'party invite {mate.name}')
        s.wait(1)
        mate.send('chat /party accept')
        s.wait(1.5)
        s.cmd('cv queue proving NORMAL TRIO')
        s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
        s.wait(4)
        mate.send('quit')
        s.wait(2)
    s.lobby()
    try:
        s.clear_chat()
        s.cmd('party')
        s.wait(1)
        s.key('t')  # chat open: the line stays on screen with the history
        s.wait(0.5)
        s.shot('party-offline-list')
        s.key('Escape')
        s.wait(0.5)
        s.cmd('cv deploy')
        s.wait(1.5)
        s.mouse_to(*s.slot_center(38, 5))
        s.wait(0.8)
        s.shot('party-offline-deploy')
        s.park_mouse()
        s.key('Escape')
        s.wait(0.5)
    finally:
        s.cmd(f'party kick {mate.name}')
        s.wait(0.5)
