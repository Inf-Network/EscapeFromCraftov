"""Checks the aimed sight picture offline with fpview's copy of the client's first-person maths.

    python3 tools/art/sightcheck.py                 # every gun and sight: numbers, exit 1 on a miss
    python3 tools/art/sightcheck.py --png out_dir   # also renders each aimed view

1x sights (red dot, holo): the reticle's centre is within 1 pixel of the screen centre and the
sight's window is 18-25% of the screen width, at the 2000x1168 window fpview draws at (1000x584).
Scopes: the mask's hole is round, its radius the same to 3% across, at 1000x584 and at 1000x750, and
the mask reaches past the screen's edges.
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


def aimed_optic(gid, oid):
    """The optic mounted on the gun's aimed pose for it: an attachment of that pose (build.wrap_attachments),
    so it carries the pose's display and its own elements (the housing and the reticle)."""
    return posed('gun_att/%s/gun_ads__%s__%s' % (oid, gid, oid))


def reticle_check(gid, oid):
    model, display = aimed_optic(gid, oid)
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


def mask_radii(oid, w, h, samples=72):
    """Screen radii (pixels from the centre) of the scope mask's hole edge, all round, at a w x h window."""
    model = json.load(open(os.path.join(ROOT, 'models', 'item', 'scope_mask', oid + '.json'), encoding='utf-8'))
    display = model['display']['firstperson_righthand']
    (x0, y0, z), (x1, y1, _) = model['elements'][0]['from'], model['elements'][0]['to']
    hole = models.mask_hole_fraction() * (x1 - x0) / 2
    fpview.W, fpview.H = w, h
    m = fpview.hand_matrix(display)
    radii = []
    for k in range(samples):
        a = 2 * math.pi * k / samples
        p = fpview.project(fpview.apply(m, ((8 + hole * math.cos(a)) / 16, (8 + hole * math.sin(a)) / 16, z / 16)))
        radii.append(math.hypot(p[0] - w / 2, p[1] - h / 2))
    corners = [fpview.project(fpview.apply(m, (x / 16, y / 16, z / 16))) for x in (x0, x1) for y in (y0, y1)]
    covers = all(p is not None and (p[0] <= 0 or p[0] >= w) and (p[1] <= 0 or p[1] >= h) for p in corners)
    fpview.W, fpview.H = 1000, 584
    return radii, covers


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
                gun, display = posed('gun_ads/%s__%s' % (gid, oid))
                optic, _ = aimed_optic(gid, oid)
                fpview.render_many([(gun, display), (optic, display)], os.path.join(out_dir, 'ads_%s_%s.png' % (gid, oid)),
                                   '%s %s aimed' % (gid, oid))
    for oid in models.MASK_HOLE:
        for w, h in ((1000, 584), (1000, 750), (1000, 400)):
            radii, covers = mask_radii(oid, w, h)
            spread = (max(radii) - min(radii)) / (sum(radii) / len(radii))
            share = sum(radii) / len(radii) / h
            ok = spread <= 0.03 and covers
            print('%-13s mask at %dx%d: hole radius %.1f px (%.2f of the height, %.2f%% out of round), covers the screen: %s  %s'
                  % (oid, w, h, sum(radii) / len(radii), share, spread * 100, covers, 'ok' if ok else 'MISS'))
            if not ok:
                misses.append('%s mask %dx%d' % (oid, w, h))
    if misses:
        print('misses: ' + ', '.join(misses))
        sys.exit(1)


if __name__ == '__main__':
    main()
