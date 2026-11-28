"""The lobby's name labels up close (#181): the market's title and subtitle from 2.5 and 5 blocks, and
the season leaderboard. Arg: a label that starts every shot name (default labels): <label>-<height>p-
gui<scale>-near / -far / -board."""


def run(s):
    _, height = s.cfg.size.split('x')
    prefix = f'{s.arg or "labels"}-{height}p-gui{s.gui_scale()}-'
    s.lobby()
    s.key('1')
    s.clear_chat()
    # The market's label floats at (0.5, 67.55, 8.5) (LobbyService.STATIONS_XZ, LobbyBuilder.Y + 3.55).
    for name, z, pitch in (('near', 6.0, -12), ('far', 3.5, -8)):
        s.rcon(f'execute as {s.name} at @s run tp @s 0.5 65 {z} 0 {pitch}')
        s.clear_chat()
        s.wait(1.5)
        s.shot(prefix + name)
    s.rcon(f'execute as {s.name} at @s run tp @s 14.5 65 4.5 0 -22')
    s.clear_chat()
    s.wait(1.5)
    s.shot(prefix + 'board')
