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
BUDGET = {'models': 1600, 'quads': 80_000, 'fp': 1000, 'third': 350}

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
    if over:
        sys.exit(1)


if __name__ == '__main__':
    main()
