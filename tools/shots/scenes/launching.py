"""A match found while the map is still being copied (issue #45): what the player sees in the lobby. No arg.

The proving ground's template gets PAD (400 000) small files in a pad/ folder first, so its copy
takes several seconds, as a big map's might on a slow disk; they are removed afterwards.
launch-title: about a second after "match found", the title and the action bar line
"匹配成功 正在部署 试验场 · 地图准备中 00:00". launch-wait: once the title has faded, the action bar
line alone, its wait counted up. The scene fails if the copy was over before that shot. The player
lands in the raid once the copy is done and leaves it through the always-open extract.

The proving ground is a test map: the server needs test.enabled (shots.py setup turns it on) or an
op player (the harness ops its player). match.wait-seconds is assumed short (3 to 5).
"""

import shutil
import time

PAD = 400_000


def copied(s, mark):
    """Whether the server log has a raid template copy written after byte `mark`."""
    with open(s.cfg.server_dir / 'logs' / 'latest.log', 'rb') as log:
        log.seek(mark)
        return b'Copied template' in log.read()


def run(s):
    pad = s.cfg.server_dir / 'craftov_tpl_proving' / 'pad'
    pad.mkdir(parents=True, exist_ok=True)
    data = bytes(512)
    for i in range(PAD):
        (pad / f'p{i}.bin').write_bytes(data)
    try:
        s.lobby()
        s.clear_chat()
        before = s.raids()
        mark = (s.cfg.server_dir / 'logs' / 'latest.log').stat().st_size
        s.cmd('cv queue proving NORMAL SOLO')
        # Matched within the queue's wait (a few seconds); the title stays for 4.5 s.
        s.wait_until(lambda: '正在复制地图 1 份' in s.rcon('cv raids'), 20, 'the match and its map copy')
        matched = time.time()
        s.wait(1.2)
        s.shot('launch-title')
        s.wait(max(0.0, matched + 6.5 - time.time()))
        early = copied(s, mark)
        s.shot('launch-wait')
        # Out again before anything can fail, so the next run does not reconnect into this raid.
        s.wait_until(s.in_raid, 120, 'the proving ground raid to start')
        mine = s.raids() - before
        s.wait(4)
        s.extract('proving', mine)
        if early:
            raise RuntimeError('the copy was done before launch-wait: raise PAD')
    finally:
        shutil.rmtree(pad, ignore_errors=True)
