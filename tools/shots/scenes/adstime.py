"""How long right mouse takes to zoom the real client's view, gun by gun (issue #117), on the private
range facing the 10 m dummy. For each gun, right mouse is pressed three times (ADSTIME_RUNS) from the hip and the
frames are timed from the press (tools/shots/framegrab.py) until the view stops changing.
adstime-<gun>: the frames of the middle run, each with its time after the press and zoom;
adstime-summary: every gun's time to a settled view, quickest first. Each gun's line in the output
also has the middle time from the first visible change to settled (rise_ms: the press's own lag,
the same for every gun, left out) and the runs in which the gun dropped back to the hip before the
sights came up (drops, the ms of each; framegrab.py, iron and 1x sights only).

Arg: guns separated by commas, an optic after a plus (default: every gun, and the M4C with the red
dot, the 4x and the 8x), e.g. adstime:sv9,m4c+optic_8x,awr. In the environment: ADSTIME_FRAMES=1 also
keeps every frame (out/adstime/<gun>-<run>/<ms>.png), ADSTIME_TAG heads the chart (改动后, say), and
ADSTIME_BEFORE names an earlier run's out/adstime/summary.json (copy it somewhere first) to chart
beside this one.
"""

import json
import os
import pathlib
import subprocess

_optics = {'__file__': str(pathlib.Path(__file__).with_name('optics.py'))}
exec(compile(pathlib.Path(_optics['__file__']).read_text(encoding='utf-8'), _optics['__file__'], 'exec'), _optics)
arm, enter_range, face_dummy = _optics['arm'], _optics['enter_range'], _optics['face_dummy']

HERE = pathlib.Path(__file__).resolve().parent.parent
OUT = HERE / 'out'
DEFAULT = 'p18,d50,sv9,v57,m4c,k47,r12,scr,kl7,dm14,awr,m4c+optic_reddot,m4c+optic_4x,m4c+optic_8x'
NAMES = {'p18': 'P18', 'd50': 'D50', 'sv9': 'SV9', 'v57': 'V57', 'm4c': 'M4C', 'k47': 'K47', 'r12': 'R12', 'scr': 'SCR',
         'kl7': 'KL7', 'dm14': 'DM14', 'awr': 'AWR', 'optic_reddot': '红点', 'optic_holo': '全息', 'optic_3x': '3 倍',
         'optic_4x': '4 倍', 'optic_8x': '8 倍'}
RUNS = int(os.environ.get('ADSTIME_RUNS', 3))


def label(gun, optic):
    built_in = {'dm14': '自带 4 倍', 'awr': '自带 8 倍'}
    return NAMES.get(gun, gun) + ' ' + (NAMES.get(optic, optic) if optic else built_in.get(gun, '机械瞄具'))


def run(s):
    specs = [(spec.partition('+')[0], spec.partition('+')[2] or None) for spec in (s.arg or DEFAULT).split(',') if spec]
    enter_range(s)
    s.cmd('cv fov 70 100')
    s.wait(0.5)
    results = []
    for gun, optic in specs:
        arm(s, gun, optic)
        face_dummy(s)
        s.wait(0.8)
        key = gun + ('-' + optic.replace('optic_', '') if optic else '')
        runs = []
        for k in range(RUNS):
            where = OUT / 'adstime' / f'{key}-{k}'
            done = subprocess.run(['/usr/bin/python3', str(HERE / 'framegrab.py'), '--display', s.display, '--out', str(where),
                                   '--label', label(gun, optic)] + (['--frames'] if os.environ.get('ADSTIME_FRAMES') else []),
                                  capture_output=True, text=True)
            if done.returncode != 0:
                raise RuntimeError('framegrab failed: ' + done.stderr[-800:])
            runs.append((json.loads(done.stdout.strip().splitlines()[-1]), where))
            s.wait(0.9)  # the server lowers the sights, past the resume window
        timed = sorted((r for r in runs if r[0]['settled_ms'] is not None), key=lambda r: r[0]['settled_ms'])
        if not timed:
            raise RuntimeError(f'{key}: the view never settled')
        middle, where = timed[len(timed) // 2]
        name = f'adstime-{key}'
        (OUT / f'{name}.png').write_bytes((where / 'sheet.png').read_bytes())
        s.shots.append(name)
        rises = sorted(r[0]['settled_ms'] - r[0]['first_change_ms'] for r in timed if r[0]['first_change_ms'] is not None)
        results.append({'key': key, 'label': label(gun, optic), 'settled': [r[0]['settled_ms'] for r in timed],
                        'zoom': middle['zoom'], 'first': middle['first_change_ms'], 'half': middle['half_ms'],
                        'settled_ms': middle['settled_ms'], 'rise_ms': rises[len(rises) // 2] if rises else None,
                        'drops': [r[0]['drop_ms'] for r in runs if r[0].get('drop_ms') is not None]
                        if middle.get('drop_checked') else None,
                        'frame_ms': middle['frame_ms']})
        print('adstime', json.dumps(results[-1], ensure_ascii=False), flush=True)
    (OUT / 'adstime' / 'summary.json').write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding='utf-8')
    done = subprocess.run(['/usr/bin/python3', str(HERE / 'framegrab.py'), '--summary', str(OUT / 'adstime' / 'summary.json'),
                           '--out', str(OUT / 'adstime-summary.png'), '--label', os.environ.get('ADSTIME_TAG', '')]
                          + (['--before', os.environ['ADSTIME_BEFORE']] if os.environ.get('ADSTIME_BEFORE') else []),
                          capture_output=True, text=True)
    if done.returncode != 0:
        raise RuntimeError('summary failed: ' + done.stderr[-800:])
    s.shots.append('adstime-summary')
    s.rcon(f'clear {s.name}')
    s.lobby()
