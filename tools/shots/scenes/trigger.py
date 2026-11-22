"""The trigger with a real client on the private range: point-blank holds, Q, and a rack through the cells.

Needs `debug: true` in the server's plugins/Craftov/config.yml: shots are counted from the server
log. Logs PASS/FAIL lines (the bot in tools/bot/trigger.js checks the same with raw packets; this is
what a vanilla client really sends):

  - K47 on full auto, left mouse held 3.5 s facing a range target 1.3, 1.5, 1.87 and 2.5 blocks away
    (centre to centre; 1.5 puts the eye exactly on a block face): the magazine runs dry (a hold that
    falls back to a tap fires a round or two).
  - Q on a full magazine while looking at the floor (the client swings after it): no shot.
  - Right mouse at the range's weapon rack 2.5 blocks away, through the capture blocks: it opens.
  - The capture block does nothing to a body inside it: with real all-face sculk veins filled around
    and ahead of the player, a 0.5 s walk forward goes as far and a jump as high as without them
    (cobweb, used before, cut both to a fraction).

Shots: trigger-close (the view at 1.3 blocks with the capture blocks out, which must not show) and
trigger-rack (the rack menu opened with a gun in hand).
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
    return len(re.findall(rf'\[debug\] {re.escape(s.name)} shot ', text))


def _pos(s, selector):
    out = s.rcon(f'execute as {s.name} at @s run data get entity {selector} Pos')
    m = re.search(r'\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]', out)
    return tuple(float(v) for v in m.groups()) if m else None


def _tp(s, x, y, z, yaw, pitch):
    s.rcon(f'execute as {s.name} at @s run tp @s {x:.3f} {y:.3f} {z:.3f} {yaw} {pitch}')
    time.sleep(0.8)


def _check(label, ok, detail=''):
    print(f'[trigger] {"PASS" if ok else "FAIL"} {label}' + (f' :: {detail}' if detail else ''), flush=True)
    return ok


def _reload(s):
    s.key('q')
    time.sleep(6.5)


VEIN = 'minecraft:sculk_vein[down=true,east=true,north=true,south=true,up=true,west=true,waterlogged=false]'


def _move(s, start, key, seconds):
    """From `start` facing -z, holds `key`; returns (blocks moved across the ground, highest rise)."""
    _tp(s, start[0], start[1], start[2], 180, 0)
    time.sleep(0.5)
    a = _pos(s, '@s')
    s.down(key)
    top = a[1]
    end = time.time() + seconds
    while time.time() < end:
        p = _pos(s, '@s')
        top = max(top, p[1]) if p else top
    s.up(key)
    time.sleep(1.0)
    b = _pos(s, '@s')
    return ((b[0] - a[0]) ** 2 + (b[2] - a[2]) ** 2) ** 0.5, top - a[1]


def _body_in_veins(s, start):
    """Walk and jump without, then with, real capture blocks around and ahead of the body."""
    plain = _move(s, start, 'w', 0.5)[0], _move(s, start, 'space', 0.8)[1]
    x, y, z = int(start[0] // 1), int(start[1] // 1), int(start[2] // 1)
    box = f'{x - 1} {y} {z + 1} {x + 1} {y + 2} {z - 3}'
    s.rcon(f'execute as {s.name} at @s run fill {box} {VEIN} replace minecraft:air')
    try:
        time.sleep(0.5)
        veins = _move(s, start, 'w', 0.5)[0], _move(s, start, 'space', 0.8)[1]
    finally:
        s.rcon(f'execute as {s.name} at @s run fill {box} minecraft:air replace minecraft:sculk_vein')
    return plain, veins


def run(s):
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
    rack = _pos(s, '@e[type=interaction,sort=nearest,limit=1]')
    target = _pos(s, '@e[type=zombie,sort=nearest,limit=1]')
    if not (me and rack and target):
        raise RuntimeError(f'range not as expected: me {me}, rack {rack}, target {target}')
    s.give('k47')
    time.sleep(0.8)
    s.hold('k47')
    time.sleep(0.8)
    _reload(s)
    results = []
    for d in (1.3, 1.5, 1.87, 2.5):
        _tp(s, target[0], target[1], target[2] + d, 180, 0)
        if d == 1.3:
            s.clear_chat()
            s.shot('trigger-close')
        since = _size(s)
        with s.mouse(1):
            time.sleep(3.5)
        time.sleep(0.5)
        fired = _shots(s, since)
        results.append(_check(f'K47 held {d} blocks from the target empties the magazine', fired == 30, f'{fired} shots'))
        _reload(s)
    plain, veins = _body_in_veins(s, me)
    results.append(_check('walking through capture blocks goes as far as without', veins[0] >= 0.9 * plain[0],
                          f'{veins[0]:.2f} vs {plain[0]:.2f} blocks in 0.5 s'))
    results.append(_check('jumping inside capture blocks goes as high as without', veins[1] >= 0.9 * plain[1],
                          f'{veins[1]:.2f} vs {plain[1]:.2f} blocks'))
    _tp(s, me[0], me[1], me[2], 180, 50)
    since = _size(s)
    s.key('q')  # full magazine: no reload, but the client swings
    time.sleep(1.5)
    fired = _shots(s, since)
    results.append(_check('Q on a full magazine looking at the floor fires nothing', fired == 0, f'{fired} shots'))
    _tp(s, rack[0], rack[1], rack[2] - 2.5, 0, 10)
    with s.mouse(3):
        time.sleep(0.15)
    time.sleep(1.0)
    s.shot('trigger-rack')
    s.key('Escape')
    print(f'[trigger] {sum(results)}/{len(results)} counted checks passed; look at trigger-rack for the opened rack', flush=True)
    time.sleep(0.5)
    s.lobby()
