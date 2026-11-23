"""Knife inspect (F) pressed the way real play presses it (issue #104). No arg.

tools/bot/inspect.js sends one packet at a time; a real client sends what one of its ticks did in
the order that tick does it: F first, the Shift and W let go (and so a sprint ended) after it, and a
hotbar key pressed in the same tick only at the start of the next tick. This presses F with the real
client in 28 situations, mixed, and reads the server's verdict on each press from the debug log
(needs `debug: true` in the server's plugins/Craftov/config.yml). "Same instant" is one xdotool call
with no delay between the keys, so both land in one client tick:

   1 standing still                         15 hotbar key 3 and F, same instant
   2 F again 0.6 s into the inspect          16 F 0.1 s after hotbar key 3
   3 walking (W held)                       17 the mouse wheel onto the knife, F 0.15 s later
   4 strafing (D held)                      18 Shift let go and F, same instant
   5 walking backwards (S held)             19 F 0.15 s after letting go of Shift
   6 in the air after a jump                20 Shift pressed and F, same instant (the operator
   7 sprinting (Ctrl+W held)                   skill, not an inspect)
   8 W let go after a sprint and F, same    21 F 0.05 s after closing the inventory (E)
     instant                                22 F 0.3 s after closing the inventory
   9 F 0.1 s after a sprint ends            23 standing still (an inspect to run out)
  10 Ctrl let go, W still held              24 F 2.4 s into that inspect, as it ends
  11 F 0.05 s after a swing (left click)    25 F 0.05 s after Q (the client swings)
  12 F 0.15 s after a swing                 26 F 0.05 s after a right-click
  13 standing still (an inspect to cut)     27 walking, F
  14 a swing into it and F, same instant    28 walking, F again 0.5 s later

The client keeps sprinting as long as W is held, also with Ctrl let go (10). The rule: with a knife
in hand, F inspects unless the player sprints (7 and 10: refused, and the action bar says why) or
holds Shift (20: the operator skill); F during an inspect starts it over, and F during a swing
starts it once the swing is over. Logs one PASS/FAIL line per press and the debug lines it was
judged on: `[inspect-keys] PASS #08 W let go after a sprint + F, same instant -> started`.

Shots: inspect-keys-NN (0.6 s after each press: the knife held up in front of the camera, or for a
refused press the knife at rest and the reason on the action bar) and inspect-keys-sheet (all of
them on one sheet, each labelled with the server's verdict). The camera stands on a strip of stone
the scene lays outside the lobby hall (and takes away afterwards), with night vision, a gun in
hotbar slot 1 and the knife in the melee slot (hotbar key 3).
"""
import re
import subprocess
import time
from pathlib import Path

KNIFE_SLOT = 2
GUN_SLOT = 0
START = (0.5, 65.0, 30.5)   # facing south (+z), along the strip
STRIP = ('-4 64 27 5 64 100 minecraft:smooth_stone', '-4 65 27 5 70 100 minecraft:air')
UNSTRIP = '-4 64 27 5 70 100 minecraft:air'
HELD = ('w', 'a', 's', 'd', 'Control_L', 'Shift_L', 'space')
SHOT_AFTER = 0.6
INSPECT = 2.5   # combat.melee.inspect-ticks (50) in seconds


def _now(*keys):
    """xdotool actions that run with no delay between them, so they land in one client tick."""
    out = []
    for action, key in keys:
        out += [action, '--delay', '0', key]
    return tuple(out)


class Press:
    def __init__(self, number, label, expect):
        self.number, self.label, self.expect = number, label, expect
        self.at = 0.0
        self.lines = ''
        self.verdict = None
        self.ok = False


class Run:
    """Presses F, remembers where in the server log each press starts, and takes its frame."""

    def __init__(self, s):
        self.s = s
        self.presses = []
        self.marks = []   # log offset at each press, in press order

    def log(self):
        return self.s.cfg.server_dir / 'logs' / 'latest.log'

    def offset(self):
        return self.log().stat().st_size

    def f(self, label, expect='inspect', before=()):
        """`before` (xdotool actions) and then F, in one xdotool call."""
        press = Press(len(self.presses) + 1, label, expect)
        self.presses.append(press)
        self.marks.append(self.offset())
        self.s.xdo(*before, 'key', '--delay', '0', 'f')
        press.at = time.time()
        return press

    def shot(self, press, delay=SHOT_AFTER):
        self.s.wait(max(0.0, press.at + delay - time.time()))
        self.s.shot(f'inspect-keys-{press.number:02d}', quick=True)

    def settle(self, press):
        """Lets go of every key and waits until the press's inspect would be over."""
        self.s.xdo('keyup', *HELD)
        self.s.wait(max(0.0, press.at + INSPECT + 0.3 - time.time()))

    def judge(self):
        name = re.escape(self.s.name)
        with open(self.log(), 'rb') as f:
            f.seek(self.marks[0])
            raw = f.read()
        bounds = [m - self.marks[0] for m in self.marks] + [len(raw)]
        for i, press in enumerate(self.presses):
            press.lines = raw[bounds[i]:bounds[i + 1]].decode('utf-8', 'replace')
            inspect = re.findall(rf'\[debug\] inspect {name} F -> (.*?) tick=', press.lines)
            key = re.findall(rf'\[debug\] F {name} -> (.*?) \(', press.lines)
            started = next((v for v in inspect if v.split()[0] in ('started', 'restarted')), None)
            press.verdict = started or (inspect[-1] if inspect else key[-1] if key else 'no F reached the server')
            press.ok = (started is not None) == (press.expect == 'inspect')
            if press.ok and press.expect != 'inspect':
                press.ok = press.expect in press.verdict
            print(f'[inspect-keys] {"PASS" if press.ok else "FAIL"} #{press.number:02d} {press.label} -> {press.verdict}'
                  + ('' if press.ok else f'  (expected: {press.expect})'), flush=True)
            for line in press.lines.splitlines():
                if re.search(rf'\[debug\] (inspect {name}|F {name})', line):
                    print('      ', line.split('[debug] ', 1)[1], flush=True)


def _home(s, slot=KNIFE_SLOT):
    s.xdo('keyup', *HELD)
    s.rcon(f'execute as {s.name} at @s run tp @s {START[0]} {START[1]} {START[2]} 0 0')
    s.select(slot)
    s.clear_chat()
    s.wait(0.6)


def _sheet(s, presses):
    """All frames on one sheet, each labelled with the press and the server's verdict."""
    out = Path(__file__).resolve().parent.parent / 'out'
    args = ['montage', '-background', '#202020', '-fill', 'white', '-pointsize', '15']
    # The verdicts quote the action bar's Chinese; ImageMagick's default font has no CJK glyphs.
    font = subprocess.run(['fc-match', '-f', '%{file}', ':lang=zh-cn'], capture_output=True, text=True).stdout.strip()
    if font:
        args += ['-font', font]
    for p in presses:
        args += ['-label', f'{"OK" if p.ok else "XX"} #{p.number:02d} {p.label}\n-> {p.verdict}',
                 str(out / f'inspect-keys-{p.number:02d}.png')]
    args += ['-tile', '4x', '-geometry', '480x270+4+4', str(out / 'inspect-keys-sheet.png')]
    subprocess.run(args, check=True)
    s.shots.append('inspect-keys-sheet')


def run(s):
    s.lobby()
    for fill in STRIP:
        s.rcon(f'execute in minecraft:craftov_lobby run fill {fill}')
    s.rcon(f'effect give {s.name} minecraft:night_vision 600 0 true')
    s.rcon(f'effect give {s.name} minecraft:saturation 600 0 true')
    try:
        r = Run(s)
        _presses(s, r)
        s.wait(0.5)
        r.judge()
        s.develop()
        _sheet(s, r.presses)
        failed = sum(not p.ok for p in r.presses)
        print(f'[inspect-keys] {len(r.presses) - failed}/{len(r.presses)} presses as the rule says', flush=True)
    finally:
        s.xdo('keyup', *HELD)
        s.rcon(f'effect clear {s.name}')
        s.rcon(f'execute in minecraft:craftov_lobby run fill {UNSTRIP}')
        s.lobby()


def _sprint(s):
    s.xdo('keydown', 'Control_L', 'keydown', 'w')
    s.wait(1.0)


def _presses(s, r):
    if s.item_in(GUN_SLOT) is None:
        s.give('k47')

    _home(s)
    p = r.f('standing still')
    r.shot(p)
    p = r.f('F again 0.6 s into the inspect')
    r.shot(p)
    r.settle(p)

    for key, label in (('w', 'walking (W held)'), ('d', 'strafing (D held)'), ('s', 'walking backwards (S held)')):
        _home(s)
        s.xdo('keydown', key)
        s.wait(0.7)
        p = r.f(label)
        r.shot(p)
        r.settle(p)

    _home(s)
    s.xdo('key', 'space')
    s.wait(0.15)
    p = r.f('in the air after a jump')
    r.shot(p)
    r.settle(p)

    _home(s)
    _sprint(s)
    p = r.f('sprinting (Ctrl+W held)', expect='冲刺')
    r.shot(p)
    r.settle(p)

    _home(s)
    _sprint(s)
    p = r.f('W let go after a sprint + F, same instant', before=_now(('keyup', 'w'), ('keyup', 'Control_L')))
    r.shot(p)
    r.settle(p)

    _home(s)
    _sprint(s)
    s.xdo('keyup', 'w', 'keyup', 'Control_L')
    s.wait(0.1)
    p = r.f('F 0.1 s after a sprint ends')
    r.shot(p)
    r.settle(p)

    _home(s)
    _sprint(s)
    s.xdo('keyup', 'Control_L')
    s.wait(0.3)
    p = r.f('Ctrl let go, W still held (still sprinting)', expect='冲刺')
    r.shot(p)
    r.settle(p)

    for gap in (0.05, 0.15):
        _home(s)
        s.xdo('click', '1')
        s.wait(gap)
        p = r.f(f'F {gap:.2f} s after a swing')
        r.shot(p)
        r.settle(p)

    _home(s)
    p = r.f('standing still (an inspect to cut into)')
    r.shot(p, 0.45)
    s.wait(max(0.0, p.at + 0.6 - time.time()))
    p = r.f('a swing into it + F, same instant', before=('click', '1'))
    r.shot(p)
    r.settle(p)

    _home(s, GUN_SLOT)
    p = r.f('hotbar key 3 + F, same instant', before=_now(('key', str(KNIFE_SLOT + 1))))
    r.shot(p)
    r.settle(p)

    _home(s, GUN_SLOT)
    s.xdo('key', str(KNIFE_SLOT + 1))
    s.wait(0.1)
    p = r.f('F 0.1 s after hotbar key 3')
    r.shot(p)
    r.settle(p)

    _home(s, KNIFE_SLOT - 1)
    s.xdo('click', '5')   # wheel down: the next slot
    s.wait(0.15)
    p = r.f('mouse wheel onto the knife, F 0.15 s later')
    r.shot(p)
    r.settle(p)

    _home(s)
    s.xdo('keydown', 'Shift_L')
    s.wait(0.6)
    p = r.f('Shift let go + F, same instant', before=_now(('keyup', 'Shift_L')))
    r.shot(p)
    r.settle(p)

    _home(s)
    s.xdo('keydown', 'Shift_L')
    s.wait(0.6)
    s.xdo('keyup', 'Shift_L')
    s.wait(0.15)
    p = r.f('F 0.15 s after letting go of Shift')
    r.shot(p)
    r.settle(p)

    _home(s)
    p = r.f('Shift pressed + F, same instant (skill)', expect='skill', before=_now(('keydown', 'Shift_L')))
    r.shot(p)
    r.settle(p)

    for gap in (0.05, 0.3):
        _home(s)
        s.xdo('key', 'e')
        s.wait(0.7)
        s.xdo('key', 'e')
        s.wait(gap)
        p = r.f(f'F {gap:.2f} s after closing the inventory')
        r.shot(p)
        r.settle(p)

    _home(s)
    first = r.f('standing still (an inspect to run out)')
    r.shot(first)
    s.wait(max(0.0, first.at + 2.4 - time.time()))
    p = r.f('F 2.4 s into that inspect, as it ends')
    r.shot(p)
    r.settle(p)

    _home(s)
    s.xdo('key', 'q')
    s.wait(0.05)
    p = r.f('F 0.05 s after Q')
    r.shot(p)
    r.settle(p)

    _home(s)
    s.xdo('click', '3')
    s.wait(0.05)
    p = r.f('F 0.05 s after a right-click')
    r.shot(p)
    r.settle(p)

    _home(s)
    s.xdo('keydown', 'w')
    s.wait(0.5)
    p = r.f('walking, F')
    r.shot(p, 0.45)
    p = r.f('walking, F again 0.5 s later')
    r.shot(p)
    r.settle(p)
