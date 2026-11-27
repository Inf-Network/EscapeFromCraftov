"""Which drops of health tilt the player's own view (#157): vanilla's hurt shake, measured frame by
frame (tools/shots/rollgrab.py). Arg: a tag for the output (default `now`), e.g. `hurtshake:before`.

The vanilla client shakes the view for half a second when a health update says less than the health
it holds, or on a damage event of the player. The player's own entity data carries the health too
and sets it without a shake, so a health update that comes after it shows no drop.

Each part stands on a platform high above the map facing a horizon built for it, a white wall in
front of a black one, whose top edge rollgrab reads the roll off; the still frame of each moment is
half a second before it. The shake turns about the direction the last hit with knockback came from:
until one comes it is a roll, so the guard's hit, which sets it, comes last and from the side (a
roll again). On the proving ground (solo, carrying a self-revive injector):
  bleed, bleed-2, bleed-3  three ticks of a heavy bleed on the chest (meant NOT to shake: #41)
  injury                   the test armory's 模拟受伤 (health to 40, a bleed and two fractures)
  knockdown                a lethal hit (/damage, cancelled) knocks the player down (#152)
  hit                      a guard's round lands (the control: a real hit, which always shakes)
Then on the training ground, standing past the trap line, the range gate is opened by command, and
the tripwire goes off (tripwire): health 18 lower, a broken leg and a bleed.

Shots: hurtshake-<tag>-view (the horizon, standing), and in tools/shots/out/hurtshake-<tag>/ for
every moment <label>-sheet.png (the still and the frames after it, each with the edge drawn and its
roll), <label>-peak.png and <label>.json, and summary.json. Prints a table of the peak roll of each
moment; 3 degrees or more is a shake.
"""
import json
import math
import re
import subprocess
import time
from pathlib import Path

SHOTS = Path(__file__).resolve().parent.parent
OUT = SHOTS / 'out'
Y = 150  # the platform's top, high above either map
SOLDIERS = ('zombie', 'husk', 'drowned', 'bee')
EMPTY = 6  # a hotbar slot the starter kit leaves empty: nothing in hand to cover the horizon


class Grab:
    """rollgrab.py running in the background: frames are kept around each mark."""

    def __init__(self, s, out):
        self.proc = subprocess.Popen(['/usr/bin/python3', '-I', str(SHOTS / 'rollgrab.py'), '--display', s.display,
                                      '--out', str(out)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        if self.proc.stdout.readline().strip() != 'ready':
            raise RuntimeError('rollgrab did not start')

    def mark(self, label, at=None):
        self.proc.stdin.write(f'mark {label} {at or time.time():.3f}\n')
        self.proc.stdin.flush()

    def stop(self):
        self.proc.stdin.close()
        results = [json.loads(line) for line in self.proc.stdout if line.startswith('{')]
        self.proc.wait()
        return results


# Moments whose event did not happen (the click missed, the gate did not trip): their roll means
# nothing, and a "still" for them would read like the bug was back.
MISSED = set()


def health(s):
    value = s.data('Health')
    return float(value.rstrip('fdF')) if value else None


def stand(s, x, z):
    s.rcon(f'execute as {s.name} at @s run tp @s {x + 0.5} {Y} {z + 0.5} 180 0')


def rig(s, x, z):
    """A platform at Y and, to the north, the horizon: a white wall 14 blocks off whose top edge is a
    little above a standing player's eyes, and a black wall behind it filling the view above."""
    world = s.dimension()
    for fill in (f'{x - 7} {Y - 1} {z - 3} {x + 3} {Y - 1} {z + 3} minecraft:smooth_stone',
                 f'{x - 30} {Y - 12} {z - 14} {x + 30} {Y + 1} {z - 14} minecraft:white_concrete',
                 f'{x - 60} {Y - 30} {z - 32} {x + 60} {Y + 40} {z - 32} minecraft:black_concrete'):
        s.rcon(f'execute in {world} run fill {fill}')
    stand(s, x, z)
    s.rcon(f'effect give {s.name} minecraft:night_vision 600 0 true')


def drops(s, grab, label, count, timeout):
    """Marks the next `count` drops of the player's health (read by RCON) as they are seen."""
    last = health(s)
    seen = 0
    end = time.time() + timeout
    while seen < count and time.time() < end:
        now = health(s)
        if now is not None and last is not None and now < last - 0.01:
            grab.mark(label)
            seen += 1
        last = now if now is not None else last
        time.sleep(0.02)
    return seen


def clear_soldiers(s):
    for kind in SOLDIERS:
        s.rcon(f'kill @e[type=minecraft:{kind}]')


def proving(s, tag, out, results):
    s.lobby()
    s.hotbar_slot('revive_kit')  # solo, a lethal hit then knocks down instead of killing
    before = s.raids()
    s.cmd('cv queue proving NORMAL SOLO')
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    mine = s.raids() - before
    grab = None
    try:
        s.wait(5)  # the insertion camera and spawn protection are over
        clear_soldiers(s)
        pos = [float(v) for v in re.findall(r'(-?[\d.]+)d', s.data('Pos') or '')]
        x, z = math.floor(pos[0]), math.floor(pos[2])
        rig(s, x, z)
        s.rcon(f'cv injure {s.name} clear')
        s.select(EMPTY)
        s.wait(2)
        s.clear_chat()
        s.shot(f'hurtshake-{tag}-view')
        grab = Grab(s, out)

        # A bleed: three ticks, 1.5 s apart.
        s.rcon(f'cv injure {s.name} chest heavy')
        if drops(s, grab, 'bleed', 3, 8) < 3:
            print('hurtshake: the bleed did not take health three times')
        s.rcon(f'cv injure {s.name} clear')
        s.wait(1.5)

        # The test armory's 模拟受伤 (slot 47 of six rows).
        s.cmd('cv items')
        s.wait(1.5)
        s.mouse_to(*s.slot_center(47, 6))
        s.wait(1.2)
        before = health(s)
        grab.mark('injury')
        s.xdo('click', '1')
        s.wait(1.5)
        after = health(s)
        if before is None or after is None or not after < before:
            MISSED.add('injury')
            print(f'hurtshake: 模拟受伤 took no health ({before} -> {after}): the click missed')
        s.key('Escape')
        s.rcon(f'cv injure {s.name} clear')
        stand(s, x, z)
        s.wait(1.5)

        # Knocked down by a lethal hit, then up again with the injector.
        grab.mark('knockdown')
        s.rcon(f'damage {s.name} 500 minecraft:generic')
        s.wait(2)
        down = health(s)
        if down is None or down > 1.0:
            MISSED.add('knockdown')
            print(f'hurtshake: no knock-down (health {down})')
        slot = s.slot_of('revive_kit')
        if slot is not None:
            s.select(slot)
            s.wait(0.5)
            with s.mouse(3):
                s.wait(5.5)
        s.wait(1)
        print(f'hurtshake: health {health(s)} after the injector (35 once up)')
        s.rcon(f'cv injure {s.name} clear')
        stand(s, x, z)
        s.select(EMPTY)
        s.wait(1.5)

        # The control: a guard's round, from the side.
        s.rcon(f'cv ai spawn guard {x - 5.5} {Y} {z + 0.5} {next(iter(mine))}')
        if not drops(s, grab, 'hit', 1, 40):
            print('hurtshake: the guard never hit the player')
        s.wait(1.2)
        clear_soldiers(s)
    finally:
        if grab:
            results += grab.stop()
        clear_soldiers(s)
        s.rcon(f'cv injure {s.name} clear')
        s.rcon(f'effect clear {s.name}')
        s.extract('proving', mine)


def training(s, tag, out, results):
    before = s.raids()
    s.cmd('cv training')
    s.wait_until(s.in_raid, 60, 'the training ground')
    mine = s.raids() - before
    grab = None
    try:
        s.wait(4)
        s.rcon(f'cv close {s.name}')
        # Between the trap line and the next gate (TrainingGroundGenerator TRAP_X 51, GATE_HEAL 60):
        # only the player's x counts, so the platform can stand high above the corridor. The trap
        # waits for the range gate (x 45) to open.
        x, z = 55, 10
        rig(s, x, z)
        s.select(EMPTY)
        s.wait(2.5)
        s.clear_chat()
        grab = Grab(s, out)
        s.wait(1)
        before = health(s)
        grab.mark('tripwire')
        s.rcon(f'execute in {s.dimension()} run fill 45 65 9 45 67 11 minecraft:air')
        s.wait(1.5)
        after = health(s)
        if before is None or after is None or not after < before:
            MISSED.add('tripwire')
            print(f'hurtshake: the tripwire took no health ({before} -> {after})')
    finally:
        if grab:
            results += grab.stop()
        s.rcon(f'effect clear {s.name}')
        for raid in mine:
            s.rcon(f'cv end {raid}')
        s.wait_until(s.in_lobby, 60, 'the lobby')
        # The settlement screen opens 30 ticks after arriving.
        s.wait(2.5)
        s.rcon(f'cv close {s.name}')
        s.wait(0.5)


def run(s):
    tag = s.arg or 'now'
    out = OUT / f'hurtshake-{tag}'
    results = []
    MISSED.clear()
    try:
        proving(s, tag, out, results)
        training(s, tag, out, results)
    finally:
        for r in results:
            r['happened'] = r['label'] not in MISSED
            verdict = 'did not happen' if not r['happened'] else 'SHAKES' if r['shakes'] else 'still'
            print(f"hurtshake {tag:8} {r['label']:10} peak roll {r['peak'] if r['peak'] is not None else '-':>6} deg "
                  f"at {r['at_ms'] if r['at_ms'] is not None else '-':>5} ms (rise {r['rise'] if r['rise'] is not None else '-':>5}) "
                  f"over {r['frames']:3} frames: {verdict}")
        (out / 'summary.json').parent.mkdir(parents=True, exist_ok=True)
        (out / 'summary.json').write_text(json.dumps(results, indent=1))
