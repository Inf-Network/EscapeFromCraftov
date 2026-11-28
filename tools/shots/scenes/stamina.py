"""The sprint stamina bar (#179) in a solo proving-ground raid: full (hidden), at 45 after a sprint,
and a few seconds after running it down to 0, still out of breath (red). Prints the server's stamina next to what the HUD was given (`cv hud`).
Arg: a label that starts every shot name (default stamina): <label>-<height>p-gui<scale>-full / -45 /
-tired."""
import re


def run(s):
    _, height = s.cfg.size.split('x')
    prefix = f'{s.arg or "stamina"}-{height}p-gui{s.gui_scale()}-'
    s.lobby()
    s.hold('m4c')
    before = s.raids()
    s.cmd('cv queue proving NORMAL SOLO')
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    mine = s.raids() - before
    try:
        s.wait(4)
        s.hold('m4c')
        s.rcon(f'effect clear {s.name}')
        for name, value, wait in (('full', 100, 0.5), ('45', 45, 0.5), ('tired', 0, 3)):
            s.rcon(f'cv slide {s.name} stamina {value}')
            s.clear_chat()
            s.wait(wait)
            s.shot(prefix + name)
            server = re.search(r'stamina=([\d.]+)', s.rcon(f'cv slide {s.name}'))
            hud = re.search(r'stamina=(\S+)', s.rcon(f'cv hud {s.name}'))
            print(f'stamina {name}: server {server and server.group(1)}, HUD {hud and hud.group(1)}')
    finally:
        s.extract('proving', mine)
