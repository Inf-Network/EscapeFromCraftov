"""The in-raid HUD with something in every panel (#6, #66): a solo proving-ground raid, a gun in hand,
a fractured leg and a bleeding chest (wound icons), the crosshair on the armory crate (interaction
prompt), then the lobby HUD. Run it at the size and GUI scale to check, e.g. `--size 2560x1440
--gui-scale 3` for #66. Shots: hud-raid, hud-raid-prompt, hud-lobby (suffixed with the GUI scale
when one is given)."""


def run(s):
    suffix = '' if not s.cfg.gui_scale else '-gui%d' % s.cfg.gui_scale
    gun = s.arg or 'm4c'
    s.lobby()
    s.hold(gun)
    s.clear_chat()
    s.wait(2.5)
    s.shot('hud-lobby' + suffix)
    before = s.raids()
    s.cmd('cv queue proving NORMAL SOLO')
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    mine = s.raids() - before
    try:
        s.wait(4)
        s.hold(gun)
        s.rcon(f'cv injure {s.name} left_leg fracture')
        s.rcon(f'cv injure {s.name} chest light')
        s.rcon(f'effect clear {s.name}')
        s.wait(1.5)
        s.clear_chat()
        s.shot('hud-raid' + suffix)
        # The armory crate in front of the test spawn (tools/bot/loopholes.js openArmory).
        s.rcon(f'execute as {s.name} at @s run tp @s 8.5 65 78.9 180 55')
        s.wait(2)
        s.shot('hud-raid-prompt' + suffix)
        print(s.rcon(f'cv hud {s.name}'))
    finally:
        s.extract('proving', mine)
