"""Pixel comparison of two revisions' first-person gun views, through their item definitions.

Each gun's items/<id>.json is resolved for a held state as the 1.21.11 client resolves it (display
context, the damage hand mark, the cooldown, the custom_model_data strings, held keys) down to the
models it draws, and fpview renders them. A refactor of the item trees (the per-pose split of
a32ee9c67, say) should leave every state's picture unchanged:

    python3 tools/art/treecmp.py 1c3661813 a32ee9c67 k47 m4c p18      # exit 1 if any state differs
    python3 tools/art/treecmp.py HEAD~1 HEAD k47 --png /tmp/cmp        # also keep the renders

Each revision's generated pack is taken from git (git archive), so neither needs building.
"""
import json
import os
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fpview  # noqa: E402
import numpy as np  # noqa: E402
from PIL import Image  # noqa: E402

REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
ASSETS = 'src/main/resources/pack/craftov/resourcepack/assets/craftov'

# Held states: damage is the hand mark (ItemFactory: 0 none, 2 reloading, 4 aimed), cooldown the
# fraction the client reads (a shot's kick early on), strings the attachments (0 optic, 1 muzzle).
STATES = {
    'hip': dict(damage=0, cooldown=0.0, strings=[]),
    'hip-kick': dict(damage=0, cooldown=0.9, strings=[]),
    'hip-suppressor': dict(damage=0, cooldown=0.0, strings=['', 'att_suppressor']),
    'hip-reddot': dict(damage=0, cooldown=0.0, strings=['optic_reddot']),
    'ads': dict(damage=4, cooldown=0.0, strings=[]),
    'ads-kick': dict(damage=4, cooldown=0.9, strings=[]),
    'ads-reddot': dict(damage=4, cooldown=0.0, strings=['optic_reddot']),
    'ads-holo': dict(damage=4, cooldown=0.0, strings=['optic_holo']),
}


def resolve(node, state):
    """The model references an item model tree draws in `state`."""
    kind = node['type']
    if kind == 'minecraft:model':
        return [node['model']]
    if kind == 'minecraft:empty':
        return []
    if kind == 'minecraft:composite':
        return [m for sub in node['models'] for m in resolve(sub, state)]
    if kind == 'minecraft:condition':
        if node['property'] != 'minecraft:keybind_down':
            raise ValueError(node['property'])
        return resolve(node['on_true'] if node['keybind'] in state['keys'] else node['on_false'], state)
    if kind == 'minecraft:select':
        prop = node['property']
        if prop == 'minecraft:display_context':
            value = state['context']
        elif prop == 'minecraft:charge_type':
            value = 'none'
        elif prop == 'minecraft:custom_model_data':
            index = node.get('index', 0)
            value = state['strings'][index] if index < len(state['strings']) else None
        else:
            raise ValueError(prop)
        for case in node['cases']:
            if value in (case['when'] if isinstance(case['when'], list) else [case['when']]):
                return resolve(case['model'], state)
        return resolve(node['fallback'], state) if 'fallback' in node else []
    if kind == 'minecraft:range_dispatch':
        value = np.float32({'minecraft:damage': state['damage'], 'minecraft:cooldown': state['cooldown']}[node['property']])
        best = None
        for entry in sorted(node['entries'], key=lambda e: np.float32(e['threshold'])):
            if np.float32(entry['threshold']) <= value:
                best = entry
        if best is not None:
            return resolve(best['model'], state)
        return resolve(node['fallback'], state) if 'fallback' in node else []
    raise ValueError(kind)


def render(root, gid, state, out):
    """Renders the gun as `state` shows it from the pack at `root`; returns the models drawn."""
    fpview.ROOT = root
    with open(os.path.join(root, 'items', gid + '.json'), encoding='utf-8') as f:
        refs = resolve(json.load(f)['model'], state)
    posed = []
    for ref in refs:
        model = fpview.load_model(os.path.join(root, 'models', ref.split(':', 1)[1] + '.json'))
        posed.append((model, model['display'][state['context']]))
    fpview.render_many(posed, out, '')
    return refs


def extract(rev, into):
    archive = subprocess.run(['git', '-C', REPO, 'archive', rev, ASSETS], capture_output=True, check=True).stdout
    subprocess.run(['tar', '-x', '-C', into], input=archive, check=True)
    return os.path.join(into, ASSETS)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    keep = sys.argv[sys.argv.index('--png') + 1] if '--png' in sys.argv else None
    if keep:
        args.remove(keep)
    rev_a, rev_b, guns = args[0], args[1], args[2:]
    fpview.CROSSHAIR = False
    differ = 0
    with tempfile.TemporaryDirectory() as tmp:
        roots = []
        for k, rev in enumerate((rev_a, rev_b)):
            os.makedirs(os.path.join(tmp, str(k)))
            roots.append(extract(rev, os.path.join(tmp, str(k))))
        out = keep or tmp
        os.makedirs(out, exist_ok=True)
        for gid in guns:
            for name, state in STATES.items():
                state = dict(state, context='firstperson_righthand', keys=set())
                shots, drawn = [], []
                for k, root in enumerate(roots):
                    path = os.path.join(out, '%s-%s-%s.png' % (gid, name, 'ab'[k]))
                    drawn.append(len(render(root, gid, state, path)))
                    shots.append(np.asarray(Image.open(path).convert('RGB'), dtype=int))
                diff = np.abs(shots[0] - shots[1]).max(2)
                changed = int((diff > 0).sum())
                differ += changed > 0
                print('%-5s %-15s models %d -> %d  %s' % (gid, name, drawn[0], drawn[1],
                      'same pixels' if not changed else '%d pixels differ (up to %d)' % (changed, diff.max())))
    print('%d state(s) differ' % differ)
    sys.exit(1 if differ else 0)


if __name__ == '__main__':
    main()
