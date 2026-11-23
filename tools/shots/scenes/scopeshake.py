"""Scope shake (issue #118): how far the reticle moves off its target while a gun fires aimed, on the
real client, measured frame by frame (tools/shots/recoilgrab.py).

On the private range, facing a wall of mixed blocks 9.5 m down the lane (the same wall every run:
the frames are matched against it) with a gold block where the crosshair rests: for each sight the
gun is aimed, and once the sights are up and still, fired without touching the mouse while frames
are grabbed. The M4C sprays 10 rounds on full auto (red dot, 4x and 8x); the AWR (built-in 8x) fires
10 rounds a click apart as fast as its bolt cycles, with the reload after the fifth. For every frame
the tool finds where the reticle points on the view before the first round.

scopeshake-<gun>: frames of the run, each with its time and how far the reticle is off the target;
scopeshake-summary: every sight's path of the target around the reticle and its height over time
(with SCOPESHAKE_BEFORE, an earlier run's out/scopeshake/summary.json, grey under this one).

Arg: sights separated by commas, an optic after a plus (default m4c+optic_reddot,m4c+optic_4x,
m4c+optic_8x,awr), or `peek` for still shots of each sight aimed at the wall (no firing). In the
environment: SCOPESHAKE_TAG heads the chart (改动后, say), SCOPESHAKE_FRAMES=1 keeps every frame.
"""

import json
import os
import pathlib
import random
import subprocess

_optics = {'__file__': str(pathlib.Path(__file__).with_name('optics.py'))}
exec(compile(pathlib.Path(_optics['__file__']).read_text(encoding='utf-8'), _optics['__file__'], 'exec'), _optics)
arm, enter_range = _optics['arm'], _optics['enter_range']

HERE = pathlib.Path(__file__).resolve().parent.parent
OUT = HERE / 'out'
DEFAULT = 'm4c+optic_reddot,m4c+optic_4x,m4c+optic_8x,awr'
NAMES = {'m4c': 'M4C', 'awr': 'AWR', 'k47': 'K47', 'dm14': 'DM14', 'scr': 'SCR', 'optic_reddot': '红点', 'optic_holo': '全息',
         'optic_3x': '3 倍', 'optic_4x': '4 倍', 'optic_8x': '8 倍'}
BUILT_IN = {'dm14': ('自带 4 倍', 4), 'awr': ('自带 8 倍', 8)}
POWER = {'optic_reddot': 1.5, 'optic_holo': 1.5, 'optic_3x': 3, 'optic_4x': 4, 'optic_8x': 8}
# Blocks with plenty of texture at any zoom, none of them red (the red dot is found by its colour).
WALL = ['stone_bricks', 'mossy_stone_bricks', 'chiseled_stone_bricks', 'cracked_stone_bricks', 'bookshelf', 'oak_log',
        'birch_log', 'spruce_planks', 'coal_ore', 'iron_ore', 'diamond_ore', 'emerald_ore', 'lapis_ore', 'gold_ore',
        'cobblestone', 'mossy_cobblestone', 'andesite', 'polished_andesite', 'deepslate_tiles', 'chiseled_deepslate',
        'tuff_bricks', 'oak_planks', 'hay_block', 'dried_kelp_block', 'blue_ice', 'prismarine_bricks', 'end_stone_bricks',
        'purpur_block', 'sea_lantern', 'crafting_table', 'jungle_log', 'note_block', 'mud_bricks', 'packed_mud',
        'cyan_terracotta', 'light_blue_glazed_terracotta', 'yellow_glazed_terracotta', 'lime_glazed_terracotta']
WALL_Z = -15          # the wall's blocks, 9.5 m from the firing position (z -5.5)
WALL_X = (7, 22)      # across the hangar (plot x 6..23)
WALL_Y = (64, 71)     # floor to ceiling
TARGET = (14, 66)     # the gold block where the crosshair rests
YAW, PITCH = 180, 0.75


def label(gun, optic):
    return NAMES.get(gun, gun) + ' ' + (NAMES.get(optic, optic) if optic else BUILT_IN.get(gun, ('机械瞄具',))[0])


def power(gun, optic):
    return POWER.get(optic) or BUILT_IN.get(gun, (None, 1.25))[1]


def plot_x(s):
    """The x offset of the player's range plot (the range puts the player at plot x + 14.5)."""
    pos = s.data('Pos')
    x = float(pos.strip('[]').split(',')[0].rstrip('d'))
    return round(x - 14.5)


def build_wall(s):
    """The same mosaic every run, the dummies out of the way."""
    dx = plot_x(s)
    # The dummies (the range keeps them from dying) go down out of sight, name tags and all.
    s.rcon(f'execute at {s.name} as @e[type=minecraft:zombie,distance=..40] at @s run tp @s ~ ~-80 ~')
    pick = random.Random(118)
    for y in range(WALL_Y[0], WALL_Y[1] + 1):
        for x in range(WALL_X[0], WALL_X[1] + 1):
            block = 'gold_block' if (x, y) == TARGET else pick.choice(WALL)
            s.rcon(f'execute at {s.name} run setblock {dx + x} {y} {WALL_Z} minecraft:{block}')
    return dx


def face_wall(s):
    s.look(YAW, PITCH)
    s.wait(0.5)
    s.clear_chat()


def rounds_spec(gun):
    """recoilgrab.py's firing arguments: a 10-round spray, or 10 clicks as fast as the gun cycles."""
    if gun == 'awr':
        # 42 rpm: a round every 1.45 s; the sixth click starts the reload (an empty magazine: 4.8 s).
        clicks = [f'{1.55 * k:.2f}' for k in range(5)] + ['7.75r'] + [f'{13.0 + 1.55 * k:.2f}' for k in range(5)]
        return ['--clicks', ','.join(clicks), '--tail', '2.0']
    # The M4C at 800 rpm: a round every 75 ms, the first on the press; the server takes the release
    # 100 to 200 ms after the client lets go (the trigger's capture cells), so this fires about 10.
    return ['--auto', '600', '--tail', '2.0']


def run(s):
    peek = s.arg == 'peek'
    specs = [(spec.partition('+')[0], spec.partition('+')[2] or None)
             for spec in (DEFAULT if peek or not s.arg else s.arg).split(',') if spec]
    enter_range(s)
    s.cmd('cv fov 70 100')
    s.wait(0.5)
    build_wall(s)
    log_path = s.cfg.server_dir / 'logs' / 'latest.log'
    results = []
    for gun, optic in specs:
        arm(s, gun, optic)
        face_wall(s)
        key = gun + ('-' + optic.replace('optic_', '') if optic else '')
        if peek:
            s.shot(f'scopeshake-peek-{key}-hip')
            with s.mouse(3):
                s.wait(1.6)
                s.shot(f'scopeshake-peek-{key}')
            s.wait(0.8)
            continue
        where = OUT / 'scopeshake' / key
        best = None
        # A spray is held for a time, and the server takes the release a tick sooner or later: it is
        # fired again until it was 10 rounds, so every run compares the same spray.
        for attempt in range(4):
            with s.mouse(3):
                s.wait(1.8)  # the sights up and the gun settled
                done = subprocess.run(['/usr/bin/python3', str(HERE / 'recoilgrab.py'), '--display', s.display,
                                       '--out', str(where), '--label', label(gun, optic),
                                       '--sight', 'scope' if power(gun, optic) >= 2.5 else 'dot',
                                       '--power', str(power(gun, optic)), '--server-log', str(log_path)]
                                      + rounds_spec(gun) + (['--frames'] if os.environ.get('SCOPESHAKE_FRAMES') else []),
                                      capture_output=True, text=True)
            if done.returncode != 0:
                raise RuntimeError('recoilgrab failed: ' + done.stderr[-1500:])
            result = json.loads(done.stdout.strip().splitlines()[-1])
            if best is None or abs((result['rounds'] or 0) - 10) < abs((best[0]['rounds'] or 0) - 10):
                best = (result, (where / 'sheet.png').read_bytes())
            if result['rounds'] == 10:
                break
            s.wait(1.5)  # the view back where it started
        result, picture = best
        (where / 'sheet.png').write_bytes(picture)
        result.update(key=key, label=label(gun, optic))
        results.append(result)
        print('scopeshake', json.dumps({k: v for k, v in result.items() if k != 'timeline'}, ensure_ascii=False), flush=True)
        name = f'scopeshake-{key}'
        (OUT / f'{name}.png').write_bytes((where / 'sheet.png').read_bytes())
        s.shots.append(name)
        s.wait(1.0)
    if not peek:
        summary = OUT / 'scopeshake' / 'summary.json'
        summary.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding='utf-8')
        done = subprocess.run(['/usr/bin/python3', str(HERE / 'recoilgrab.py'), '--chart', str(summary),
                               '--out', str(OUT / 'scopeshake-summary.png'), '--label', os.environ.get('SCOPESHAKE_TAG', '')]
                              + (['--before', os.environ['SCOPESHAKE_BEFORE']] if os.environ.get('SCOPESHAKE_BEFORE') else []),
                              capture_output=True, text=True)
        if done.returncode != 0:
            raise RuntimeError('chart failed: ' + done.stderr[-1500:])
        s.shots.append('scopeshake-summary')
    s.rcon(f'clear {s.name}')
    s.lobby()
