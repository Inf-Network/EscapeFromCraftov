"""The lobby hangar seen from the spawn point."""


def run(s):
    s.select(0)  # the primary weapon, whatever an earlier run left selected
    s.lobby()
    s.clear_chat()
    s.wait(2.5)  # the item name and control hints that come with switching slots fade out
    s.shot('lobby')
