"""Escort refusals and the house bounty text (#63).

The bounty board's header tooltip (bounty-house): the top player's house bounty counts only when
they themselves deploy for ranked.

The escort hall's 护航规则 tooltip (escort-rules) states the same one-contract rules as the README
and the refusals: a client cancels their own contract first, an escort who leaves the squad before
deploying gives the contract up, and after the client deploys everyone waits for that raid to end.

Then the escort hall with a puppet (ShotMate) that posts a contract of its own. ShotCam posts one
too and clicks ShotMate's: the refusal flashes into that button's tooltip and tells ShotCam to
cancel its own first (escort-busy-client; it used to name ShotCam as the client it was escorting).
ShotCam cancels, takes ShotMate's contract and clicks 发布委托: the tooltip says it is escorting
ShotMate and must leave that squad first (escort-busy-escort). Everything is called off again.
"""

MAIN_ROWS, HALL_ROWS, BOARD_ROWS = 6, 5, 4
POST = 38  # first 发布委托 button in the hall
RULES = 36  # 护航规则


def hall(s):
    s.cmd('cv')
    s.wait(1.5)
    s.click_slot(16, MAIN_ROWS)
    s.wait(1.2)


def run(s):
    s.lobby()
    s.rcon(f'cv money {s.name} 100000')
    s.clear_chat()
    s.cmd('cv')
    s.wait(1.5)
    try:
        s.click_slot(25, MAIN_ROWS)
        s.wait(1.2)
        s.mouse_to(*s.slot_center(4, BOARD_ROWS))
        s.wait(0.8)
        s.shot('bounty-house')
    finally:
        s.park_mouse()
        s.key('Escape')
        s.wait(0.6)

    hall(s)
    try:
        s.mouse_to(*s.slot_center(RULES, HALL_ROWS))
        s.wait(0.8)
        s.shot('escort-rules')
    finally:
        s.park_mouse()
        s.key('Escape')
        s.wait(0.6)

    with s.puppet() as mate:
        s.rcon(f'cv money {mate.name} 100000')
        mate.send('chat /cv')
        s.wait(1.5)
        mate.send('click 16')
        s.wait(1.2)
        mate.send(f'click {POST}')
        s.wait(1)
        mate.send('close')
        s.clear_chat()
        hall(s)
        try:
            s.click_slot(POST, HALL_ROWS)  # ShotCam's own contract, after ShotMate's in slot 0
            s.wait(1)
            s.park_mouse()
            s.click_slot(0, HALL_ROWS)     # ShotMate's: refused, ShotCam is a client
            s.shot('escort-busy-client')
            s.park_mouse()
            s.wait(2.2)                    # the flash is gone before the next click
            s.click_slot(1, HALL_ROWS)     # ShotCam's own: cancelled
            s.wait(1)
            s.click_slot(0, HALL_ROWS)     # ShotMate's: taken, ShotCam joins its squad
            s.wait(1)
            s.park_mouse()
            s.click_slot(POST, HALL_ROWS)  # posting while escorting: refused
            s.shot('escort-busy-escort')
        finally:
            s.park_mouse()
            s.key('Escape')
            s.wait(0.6)
            s.cmd('cv party leave')
            s.wait(1)
            mate.send('chat /cv')
            s.wait(1.5)
            mate.send('click 16')
            s.wait(1.2)
            mate.send('click 0')               # ShotMate calls its contract off
            s.wait(1)
            mate.send('close')
