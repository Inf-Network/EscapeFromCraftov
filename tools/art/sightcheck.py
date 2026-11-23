"""Checks the aimed sight picture offline with fpview's copy of the client's first-person maths.

    python3 tools/art/sightcheck.py                 # every gun and sight: numbers, exit 1 on a miss
    python3 tools/art/sightcheck.py --png out_dir   # also renders each aimed view

1x sights (red dot, holo): the reticle's centre is within 1 pixel of the screen centre and the
sight's window is 18-25% of the screen width, at the 2000x1168 window fpview draws at (1000x584).
"""
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
import fpview  # noqa: E402
import models  # noqa: E402

ROOT = fpview.ROOT
fpview.CROSSHAIR = False
WINDOW = (0.18, 0.25)


def posed(path):
    model = fpview.load_model(os.path.join(ROOT, 'models', 'item', path + '.json'))
    return model, model['display']['firstperson_righthand']


def screen_points(model, display, select):
    """Screen positions of the corners of the elements `select` picks."""
    m = fpview.hand_matrix(display)
    out = []
    for element in model['elements']:
        if not select(element):
            continue
        (x0, y0, z0), (x1, y1, z1) = element['from'], element['to']
        for x in (x0, x1):
            for y in (y0, y1):
                for z in (z0, z1):
                    p = fpview.project(fpview.apply(m, (x / 16, y / 16, z / 16)))
                    if p:
                        out.append(p)
    return out


def reticle_check(gid, oid):
    model, display = posed('gun_opt_ads/%s__%s' % (gid, oid))
    glow = screen_points(model, display, lambda e: any(f.get('texture') == '#r' for f in e.get('faces', {}).values()))
    if not glow:
        return 'no reticle'
    cx = sum(p[0] for p in glow) / len(glow)
    cy = sum(p[1] for p in glow) / len(glow)
    off = math.hypot(cx - fpview.W / 2, cy - fpview.H / 2)
    window = models.sight_window(gid, oid)
    frame = [fpview.project(fpview.apply(fpview.hand_matrix(display), (window['x'] / 16, y / 16, z / 16)))
             for y in (window['y0'], window['y1']) for z in (window['z0'], window['z1'])]
    width = (max(p[0] for p in frame) - min(p[0] for p in frame)) / fpview.W
    return off, width


def main():
    out_dir = sys.argv[sys.argv.index('--png') + 1] if '--png' in sys.argv else None
    misses = []
    for gid in models.GUNS:
        for oid in models.optics_for(gid):
            _, _, sight = models.optic_data(oid)
            if sight is None:
                continue
            result = reticle_check(gid, oid)
            if isinstance(result, str):
                misses.append('%s %s: %s' % (gid, oid, result))
                continue
            off, width = result
            ok = off <= 1.0 and WINDOW[0] <= width <= WINDOW[1]
            print('%-4s %-13s reticle %.2f px from centre, window %.1f%% of the width  %s' % (gid, oid, off, width * 100, 'ok' if ok else 'MISS'))
            if not ok:
                misses.append('%s %s' % (gid, oid))
            if out_dir:
                model, display = posed('gun_opt_ads/%s__%s' % (gid, oid))
                fpview.render(model, display, os.path.join(out_dir, 'ads_%s_%s.png' % (gid, oid)), '%s %s aimed' % (gid, oid))
    if misses:
        print('misses: ' + ', '.join(misses))
        sys.exit(1)


if __name__ == '__main__':
    main()
