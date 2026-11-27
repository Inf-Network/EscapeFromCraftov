"""The raid entry camera (#166), recorded frame by frame (tools/shots/flightgrab.py). Arg: a tag for
the output, then optionally `@map` (default rustport) and `+mate`, e.g. `insertion:before`,
`insertion:after@proving` or `insertion:squad+mate`.

Queues the map solo (with +mate: a party with a puppet squadmate, ShotMate, as a trio) and records
from the black curtain over the jump into the raid world until well after landing, then leaves
through the always-open extract (or ends the raid, alone in it). The squadmate flies in on the same
camera; its spectator head is wherever the server puts it, so a squadmate showing up in the view
shows here. It leaves the raid by disconnecting.

Shots: insertion-<tag>-landed (just after landing). In tools/shots/out/insertion/:
insertion-<tag>-motion.png (how much the picture changed on every frame the client drew),
insertion-<tag>-strip.png (12 frames in a row from the middle of the flight), insertion-<tag>.gif,
insertion-<tag>.json and the frames in insertion-<tag>/. Prints the summary: drawn frames, how many
moved, holds (the view standing still) and jumps. Two runs side by side:
`python3 tools/shots/flightgrab.py --compare A.json B.json --out PREFIX`.
"""
import contextlib
import json
import subprocess
import time
from pathlib import Path

SHOTS = Path(__file__).resolve().parent.parent
OUT = SHOTS / 'out' / 'insertion'
FLIGHT = 11  # seconds recorded after the jump: the curtain, the flight (about 6.5 s) and the landing


def fly(s, map_id, label, mode):
    before = s.raids()
    grab = subprocess.Popen(['/usr/bin/python3', '-I', str(SHOTS / 'flightgrab.py'), '--display', s.display,
                             '--out', str(OUT), '--label', label],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    if grab.stdout.readline().strip() != 'ready':
        raise RuntimeError('flightgrab did not start')
    try:
        s.cmd(f'cv queue {map_id} NORMAL {mode}')
        # In the raid world as a spectator: on the camera (in_raid waits for the landing).
        s.wait_until(lambda: 'craftov_raid' in s.dimension(), 150, f'the {map_id} raid to start')
        grab.stdin.write(f'mark {time.time():.3f}\n')
        grab.stdin.flush()
        s.wait(FLIGHT)
    finally:
        grab.stdin.close()
        results = [json.loads(line) for line in grab.stdout if line.startswith('{')]
        grab.wait()
    for result in results:
        print(json.dumps(result), flush=True)
    s.shot(f'{label}-landed')
    return s.raids() - before


def run(s):
    spec = s.arg or 'now'
    mate = spec.endswith('+mate')
    tag, _, map_id = spec.removesuffix('+mate').partition('@')
    map_id = map_id or 'rustport'
    label = f'insertion-{tag}'
    s.lobby()
    s.clear_chat()
    if not mate:
        s.extract(map_id, fly(s, map_id, label, 'SOLO'))
        return
    with s.puppet() as puppet:
        s.cmd(f'cv party invite {puppet.name}')
        s.wait(1)
        puppet.send('chat /cv party accept')
        s.wait(1)
        mine = fly(s, map_id, label, 'TRIO')
    # The squadmate has left (its stand-in stays): alone in the raid now.
    with contextlib.suppress(Exception):
        s.cmd('cv party leave')
    s.extract(map_id, mine)
