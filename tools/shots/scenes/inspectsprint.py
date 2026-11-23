"""A knife inspect (F) on a sprint (issue #116), with the real client. Arg: a knife id (default
knife_karambit).

The camera sprints (left Ctrl and W held) down a strip of stone the scene lays outside the lobby
hall (and takes away afterwards), with a post on each side every 4 blocks so the frames show the
run, presses F with the knife in hand and keeps running through the inspect. Needs `debug: true` in the server's plugins/Craftov/config.yml: the server's verdict on
the press is read from the log. Logs PASS/FAIL lines: the press started an inspect (the server's
`F -> started` line, with sprint=true), nothing stopped it, and the server counted the player as
sprinting before F, mid-inspect and as it ended (an entity predicate through RCON).

Shots: inspect-sprint-<knife>-0 (sprinting with the knife at rest, just before F), -1 to -3 (0.65,
1.0 and 1.4 s after F: the knife up in front of the camera and turned over while the sprint goes
on) and inspect-sprint-<knife>-sheet (the four on one sheet, each labelled with what the server
said). The inspect runs for combat.melee.inspect-ticks (50, 2.5 s).
"""
import re
import subprocess
import time
from pathlib import Path

KNIFE_SLOT = 2
START = (0.5, 65.0, 30.5)   # facing south (+z), along the strip
STRIP = ('-4 64 27 5 64 100 minecraft:smooth_stone', '-4 65 27 5 70 100 minecraft:air')
UNSTRIP = '-4 64 27 5 70 100 minecraft:air'
POSTS = tuple(f'{x} 65 {z} {x} 66 {z} minecraft:{"yellow" if z // 4 % 2 else "black"}_concrete'
              for z in range(34, 100, 4) for x in (-3, 4))
HELD = ('w', 'Control_L')
TIMES = (0.65, 1.0, 1.4)
INSPECT = 2.5
SPRINTING = '{condition:"minecraft:entity_properties",entity:"this",predicate:{flags:{is_sprinting:true}}}'


def _sprinting(s):
    """Whether the server has the camera player sprinting."""
    return 'passed' in s.rcon(f'execute as {s.name} if predicate {SPRINTING}').lower()


def _log(s):
    return s.cfg.server_dir / 'logs' / 'latest.log'


def _hold_knife(s, knife):
    if s.item_in(KNIFE_SLOT) != knife:
        s.rcon(f'item replace entity {s.name} hotbar.{KNIFE_SLOT} with minecraft:air')
        s.wait(0.3)
        s.give(knife)
        s.wait_until(lambda: s.item_in(KNIFE_SLOT) == knife, 10, f'{knife} in the melee slot')


def _sheet(s, knife, labels):
    out = Path(__file__).resolve().parent.parent / 'out'
    args = ['montage', '-background', '#202020', '-fill', 'white', '-pointsize', '18']
    for i, label in enumerate(labels):
        args += ['-label', label, str(out / f'inspect-sprint-{knife}-{i}.png')]
    name = f'inspect-sprint-{knife}-sheet'
    args += ['-tile', '2x', '-geometry', '800x450+4+4', str(out / f'{name}.png')]
    subprocess.run(args, check=True)
    s.shots.append(name)


def _report(ok, what, detail=''):
    print(f'[inspect-sprint] {"PASS" if ok else "FAIL"} {what}' + (f'  ({detail})' if detail else ''), flush=True)
    return ok


def run(s):
    knife = s.arg or 'knife_karambit'
    s.lobby()
    for fill in STRIP + POSTS:
        s.rcon(f'execute in minecraft:craftov_lobby run fill {fill}')
    s.rcon(f'effect give {s.name} minecraft:night_vision 120 0 true')
    s.rcon(f'effect give {s.name} minecraft:saturation 120 0 true')
    try:
        _hold_knife(s, knife)
        s.rcon(f'execute as {s.name} at @s run tp @s {START[0]} {START[1]} {START[2]} 0 0')
        s.select(KNIFE_SLOT)
        s.clear_chat()
        s.wait(1.0)

        s.focus()
        s.xdo('keydown', 'Control_L', 'keydown', 'w')
        s.wait(1.0)
        before = _sprinting(s)
        s.shot(f'inspect-sprint-{knife}-0', quick=True)
        mark = _log(s).stat().st_size
        s.xdo('key', 'f')
        pressed = time.time()
        during = None
        for i, at in enumerate(TIMES, 1):
            s.wait(max(0.0, pressed + at - time.time()))
            s.shot(f'inspect-sprint-{knife}-{i}', quick=True)
            if i == 2:
                during = _sprinting(s)
        s.wait(max(0.0, pressed + INSPECT - 0.2 - time.time()))
        late = _sprinting(s)
        s.xdo('keyup', *HELD)
        s.wait(0.5)

        with open(_log(s), 'rb') as f:
            f.seek(mark)
            lines = f.read().decode('utf-8', 'replace')
        name = re.escape(s.name)
        verdicts = re.findall(rf'\[debug\] inspect {name} F -> (\S+).*? sprint=(\w+)', lines)
        stops = re.findall(rf'\[debug\] inspect {name} stop (.*?) tick=', lines)
        verdict = f'F -> {verdicts[0][0]} (sprint={verdicts[0][1]})' if verdicts else 'no F reached the server'
        ok = all([
            _report(before, 'the server has the player sprinting before F'),
            _report(bool(verdicts) and verdicts[0] == ('started', 'true'), 'F on the sprint starts an inspect', verdict),
            _report(not stops, 'nothing stops the inspect', ', '.join(stops)),
            _report(bool(during) and late, 'the sprint goes on through the inspect', f'mid-inspect {during}, as it ends {late}'),
        ])
        s.develop()
        _sheet(s, knife, [
            f'0  sprinting, knife at rest (server: sprinting={str(before).lower()})',
            f'1  0.65 s after F: {verdict}',
            f'2  1.0 s after F (server: sprinting={str(during).lower()})',
            f'3  1.4 s after F (server 2.3 s after F: sprinting={str(late).lower()})',
        ])
        print(f'[inspect-sprint] {"all checks passed" if ok else "a check failed"}', flush=True)
    finally:
        s.xdo('keyup', *HELD)
        s.rcon(f'effect clear {s.name}')
        s.rcon(f'execute in minecraft:craftov_lobby run fill {UNSTRIP}')
        s.lobby()
