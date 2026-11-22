"""The deploy menu (/cv deploy): map, difficulty and squad size."""


def run(s):
    s.lobby()
    s.clear_chat()
    s.cmd('cv deploy')
    s.wait(1.5)
    try:
        s.park_mouse()
        s.shot('deploy')
    finally:
        s.key('Escape')
