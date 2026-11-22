"""A lobby menu opened by its /cv command. Arg: the command after /cv (default: menu), dots for spaces.

`menu:stash` shoots /cv stash as menu-stash.png, `menu:menu` the tactical terminal. The pointer is
parked in the corner first, so no tooltip covers the screen.
"""


def run(s):
    command = (s.arg or 'menu').replace('.', ' ')
    s.lobby()
    s.clear_chat()
    s.cmd('cv ' + command)
    s.wait(1.5)
    try:
        s.park_mouse()
        s.shot('menu-' + command.replace(' ', '-'))
    finally:
        s.key('Escape')
        s.wait(0.5)
