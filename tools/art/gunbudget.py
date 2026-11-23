"""What each gun's item definition costs the client, and whether it stays inside the budget.

Every model an item definition references is baked on its own: a pose or reload frame that only
changes the display transform still bakes a full copy of the gun's quads. This walks each gun's
items/<id>.json, resolves every referenced model through its parents and counts:

  models   distinct models the definition references (each one baked once)
  refs     model nodes in the definition (how much the client walks to pick one)
  quads    faces baked over all those models: the heap the gun's models take
  fp       faces of the first-person body at rest (the fine model)
  third    faces of the third-person body (the low model)

    python3 tools/art/gunbudget.py            # table of every gun, exit 1 over budget
    python3 tools/art/gunbudget.py k47 --tree # one gun, with the models it references by folder
    python3 tools/art/gunbudget.py --lint     # also check every gun's models (see lint), exit 1 on a problem
"""
import json
import os
import sys
from collections import Counter

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'src', 'main', 'resources', 'pack', 'craftov',
                    'resourcepack', 'assets', 'craftov')
GUNS = ['p18', 'd50', 'sv9', 'v57', 'k47', 'm4c', 'scr', 'kl7', 'r12', 'dm14', 'awr']

# Per gun: distinct models, baked quads (all models together), faces of the fine first-person body,
# faces of the low third-person body.
BUDGET = {'models': 1600, 'quads': 100_000, 'fp': 1000, 'third': 350}

_MODELS = {}


def model(ref):
    """A model resolved through its parents: {'elements': [...], 'display': {...}} (only craftov models)."""
    if ref in _MODELS:
        return _MODELS[ref]
    ns, path = ref.split(':', 1) if ':' in ref else ('minecraft', ref)
    if ns != 'craftov':
        _MODELS[ref] = {'elements': [], 'display': {}}
        return _MODELS[ref]
    with open(os.path.join(ROOT, 'models', path + '.json'), encoding='utf-8') as f:
        data = json.load(f)
    resolved = {'elements': data.get('elements'), 'display': dict(data.get('display', {}))}
    if 'parent' in data:
        parent = model(data['parent'])
        if resolved['elements'] is None:
            resolved['elements'] = parent['elements']
        resolved['display'] = dict(parent['display'], **resolved['display'])
    resolved['elements'] = resolved['elements'] or []
    _MODELS[ref] = resolved
    return resolved


def faces(ref):
    return sum(len(e.get('faces', {})) for e in model(ref)['elements'])


def walk(node, out):
    """Collects every model reference of an item model tree (with repeats)."""
    if isinstance(node, list):
        for v in node:
            walk(v, out)
    elif isinstance(node, dict):
        if node.get('type') == 'minecraft:model':
            out.append(node['model'])
        for v in node.values():
            walk(v, out)


def measure(gid):
    with open(os.path.join(ROOT, 'items', gid + '.json'), encoding='utf-8') as f:
        definition = json.load(f)
    refs = []
    walk(definition, refs)
    distinct = sorted(set(refs))
    fp = faces('craftov:item/gun/' + gid)
    third_ref = 'craftov:item/gun_low/' + gid
    third = faces(third_ref) if os.path.exists(os.path.join(ROOT, 'models', 'item', 'gun_low', gid + '.json')) else fp
    return {'models': len(distinct), 'refs': len(refs), 'quads': sum(faces(r) for r in distinct), 'fp': fp,
            'third': third, 'distinct': distinct}


MIN_DENSITY = 12        # texels a unit on the faces the first-person camera sees (left side and top)


def _face_dims(element, face):
    import gunpaint
    _, (ua, _), (va, _) = gunpaint.FACES[face]
    lo, hi = element['from'], element['to']
    return hi[ua] - lo[ua], hi[va] - lo[va]


def _coplanar(elements):
    """Pairs of unturned elements with faces in one plane, facing the same way and overlapping: they
    would flicker (z-fighting)."""
    import gunpaint
    faces = []
    for k, e in enumerate(elements):
        if e.get('rotation'):
            continue
        for face in e.get('faces', {}):
            normal, (ua, _), (va, _) = gunpaint.FACES[face]
            axis = next(i for i in range(3) if normal[i])
            plane = e['to'][axis] if normal[axis] > 0 else e['from'][axis]
            faces.append((face, round(plane, 3), e['from'][ua], e['to'][ua], e['from'][va], e['to'][va], k))
    out = []
    for i, a in enumerate(faces):
        for b in faces[i + 1:]:
            if a[0] != b[0] or a[6] == b[6] or abs(a[1] - b[1]) > 0.002:
                continue
            overlap = max(0.0, min(a[3], b[3]) - max(a[2], b[2])) * max(0.0, min(a[5], b[5]) - max(a[4], b[4]))
            if overlap > 0.01:
                out.append((a[6], b[6], a[0], round(overlap, 3)))
    return out


def _timed_frames(gid):
    """[(kind, range dispatch entries, ticks)] of the gun's reload and draw frames, from its item
    definition (the first-person tree's damage 2 and 1 branches), with the cooldown the server sends
    for each: the draw time, and the reload in items.yml less the tick before showReload starts it."""
    import yaml
    import models
    with open(os.path.join(ROOT, 'items', gid + '.json'), encoding='utf-8') as f:
        definition = json.load(f)
    items = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'src', 'main', 'resources', 'items.yml')
    with open(items, encoding='utf-8') as f:
        gun = yaml.safe_load(f)['items'][gid]['gun']
    out = []

    def find(node):
        if isinstance(node, dict):
            if node.get('property') == 'minecraft:damage':
                yield node
            for v in node.values():
                yield from find(v)
        elif isinstance(node, list):
            for v in node:
                yield from find(v)
    for marks in find(definition):
        by = {round(e['threshold'] + 0.5): e['model'] for e in marks['entries']}
        out.append(('reload', by[2]['entries'], models.reload_cooldown(gun['reload'])))
        out.append(('draw', by[1]['entries'], models.draw_ticks(gun.get('action'))))
    return out


# Iron sights, seen from the eye as aimed: rays this many degrees over the aim point (and to either side)
# must meet nothing, and the ray UNDER_AIM degrees under it must meet the front sight first.
OVER_AIM = [(up, side) for up in (0.05, 0.25, 0.5) for side in (-0.25, 0.0, 0.25)]
UNDER_AIM = 0.15


def _describe(p):
    c = p.corners()
    return '%s (x %.2f-%.2f, top %.2f)' % ('mesh piece %d' % p.piece if p.piece >= 0 else 'a hand-placed part',
                                          c[:, 0].min(), c[:, 0].max(), c[:, 1].max())


def iron_sight(gid):
    """Problems with a gun's iron sight picture (models.iron_eye against the fine model): something standing
    over the aim point (a safety, a slide serration) hides what the shot goes to, and a front sight that is
    not what the aim point rests on (hidden behind the rear sight, or under a sight line drawn elsewhere)
    gives the player nothing to line up."""
    import math
    import meshprim
    import models
    sight = meshprim.sight_y(gid)
    if sight is None or not meshprim.spec(gid).get('eye'):
        return []
    eye, per_block = models.iron_eye(gid)
    near = 0.05 * per_block                                   # the client's near plane: 0.05 blocks
    prims = meshprim.lod(gid, 'fine')
    out = []
    over = set()
    for up, side in OVER_AIM:
        hit = meshprim.first_hit(prims, eye, (1, math.tan(math.radians(up)), math.tan(math.radians(side))), near)
        if hit and hit[1] not in over:
            over.add(hit[1])
            out.append('%s: %s stands over the aim point (%.2f degrees up)' % (gid, _describe(prims[hit[1]]), up))
    hit = meshprim.first_hit(prims, eye, (1, -math.tan(math.radians(UNDER_AIM)), 0), near)
    rear = meshprim.spec(gid)['eye'][0]
    if hit is None:
        out.append('%s: nothing under the aim point: no front sight' % gid)
    else:
        p = prims[hit[1]]
        top = p.corners()[:, 1].max()
        if eye[0] + hit[0] < rear + 2 or abs(top - sight) > 0.03:
            out.append('%s: under the aim point is %s, not the front sight topping out at sight_y %.2f'
                       % (gid, _describe(p), sight))
    return out


def lint(gid):
    """Problems with one gun's models: references that do not resolve, coordinates outside -16..32, and
    for a fitted gun (meshprim.py) each level of detail over its budget, the outline off the mesh (IoU
    under 0.85), the seen faces under MIN_DENSITY texels a unit, coplanar overlapping faces, texture
    statistics (each finish's mean brightness in its range and creases at least 15% darker than open
    faces) and the iron sight picture (iron_sight)."""
    import meshprim
    import gunpaint
    problems = []
    try:
        m = measure(gid)
    except (OSError, ValueError) as ex:
        return ['%s: %s' % (gid, ex)]
    for ref in m['distinct']:
        for e in model(ref)['elements']:
            if min(e['from'] + e['to']) < -16 or max(e['from'] + e['to']) > 32:
                problems.append('%s: %s has an element outside -16..32' % (gid, ref))
                break
    # The reload and draw frames: at most one a tick (the client reads the cooldown once a tick), their
    # cooldown thresholds falling, and, replaying the client's float sampling of the cooldown the server
    # sends, every frame shown: the draw's frame t on tick t, the reload's each for a tick or more.
    import models
    for kind, entries, ticks in _timed_frames(gid):
        thresholds = [e['threshold'] for e in entries]
        if len(entries) > ticks:
            problems.append('%s: %d %s frames for %d ticks' % (gid, len(entries), kind, ticks))
        if any(a <= b for a, b in zip(thresholds, thresholds[1:])):
            problems.append('%s: %s frame thresholds do not fall' % (gid, kind))
        shown = models.cooldown_frames(thresholds, ticks)
        missed = sorted(set(range(len(entries))) - set(shown))
        if missed:
            problems.append('%s: %s frames %s never show over a %d-tick cooldown' % (gid, kind, missed, ticks))
        if None in shown:
            problems.append('%s: the %s falls back to rest before its cooldown ends' % (gid, kind))
        if kind == 'draw' and shown != list(range(ticks)):
            problems.append('%s: draw ticks show frames %s, not one each in turn' % (gid, shown))
    if not meshprim.has_spec(gid):
        return problems
    for level in ('fine', 'mid', 'low'):
        prims = meshprim.lod(gid, level)
        faces = sum(len(f) for f in gunpaint.visible_faces(prims))
        cap_elements, cap_faces = meshprim.BUDGET[level]
        if len(prims) > cap_elements or faces > cap_faces:
            problems.append('%s: %s model has %d elements and %d faces (budget %d / %d)' % (gid, level, len(prims), faces, cap_elements, cap_faces))
    iou = meshprim.iou_side(gid)
    if iou < 0.85:
        problems.append('%s: fitted outline IoU %.3f against the mesh (under 0.85)' % (gid, iou))
    data = meshprim.build(gid)
    size = data['texture'].size[0]
    fine = [e for part in data['parts'].values() for e in part]
    thin = 0
    for e in fine:
        for face in ('north', 'up'):
            if face not in e['faces']:
                continue
            w, h = _face_dims(e, face)
            u0, v0, u1, v1 = e['faces'][face]['uv']
            if w > 0.2 and (u1 - u0) / 16 * size / w < MIN_DENSITY - 1:
                thin += 1
    if thin:
        problems.append('%s: %d seen faces under %d texels a unit' % (gid, thin, MIN_DENSITY))
    for a, b, face, area in _coplanar(fine):
        problems.append('%s: elements %d and %d share a %s plane over %.3f unit^2' % (gid, a, b, face, area))
    problems += iron_sight(gid)
    for mat, st in data['texture'].info.get('stats', {}).items():
        lo, hi = gunpaint.target_lum(mat)
        if st['texels'] > 500 and not lo <= st['lum'] <= hi:
            problems.append('%s: %s averages luminance %.3f (expected %.3f..%.3f)' % (gid, mat, st['lum'], lo, hi))
        if st['crease'] is not None and st['open'] is not None and st['texels'] > 500 and st['crease'] > 0.85 * st['open']:
            problems.append('%s: %s creases (%.3f) are not darker than open faces (%.3f)' % (gid, mat, st['crease'], st['open']))
    return problems


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    guns = args or GUNS
    over = []
    print('%-5s %7s %7s %9s %6s %6s' % ('gun', 'models', 'refs', 'quads', 'fp', 'third'))
    total = Counter()
    for gid in guns:
        m = measure(gid)
        flags = [k for k in BUDGET if m[k] > BUDGET[k]]
        print('%-5s %7d %7d %9d %6d %6d %s' % (gid, m['models'], m['refs'], m['quads'], m['fp'], m['third'],
                                              ('over: ' + ', '.join(flags)) if flags else ''))
        over += [(gid, k) for k in flags]
        total.update({k: m[k] for k in ('models', 'refs', 'quads')})
        if '--tree' in sys.argv:
            folders = Counter(r.split(':', 1)[1].rsplit('/', 1)[0].split('/')[1] for r in m['distinct'])
            for folder, n in folders.most_common():
                print('      %-14s %5d' % (folder, n))
    print('%-5s %7d %7d %9d' % ('all', total['models'], total['refs'], total['quads']))
    if '--lint' in sys.argv:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        problems = [p for gid in guns for p in lint(gid)]
        for problem in problems:
            print('lint: ' + problem)
        print('lint: %s' % ('%d problem(s)' % len(problems) if problems else 'every gun passes'))
        over += problems
    if over:
        sys.exit(1)


if __name__ == '__main__':
    main()
