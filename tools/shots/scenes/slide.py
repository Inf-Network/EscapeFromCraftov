"""Slide (issue #9, SlideService) with a real client, on the proving ground. Arg: gun id (k47).

ShotCam and a puppet (tools/bot/puppet.js, needs tools/bot/node_modules) queue as one squad. On open
ground ShotCam sprints (W and left Ctrl held) and taps Shift twice the way a player does (80 ms down,
80 ms up, down again and held), and frames of the slide are grabbed as it goes:

  slide-fp-stand       first person standing at the start of the lane, for the eye height to compare
  slide-fp-0/1/2       first person, about 0.2 / 0.45 / 0.7 s after the second tap: the eyes about 1
                       block over the ground (lower: the horizon sits higher on the walls ahead), the
                       gun in hand, no "Press Shift to Dismount" over the hotbar
  slide-fp-after       standing again where the slide ended, keys let go: the eyes back at standing
                       height, not stuck in the floor
  slide-tp-0/1         the same in third person from behind (F5)
  slide-others-0/1/2   the puppet sliding past ShotCam, seen from the side with the HUD hidden: sat low
                       with the legs forward and the gun shouldered in both hands
  slide-others-front   the puppet sliding straight at ShotCam

Logs PASS/FAIL from /cv slide for each slide (ShotCam's comes from the vanilla client's own keys):
that it happened, how far it went and that the player stands on the ground after it. Both leave
through the always-open extract at the end.
"""
import re

MAP = 'proving'
# Open ground between the gallery and the armory, running north (yaw 180) along x 28.5.
LANE_X = 28.5
START_Z = 86.5
GROUND = 65


def raid(s, mate):
    s.cmd('cv party invite ' + mate.name)
    s.wait(1)
    mate.send('chat /cv party accept')
    s.wait(1.5)
    before = s.raids()
    s.cmd(f'cv queue {MAP} NORMAL TRIO')
    s.wait_until(s.in_raid, 150, 'the proving ground raid to start')
    s.wait_until(lambda: 'craftov_raid' in (s.data('Dimension', mate.name) or ''), 30, f'{mate.name} in the raid')
    return s.raids() - before


def check(label, ok, detail=''):
    print(f"{'PASS' if ok else 'FAIL'} {label}{' :: ' + detail if detail else ''}", flush=True)


def report(s, who, label):
    """PASS when `who` slid just now (/cv slide): 3 to 4.6 blocks, and stands on the ground after."""
    s.wait(1.2)
    out = s.rcon(f'cv slide {who}')
    last = re.search(r'last reason=(\S+) ticks=(\d+) distance=([\d.]+) .*?ago=(\d+)', out)
    y = re.search(r' y=(-?[\d.]+)', out)
    fresh = last is not None and int(last.group(4)) < 80
    check(f'{label}: slid', fresh, last.group(0) if last else out.replace('\n', ' / ')[:300])
    if fresh:
        d = float(last.group(3))
        check(f'{label}: 3 to 4.6 blocks', 3.0 <= d <= 4.6, f'{d} blocks, {last.group(2)} ticks, {last.group(1)}')
    check(f'{label}: on its feet on the ground after', y is not None and abs(float(y.group(1)) - GROUND) < 0.05 and 'vehicle=false' in out,
          y.group(0) if y else '-')


def sprint_and_tap(s):
    """W and left Ctrl down for a second (a sprint), then Shift tapped twice, the second held."""
    s.down('w')
    s.down('Control_L')
    s.wait(1.0)
    s.xdo('keydown', 'Shift_L', 'sleep', '0.08', 'keyup', 'Shift_L', 'sleep', '0.08', 'keydown', 'Shift_L')


def let_go(s):
    for key in ('Shift_L', 'Control_L', 'w'):
        s.up(key)


def to_start(s, who):
    s.rcon(f'execute as {who} at @s run tp @s {LANE_X} {GROUND} {START_Z} 180 0')
    s.clear_chat()


def run(s):
    gun = s.arg or 'k47'
    s.lobby()
    with s.puppet() as mate:
        mine = raid(s, mate)
        try:
            s.wait(6)  # the landing
            s.hold(gun)
            mate.hold(gun)
            s.rcon(f'effect give {s.name} minecraft:resistance 300 4 true')
            s.rcon(f'effect give {mate.name} minecraft:resistance 300 4 true')
            for kind in ('zombie', 'husk', 'drowned', 'bee'):
                s.rcon(f'execute at {s.name} run kill @e[type=minecraft:{kind},distance=..200]')
            # Keep the puppet out of the lane while ShotCam slides.
            s.rcon(f'execute as {mate.name} at @s run tp @s {LANE_X + 6} {GROUND} {START_Z} 180 0')
            s.clear_chat()

            # First person.
            to_start(s, s.name)
            s.wait(2)
            s.shot('slide-fp-stand')
            sprint_and_tap(s)
            s.wait(0.2)
            for i in range(3):
                s.shot(f'slide-fp-{i}', quick=True)
                s.wait(0.25)
            s.wait(0.3)
            let_go(s)
            report(s, s.name, 'first person')
            s.shot('slide-fp-after')

            # Third person from behind.
            s.wait(2)
            to_start(s, s.name)
            s.key('F5')
            s.wait(1.5)
            sprint_and_tap(s)
            s.wait(0.25)
            s.shot('slide-tp-0', quick=True)
            s.wait(0.3)
            s.shot('slide-tp-1', quick=True)
            s.wait(0.4)
            let_go(s)
            report(s, s.name, 'third person')
            s.key('F5', 'F5')

            # The puppet, seen from the side: ShotCam 4 blocks west of the lane where the slide runs.
            s.key('F1')
            try:
                to_start(s, mate.name)
                s.rcon(f'execute as {s.name} at @s run tp @s {LANE_X - 4} {GROUND} {START_Z - 8.5} -90 8')
                s.clear_chat()
                s.wait(2.5)
                mate.send('slide 1000')
                s.wait(0.85)
                for i in range(3):
                    s.shot(f'slide-others-{i}', quick=True)
                    s.wait(0.22)
                report(s, mate.name, 'puppet from the side')
                # Straight at ShotCam.
                s.wait(1.5)
                to_start(s, mate.name)
                s.rcon(f'execute as {s.name} at @s run tp @s {LANE_X} {GROUND} {START_Z - 13} 0 12')
                s.clear_chat()
                s.wait(2.5)
                mate.send('slide 1000')
                s.wait(1.2)
                s.shot('slide-others-front', quick=True)
                report(s, mate.name, 'puppet from the front')
            finally:
                s.key('F1')
            s.develop()
        finally:
            let_go(s)
            spot = s.always_extract(MAP)
            if spot:
                s.rcon(f'execute as {mate.name} at @s run tp @s {spot[0]} {spot[1]} {spot[2]}')
            s.extract(MAP, mine)
