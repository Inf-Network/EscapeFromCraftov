"""Every gun report on the real client: each gun fired on the private range, then every report the
server can send played to the player. Passes when the client logs nothing about a craftov sound it
cannot play (`Unable to play unknown soundEvent`) or could not load from the pack.

Needs `debug: true` in the server's plugins/Craftov/config.yml (shots are counted from the server
log). A key the pack does not have is played first and must be reported, so the check cannot pass
on a client that stopped logging. The event names are built the way GunAudio.java builds them: gun.<id>.<band> for fp, fp_s,
near, near_s, mid, mid_s, far and muff, the same under .x<n> for the multi-round samples, and
gun.tail.<family>.in / .in2 with their muffled .in_muff / .in_muff2. Logs PASS/FAIL lines; shot: gunaudio-range (the range after the last gun).
"""
import re
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent.parent
CLIENT_LOG = HERE.parent / '.cache' / 'game' / 'logs' / 'latest.log'
BANDS = ('fp', 'fp_s', 'near', 'near_s', 'mid', 'mid_s', 'far', 'muff')


def _check(label, ok, detail=''):
    print(f'[gunaudio] {"PASS" if ok else "FAIL"} {label}' + (f' :: {detail}' if detail else ''), flush=True)
    return ok


def _guns():
    text = (ROOT / 'src' / 'main' / 'resources' / 'items.yml').read_text(encoding='utf-8')
    out = []
    current = None
    for line in text.splitlines():
        head = re.match(r'^  ([a-z0-9_]+):', line)
        if head:
            current = head.group(1)
        if current and re.search(r'\bgun: \{', line):
            out.append(current)
    return out


def _events(guns):
    groups = {gid: int(n) for gid, n in re.findall(r'"(\w+)", new Rounds\((\d+), \d+\)', (
        ROOT / 'src' / 'main' / 'java' / 'net' / 'infnetwork' / 'snowball' / 'craftov' / 'weapon' / 'GunAudio.java'
    ).read_text(encoding='utf-8'))}
    keys = []
    for gid in guns:
        keys += [f'craftov:gun.{gid}.{band}' for band in BANDS]
        if gid in groups:
            keys += [f'craftov:gun.{gid}.x{groups[gid]}.{band}' for band in BANDS]
    for family in ('light', 'rifle', 'heavy', 'sniper'):
        keys += [f'craftov:gun.tail.{family}.{room}' for room in ('in', 'in2', 'in_muff', 'in_muff2')]
    return keys


def _client_log_from(offset):
    with open(CLIENT_LOG, 'rb') as f:
        f.seek(offset)
        return f.read().decode('utf-8', 'replace')


def _server_shots(s, since):
    with open(s.cfg.server_dir / 'logs' / 'latest.log', 'rb') as f:
        f.seek(since)
        text = f.read().decode('utf-8', 'replace')
    return len(re.findall(rf'\[debug\] {re.escape(s.name)} shot ', text))


def run(s):
    results = []
    # The pack loaded without missing files or events for any gun report.
    loaded = CLIENT_LOG.read_text(encoding='utf-8', errors='replace')
    broken = sorted(set(re.findall(r'(?:does not exist|Missing sound|Unable to)[^\n]*craftov:gun[^\n]*', loaded)))
    results.append(_check('the pack loads every gun sound', not broken, '; '.join(broken[:5])))

    s.lobby()
    # The range's entrance is the weapon-rack station by the lobby spawn (as tools/bot/trigger.js finds it).
    s.rcon(f'execute as {s.name} at @s run tp @s 9.5 65 -6 0 0')
    time.sleep(1.5)
    out = s.rcon(f'execute as {s.name} at @s run data get entity @e[type=interaction,sort=nearest,limit=1] Pos')
    station = re.search(r'\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]', out)
    if not station:
        raise RuntimeError('no range station near the lobby spawn')
    x, y, z = (float(v) for v in station.groups())
    s.rcon(f'execute as {s.name} at @s run tp @s {x:.3f} {y:.3f} {z - 2.5:.3f} 0 10')
    time.sleep(0.8)
    with s.mouse(3):
        time.sleep(0.2)
    s.wait_until(lambda: 'craftov_range' in s.dimension(), 20, 'the range')
    time.sleep(2)
    s.look(180, 20)

    guns = _guns()
    log_from = CLIENT_LOG.stat().st_size
    server_log = s.cfg.server_dir / 'logs' / 'latest.log'
    for gid in guns:
        s.rcon(f'clear {s.name}')
        time.sleep(0.3)
        s.select(8)
        s.give(gid)
        time.sleep(0.6)
        s.hold(gid)
        time.sleep(1.2)  # the draw
        since = server_log.stat().st_size
        with s.mouse(1):
            time.sleep(0.25)
        time.sleep(1.6 if gid in ('awr', 'r12') else 0.6)
        fired = _server_shots(s, since)
        results.append(_check(f'{gid} fires on the real client', fired >= 1, f'{fired} shots'))
    s.clear_chat()
    s.shot('gunaudio-range')

    keys = _events(guns)
    # A key that does not exist first, so a client that stopped logging unknown events fails the check.
    control = 'craftov:gun.no_such_gun.near'
    for key in [control] + keys:
        s.rcon(f'execute as {s.name} at @s run playsound {key} player @s ~ ~ ~ 0.05')
        time.sleep(0.05)
    time.sleep(2)
    text = _client_log_from(log_from)
    unknown = sorted(set(re.findall(r'Unable to play unknown soundEvent: (\S+)', text)))
    results.append(_check('the client reports an event the pack does not have', control in unknown, ', '.join(unknown[:10])))
    unknown = [key for key in unknown if key != control]
    results.append(_check(f'the client knows all {len(keys)} gun report events and every report fired', not unknown,
                          ', '.join(unknown[:10])))
    s.rcon(f'clear {s.name}')
    s.lobby()
    if not all(results):
        raise RuntimeError('gun audio checks failed')
