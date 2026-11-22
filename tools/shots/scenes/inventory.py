"""The inventory screen (E) with a gun in the hotbar."""


def run(s):
    s.lobby()
    s.hold('m4c')
    s.clear_chat()
    s.key('e')
    s.wait(1)
    try:
        s.park_mouse()
        s.shot('inventory')
    finally:
        s.key('Escape')
