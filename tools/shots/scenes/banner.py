"""The alert banner under the compass next to the top-left raid panels (#178): a solo proving-ground
raid until its timed extract opens two minutes in, which announces itself in the banner. Shots, all
suffixed with the window height and GUI scale: <label>-raid (before the alert), <label>-alert, and
<label>-alert-chat with the chat open. Arg: the label (default banner). Run it at the owner's GUI
size, e.g. `--size 1382x806 --gui-scale 2` (a 3456x2018 window at GUI scale 5 is 691x403 too)."""


def run(s):
    _, height = s.cfg.size.split('x')
    prefix = f'{s.arg or "banner"}-{height}p-gui{s.gui_scale()}-'
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
        s.clear_chat()
        s.wait(1)
        s.shot(prefix + 'raid')
        # The timed extract opens 120 s after the start (ProvingGroundGenerator) and the banner stays 6 s.
        s.wait_until(lambda: '现已开放' in s.rcon(f'cv hud {s.name}'), 150, 'the timed extract to open')
        s.clear_chat()
        s.wait(0.6)
        s.shot(prefix + 'alert')
        s.key('t')
        s.wait(0.8)
        try:
            s.shot(prefix + 'alert-chat')
        finally:
            s.key('Escape')
            s.wait(0.5)
    finally:
        s.extract('proving', mine)
