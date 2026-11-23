"""Run-and-gun (issue #105) with a real client on the private range: the crosshair standing, strafing and
firing on a sprint, and right mouse ending a sprint. Arg: the gun (default k47).

Needs `debug: true` in the server's plugins/Craftov/config.yml: shots are read from the server log.
Logs PASS/FAIL lines for what the vanilla client really does (tools/bot/accuracy.js checks the same
with raw packets):

  - Standing, full auto: no round counts as airborne. The vanilla client answers each recoil kick (a
    camera turn from the server) with a rotation packet saying it is off the ground; the jump cone
    goes by the server's own check of what is under the feet.
  - Left mouse held while sprinting (W and left Ctrl) along the firing line fires full auto: the
    client keeps digging the capture cells as they move with it and keeps sprinting, so every shot
    in the server log has sprint=true, sprint pace (over 0.25 blocks a tick) and ground=true.
  - Right mouse on a sprint ends it (food level 6, which the vanilla client cannot sprint at) and the
    sights come up; let go, the food level is back at 20.

Shots: rag-stand (arms 12 GUI pixels out with a K47), rag-walk (strafing: wider), rag-stand-fire (a
full-auto burst standing), rag-walk-fire (walking forward and firing), rag-sprint (sprinting, not firing:
the arms at their widest), rag-sprint-fire-a/b/c (firing on the sprint, 0.1 s apart), rag-sprint-ads
(right mouse on the sprint: sights up, no crosshair arms).
"""
import re
import time


def _log(s):
    return s.cfg.server_dir / 'logs' / 'latest.log'


def _size(s):
    return _log(s).stat().st_size


def _shots(s, since):
    with open(_log(s), 'rb') as f:
        f.seek(since)
        text = f.read().decode('utf-8', 'replace')
    out = []
    for line in text.splitlines():
        if f'[debug] {s.name} shot ' not in line:
            continue
        fields = dict(re.findall(r' (\w+)=(\S+)', line))
        out.append(fields)
    return out


def _pos(s, selector):
    out = s.rcon(f'execute as {s.name} at @s run data get entity {selector} Pos')
    m = re.search(r'\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]', out)
    return tuple(float(v) for v in m.groups()) if m else None


def _food(s):
    m = re.search(r'(-?\d+)$', s.rcon(f'data get entity {s.name} foodLevel').strip())
    return int(m.group(1)) if m else None


def _tp(s, x, y, z, yaw, pitch):
    s.rcon(f'execute as {s.name} at @s run tp @s {x:.3f} {y:.3f} {z:.3f} {yaw} {pitch}')
    time.sleep(0.8)


def _check(label, ok, detail=''):
    print(f'[runandgun] {"PASS" if ok else "FAIL"} {label}' + (f' :: {detail}' if detail else ''), flush=True)
    return ok


def _sprint_down(s):
    s.down('Control_L')
    s.down('w')


def _sprint_up(s):
    s.up('w')
    s.up('Control_L')


def run(s):
    gun = s.arg or 'k47'
    s.lobby()
    # The range's entrance is the weapon-rack station by the lobby spawn (as tools/bot/trigger.js finds it).
    s.rcon(f'execute as {s.name} at @s run tp @s 9.5 65 -6 0 0')
    time.sleep(1.5)
    station = _pos(s, '@e[type=interaction,sort=nearest,limit=1]')
    if station is None:
        raise RuntimeError('no range station near the lobby spawn')
    _tp(s, station[0], station[1], station[2] - 2.5, 0, 10)
    with s.mouse(3):
        time.sleep(0.2)
    s.wait_until(lambda: 'craftov_range' in s.dimension(), 20, 'the range')
    time.sleep(2)
    me = _pos(s, '@s')
    if not me:
        raise RuntimeError('no position on the range')
    s.give(gun)
    time.sleep(0.8)
    s.hold(gun)
    time.sleep(0.8)
    s.key('q')
    time.sleep(6.5)
    results = []

    # Standing and strafing, facing down the range.
    _tp(s, me[0], me[1], me[2], 180, 0)
    s.clear_chat()
    time.sleep(1.0)
    s.shot('rag-stand')
    s.down('a')
    time.sleep(0.6)
    s.shot('rag-walk', quick=True)
    s.up('a')
    time.sleep(0.5)
    since = _size(s)
    with s.mouse(1):
        time.sleep(0.35)
        s.shot('rag-stand-fire', quick=True)
        time.sleep(0.3)
    time.sleep(0.5)
    standing = _shots(s, since)
    aloft = [f for f in standing if f.get('ground') != 'true']
    results.append(_check('standing full auto: no round counts as airborne (the client answers each recoil kick saying it is off the ground)',
                          standing and not aloft, f'{len(aloft)} of {len(standing)} rounds airborne; spreads '
                          + ' '.join(f.get('spread', '?') for f in standing)))

    # The east end of the firing line, facing west along it: 15 blocks of flat floor (toward the
    # targets a sprint runs up the slab at the edge of the range).
    runway = (me[0] + 6, me[1], me[2] - 1, 90, 0)
    _tp(s, *runway)
    s.clear_chat()
    time.sleep(0.5)
    _sprint_down(s)
    try:
        time.sleep(0.6)
        s.shot('rag-sprint', quick=True)
    finally:
        _sprint_up(s)

    # Walking forward (no sprint) and firing, for comparison.
    _tp(s, *runway)
    s.clear_chat()
    time.sleep(0.5)
    s.down('w')
    try:
        time.sleep(0.5)
        with s.mouse(1):
            time.sleep(0.4)
            s.shot('rag-walk-fire', quick=True)
    finally:
        s.up('w')
    time.sleep(0.5)

    _tp(s, *runway)
    s.clear_chat()
    time.sleep(0.5)
    since = _size(s)
    _sprint_down(s)
    try:
        time.sleep(0.5)
        with s.mouse(1):
            time.sleep(0.35)
            for suffix in 'abc':
                s.shot(f'rag-sprint-fire-{suffix}', quick=True)
                time.sleep(0.1)
            time.sleep(0.3)
        time.sleep(0.3)  # let go of the trigger first: the last round is still on the sprint
    finally:
        _sprint_up(s)
    time.sleep(0.6)
    shots = _shots(s, since)
    sprinting = [f for f in shots if f.get('sprint') == 'true']
    fast = [f for f in shots if float(f.get('speed', 0)) > 0.25]
    spreads = ' '.join(f.get('spread', '?') for f in shots)
    results.append(_check(f'{gun}: left mouse held on a sprint fires full auto (8+ rounds in about 1 s)', len(shots) >= 8, f'{len(shots)} shots'))
    results.append(_check('and the client keeps sprinting through it', shots and len(sprinting) == len(shots) and len(fast) >= len(shots) - 1,
                          f'sprint=true on {len(sprinting)}/{len(shots)}, sprint pace on {len(fast)}; spreads {spreads}'))
    results.append(_check('on the ground all the way (the sprint cone, never the jump cone)', shots and all(f.get('ground') == 'true' for f in shots),
                          f'{sum(1 for f in shots if f.get("ground") != "true")} rounds airborne'))

    # Right mouse on a sprint: the sprint ends, the sights come up.
    _tp(s, *runway)
    s.clear_chat()
    time.sleep(0.5)
    _sprint_down(s)
    try:
        time.sleep(0.6)
        with s.mouse(3):
            time.sleep(0.15)
            food = _food(s)
            time.sleep(0.6)
            moved_from, t_from = _pos(s, '@s'), time.time()
            time.sleep(0.5)
            moved_to, t_to = _pos(s, '@s'), time.time()
            s.shot('rag-sprint-ads', quick=True)
    finally:
        _sprint_up(s)
    time.sleep(0.8)
    after = _food(s)
    ticks = (t_to - t_from) * 20
    pace = ((moved_to[0] - moved_from[0]) ** 2 + (moved_to[2] - moved_from[2]) ** 2) ** 0.5 / ticks if moved_from and moved_to else -1
    results.append(_check('right mouse on a sprint holds the food level at 6', food == 6, f'food {food}'))
    results.append(_check('so the client stops sprinting while the sprint key is still held (under 0.24 blocks a tick: walking pace)',
                          0 <= pace < 0.24, f'{pace:.3f} blocks a tick'))
    results.append(_check('and sprints again once right mouse is let go (food back at 20)', after == 20, f'food {after}'))
    print(f'[runandgun] {sum(1 for r in results if r)}/{len(results)} checks passed; look at rag-stand, rag-walk, rag-sprint, '
          'rag-sprint-fire-a..c and rag-sprint-ads', flush=True)
    time.sleep(0.5)
    s.lobby()
