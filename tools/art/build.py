"""Builds the Craftov CraftEngine resource folder and BetterHud layout from scratch.

Usage: python3 tools/art/build.py   (from the repository root or anywhere)
Outputs:
  src/main/resources/pack/craftov/     -> installed to plugins/CraftEngine/resources/craftov/
  src/main/resources/betterhud/        -> installed to plugins/BetterHud/
"""
import json
import os
import shutil
import sys

import numpy as np
import yaml
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
import hudart  # noqa: E402
import icons  # noqa: E402
import models  # noqa: E402
import skins  # noqa: E402
import soldiers  # noqa: E402
import sounds  # noqa: E402
import gunaudio  # noqa: E402
import ui  # noqa: E402
from px import gradient_bg, rgb  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
RES = os.path.join(ROOT, 'src', 'main', 'resources')
PACK = os.path.join(RES, 'pack', 'craftov')
ASSETS = os.path.join(PACK, 'resourcepack', 'assets')
CV = os.path.join(ASSETS, 'craftov')
MC = os.path.join(ASSETS, 'minecraft')
BH = os.path.join(RES, 'betterhud')

RARITY_COLORS = {'WHITE': '#C8CCD2', 'GREEN': '#5BD16B', 'BLUE': '#4FA3FF', 'PURPLE': '#B66BFF', 'GOLD': '#FFC23D', 'RED': '#FF4D4D'}


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)


def write_text(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(text)


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def model_ref(model):
    return {'type': 'minecraft:model', 'model': model}


def gui_select(gui_model, other_model):
    return {'model': {'type': 'minecraft:select', 'property': 'minecraft:display_context',
                      'cases': [{'when': ['gui'], 'model': model_ref(gui_model)}], 'fallback': model_ref(other_model)}}


def generated(texture, parent='minecraft:item/generated'):
    return {'parent': parent, 'textures': {'layer0': texture}}


def build_items(item_defs):
    for item_id, spec in item_defs.items():
        rarity = spec.get('rarity', 'WHITE')
        bg = gradient_bg((32, 32), rgb(RARITY_COLORS[rarity]))
        if item_id in models.GUNS:
            icon = models.gun_icon(item_id)
            write_json(os.path.join(CV, 'models', 'item', 'gun', item_id + '.json'), models.gun_model(item_id))
            skin = models.gun_skin_image(item_id)
            if skin is not None:
                save(skin, os.path.join(CV, 'textures', 'item', 'gun', item_id + '_skin.png'))
            parts = models.gun_parts(item_id)
            for part in parts:
                write_json(os.path.join(CV, 'models', 'item', 'gun_part', item_id + '_' + part + '.json'),
                           models.gun_part_model(item_id, part))
            frames = {}
            for k, part, parent, right in models.reload_frames(item_id):
                name = 'craftov:item/gun_reload/%s/%s_%d' % (item_id, part, k)
                write_json(os.path.join(CV, 'models', 'item', 'gun_reload', item_id, '%s_%d.json' % (part, k)),
                           models.frame_model(parent, right))
                frames.setdefault(k, []).append(name)
            write_json(os.path.join(CV, 'models', 'item', 'gun_ai', item_id + '.json'), models.gun_ai_model(item_id))
            zoom = (spec.get('gun') or {}).get('zoom', 0)
            write_json(os.path.join(CV, 'models', 'item', 'gun_ads', item_id + '.json'), models.gun_ads_model(item_id, zoom))
            write_json(os.path.join(CV, 'items', 'ai_' + item_id + '.json'), {'model': model_ref('craftov:item/gun_ai/' + item_id)})
            held = 'craftov:item/gun/' + item_id
        elif item_id in models.OPTICS:
            icon = models.optic_icon(item_id)
            held = 'craftov:item/optic/' + item_id
            save(models.optic_skin_image(item_id), os.path.join(CV, 'textures', 'item', 'optic', item_id + '_skin.png'))
            write_json(os.path.join(CV, 'models', 'item', 'optic', item_id + '.json'), models.optic_item_model(item_id))
        else:
            icon = icons.render(item_id)
            held = 'craftov:item/' + item_id
            # Melee weapons are held like a sword; everything else like a flat item.
            parent = 'minecraft:item/handheld' if spec.get('category') == 'MELEE' else 'minecraft:item/generated'
            write_json(os.path.join(CV, 'models', 'item', item_id + '.json'), generated('craftov:item/' + item_id, parent))
        save(icon, os.path.join(CV, 'textures', 'item', item_id + '.png'))
        composite = bg.copy()
        composite.alpha_composite(icon)
        save(composite, os.path.join(CV, 'textures', 'item', 'gui', item_id + '.png'))
        write_json(os.path.join(CV, 'models', 'item', 'gui', item_id + '.json'), generated('craftov:item/gui/' + item_id))
        if item_id in models.GUNS:
            # Pose trees, all picked on the client from held keys (no packet, no delay): right mouse aims
            # down sights; left mouse kicks the gun back and flashes the muzzle; sprinting carries it low
            # and canted; strafing leans it. Scoped guns hide the gun while aiming, so they keep plain ADS.
            def posed_aim(name, hip, ads, hip_display, ads_display):
                def key(k, on, off):
                    return {'type': 'minecraft:condition', 'property': 'minecraft:keybind_down', 'keybind': k,
                            'on_true': on, 'on_false': off}

                def pose(kind, base, display, pose_name, spec):
                    path = 'gun_pose/%s_%s/%s' % (kind, pose_name, name)
                    write_json(os.path.join(CV, 'models', 'item', path + '.json'), models.pose_model(base, display, spec))
                    return model_ref('craftov:item/' + path)

                def flash(kind, base, display, spec):
                    fire = models.pose_model(base, display, spec)['display']
                    path = 'gun_flash/%s/%s' % (kind, name)
                    write_json(os.path.join(CV, 'models', 'item', path + '.json'),
                               models.flash_model(item_id, fire['firstperson_righthand'], fire['firstperson_lefthand']))
                    return model_ref('craftov:item/' + path)

                def strafe(base, poses):
                    return key('key.left', poses['left'], key('key.right', poses['right'], base))

                hp = {n: pose('hip', hip, hip_display, n, spec) for n, spec in models.HIP_POSES.items()}
                hip_idle = strafe(model_ref(hip), hp)
                hip_tree = key('key.attack', {'type': 'minecraft:composite', 'models': [
                    hp['fire'], flash('hip', hip, hip_display, models.HIP_POSES['fire'])]},
                    key('key.forward', key('key.sprint', hp['sprint'], hip_idle), hip_idle))
                if ads_display['firstperson_righthand'].get('scale') == [0, 0, 0]:
                    ads_tree = model_ref(ads)
                else:
                    ap = {n: pose('ads', ads, ads_display, n, spec) for n, spec in models.ADS_POSES.items()}
                    ads_tree = key('key.attack', {'type': 'minecraft:composite', 'models': [
                        ap['fire'], flash('ads', ads, ads_display, models.ADS_POSES['fire'])]}, strafe(model_ref(ads), ap))
                return key('key.use', ads_tree, hip_tree)
            # While the gun's reload cooldown runs, the client steps through the reload frames itself.
            def reloading(normal, optic_frames=None):
                entries = []
                for k, threshold in enumerate(models.reload.thresholds()):
                    names = frames[k] + ([optic_frames[k]] if optic_frames else [])
                    entries.append({'threshold': threshold, 'model': {
                        'type': 'minecraft:composite', 'models': [model_ref(n) for n in names]}})
                return {'type': 'minecraft:range_dispatch', 'property': 'minecraft:cooldown', 'entries': entries,
                        'fallback': normal}
            in_hand = reloading(posed_aim(item_id, held, 'craftov:item/gun_ads/' + item_id,
                                          models.gun_model(item_id)['display'], models.gun_ads_model(item_id, zoom)['display']))
            # Other players receive the gun as a charged crossbow while it is raised (StanceService),
            # which gives the two-handed aiming pose; these models keep the barrel straight in it.
            write_json(os.path.join(CV, 'models', 'item', 'gun_aimed', item_id + '.json'), models.gun_aimed_model(item_id, held))
            aimed = model_ref('craftov:item/gun_aimed/' + item_id)
            aimed_cases = []
            # A mounted optic is stored as custom_model_data string 0 and picks a gun+optic model.
            cases = []
            for oid in models.optics_for(item_id):
                combo = item_id + '__' + oid
                write_json(os.path.join(CV, 'models', 'item', 'gun_opt', combo + '.json'), models.gun_with_optic(item_id, oid, False))
                write_json(os.path.join(CV, 'models', 'item', 'gun_opt_ads', combo + '.json'), models.gun_with_optic(item_id, oid, True))
                write_json(os.path.join(CV, 'models', 'item', 'gun_part', combo + '.json'), models.optic_on_gun_model(item_id, oid))
                optic_frames = []
                for k, right in enumerate(models.reload_frames_optic(item_id)):
                    name = 'craftov:item/gun_reload/%s/%s_%d' % (item_id, oid, k)
                    write_json(os.path.join(CV, 'models', 'item', 'gun_reload', item_id, '%s_%d.json' % (oid, k)),
                               models.frame_model('craftov:item/gun_part/' + combo, right))
                    optic_frames.append(name)
                write_json(os.path.join(CV, 'models', 'item', 'gun_aimed', combo + '.json'),
                           models.gun_aimed_model(item_id, 'craftov:item/gun_opt/' + combo))
                aimed_cases.append({'when': oid, 'model': model_ref('craftov:item/gun_aimed/' + combo)})
                cases.append({'when': oid, 'model': reloading(posed_aim(
                    combo, 'craftov:item/gun_opt/' + combo, 'craftov:item/gun_opt_ads/' + combo,
                    models.gun_with_optic(item_id, oid, False)['display'], models.gun_with_optic(item_id, oid, True)['display']),
                    optic_frames)})
            if cases:
                in_hand = {'type': 'minecraft:select', 'property': 'minecraft:custom_model_data', 'index': 0,
                           'cases': cases, 'fallback': in_hand}
                aimed = {'type': 'minecraft:select', 'property': 'minecraft:custom_model_data', 'index': 0,
                         'cases': aimed_cases, 'fallback': aimed}
            in_hand = {'type': 'minecraft:select', 'property': 'minecraft:charge_type',
                       'cases': [{'when': 'arrow', 'model': aimed}], 'fallback': in_hand}
            write_json(os.path.join(CV, 'items', item_id + '.json'), {'model': {
                'type': 'minecraft:select', 'property': 'minecraft:display_context',
                'cases': [{'when': ['gui'], 'model': model_ref('craftov:item/gui/' + item_id)}], 'fallback': in_hand}})
        else:
            write_json(os.path.join(CV, 'items', item_id + '.json'), gui_select('craftov:item/gui/' + item_id, held))
    save(models.palette_texture(models.GUN_PALETTE), os.path.join(CV, 'textures', 'item', 'gun_palette.png'))
    write_json(os.path.join(CV, 'models', 'item', 'gun_part', 'shell.json'), models.shell_model())
    save(models.palette_texture(models.BOX_PALETTE), os.path.join(CV, 'textures', 'item', 'box_palette.png'))


def build_containers():
    for cid in models.CONTAINERS:
        write_json(os.path.join(CV, 'models', 'item', 'container', cid + '.json'), models.container_model(cid))
        write_json(os.path.join(CV, 'items', 'container', cid + '.json'), {'model': model_ref('craftov:item/container/' + cid)})


def save_animated(frames, path, frametime=2):
    """Vertical strip plus .mcmeta: item textures animate on the client with no server updates."""
    w, h = frames[0].size
    strip = Image.new('RGBA', (w, h * len(frames)))
    for i, frame in enumerate(frames):
        strip.paste(frame, (0, h * i))
    save(strip, path)
    write_json(path + '.mcmeta', {'animation': {'frametime': frametime}})


def flash_frame(kind, strength, seed):
    """One 16x16 frame of muzzle flash: a jagged jet seen side-on, or a star burst seen head-on."""
    import numpy as np
    rng = np.random.default_rng(seed)
    n = 64
    yy, xx = np.mgrid[0:n, 0:n] / (n - 1) * 2 - 1
    if kind == 'side':
        # Elongated along u, thickest at the middle, ragged edges.
        width = 0.55 * (1 - np.abs(xx) ** 1.6) * (0.75 + 0.25 * np.cos(xx * 9 + rng.uniform(0, 6)))
        d = np.abs(yy) / np.maximum(width, 1e-3)
        a = np.clip(1 - d, 0, 1) * np.clip(1 - np.abs(xx) ** 3, 0, 1)
    else:
        r = np.hypot(xx, yy)
        ang = np.arctan2(yy, xx)
        spikes = 0.45 + 0.55 * np.abs(np.cos(ang * 2.5 + rng.uniform(0, 3))) ** 3
        a = np.clip(1 - r / (0.95 * spikes), 0, 1)
    a = a ** 0.8 * strength
    core = np.clip(a * 1.8 - 0.6, 0, 1)
    rgb = np.stack([np.full_like(a, 255), 150 + 95 * np.clip(a * 1.4, 0, 1) + 10 * core, 40 + 200 * core], -1)
    img = np.concatenate([np.clip(rgb, 0, 255), (np.clip(a * 1.3, 0, 1) * 255)[..., None]], -1).astype(np.uint8)
    return Image.fromarray(img).resize((16, 16), Image.LANCZOS)


def bullet_hole(metal, seed):
    """Bullet mark: a dark crater with a cracked, dusty rim; on metal a bright dented ring instead."""
    import numpy as np
    rng = np.random.default_rng(seed)
    n = 64
    yy, xx = np.mgrid[0:n, 0:n] / (n - 1) * 2 - 1
    r = np.hypot(xx, yy)
    ang = np.arctan2(yy, xx)
    jag = 1 + 0.12 * np.sin(ang * 7 + rng.uniform(0, 6)) + 0.08 * np.sin(ang * 13 + rng.uniform(0, 6))
    rr = r * jag
    rgba = np.zeros((n, n, 4))
    if metal:
        ring = np.exp(-((rr - 0.42) / 0.12) ** 2)
        hole = np.clip((0.3 - rr) / 0.06, 0, 1)
        rgba[..., :3] = (200 * ring + 40 * (1 - ring))[..., None] * np.array([1, 0.97, 0.9])
        rgba[..., :3] = rgba[..., :3] * (1 - hole[..., None]) + np.array([25, 22, 20]) * hole[..., None]
        rgba[..., 3] = np.clip(ring * 0.9 + hole, 0, 1)
    else:
        hole = np.clip((0.26 - rr) / 0.05, 0, 1)
        rim = np.clip(1 - (rr - 0.26) / 0.55, 0, 1) ** 2
        cracks = np.zeros_like(r)
        for _ in range(6):
            a0 = rng.uniform(-np.pi, np.pi)
            d = np.abs(np.angle(np.exp(1j * (ang - a0))))
            cracks += np.clip(1 - d / 0.07, 0, 1) * np.clip(1 - r / rng.uniform(0.6, 0.95), 0, 1)
        dark = np.clip(hole + 0.55 * rim + 0.6 * cracks, 0, 1)
        rgba[..., :3] = np.array([30, 27, 24]) * hole[..., None] + np.array([70, 66, 60]) * (1 - hole[..., None])
        rgba[..., 3] = dark * 0.95
    img = np.clip(rgba * [1, 1, 1, 255], 0, 255).astype(np.uint8)
    return Image.fromarray(img).resize((16, 16), Image.LANCZOS)


def build_fx():
    """Animated muzzle flash (bright, off, medium, off - one tick each) and bullet-hole decals."""
    blank = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for kind in ('side', 'front'):
        frames = [flash_frame(kind, 1.0, 1), blank, flash_frame(kind, 0.75, 2), blank]
        save_animated(frames, os.path.join(CV, 'textures', 'item', 'fx', 'muzzle_%s.png' % kind), frametime=1)
    # Decals are item displays lying flat on the wall (ImpactFx.java): one face at z=8, seen from +Z.
    for name, metal in (('hole', False), ('hole_metal', True)):
        save(bullet_hole(metal, 3 if metal else 5), os.path.join(CV, 'textures', 'item', 'fx', name + '.png'))
        write_json(os.path.join(CV, 'models', 'item', 'fx', name + '.json'), {
            'textures': {'hole': 'craftov:item/fx/' + name, 'particle': 'craftov:item/fx/' + name},
            'elements': [{'from': [0, 0, 8], 'to': [16, 16, 8], 'shade': False,
                          'faces': {'south': {'uv': [0, 0, 16, 16], 'texture': '#hole'}}}]})
        write_json(os.path.join(CV, 'items', 'fx', name + '.json'), {'model': model_ref('craftov:item/fx/' + name)})


def build_ui():
    ui.UI['search_active'] = ui.search_spinner
    for name, fn in ui.UI.items():
        img = fn()
        if isinstance(img, list):
            save_animated([f.convert('RGBA') for f in img], os.path.join(CV, 'textures', 'item', 'ui', name + '.png'))
            write_json(os.path.join(CV, 'models', 'item', 'ui', name + '.json'), generated('craftov:item/ui/' + name))
            write_json(os.path.join(CV, 'items', 'ui', name + '.json'), {'model': model_ref('craftov:item/ui/' + name)})
            continue
        img = img.convert('RGBA')
        save(img, os.path.join(CV, 'textures', 'item', 'ui', name + '.png'))
        write_json(os.path.join(CV, 'models', 'item', 'ui', name + '.json'), generated('craftov:item/ui/' + name))
        write_json(os.path.join(CV, 'items', 'ui', name + '.json'), {'model': model_ref('craftov:item/ui/' + name)})
    save(ui.scope_overlay('4x'), os.path.join(CV, 'textures', 'misc', 'scope.png'))
    save(ui.suppression_overlay(), os.path.join(CV, 'textures', 'misc', 'suppressed.png'))
    for style in ('3x', '4x', '8x'):
        save(ui.scope_overlay(style), os.path.join(CV, 'textures', 'misc', 'scope_' + style + '.png'))
    # Health screen body parts: the plate stays as drawn, the silhouette takes the stack's dyed colour.
    save(ui.body_plate(), os.path.join(CV, 'textures', 'item', 'ui', 'body_plate.png'))
    for shape in ui.BODY_SHAPES:
        save(ui.body_shape(shape), os.path.join(CV, 'textures', 'item', 'ui', 'body_' + shape + '.png'))
        write_json(os.path.join(CV, 'models', 'item', 'ui', 'body_' + shape + '.json'), {
            'parent': 'minecraft:item/generated',
            'textures': {'layer0': 'craftov:item/ui/body_plate', 'layer1': 'craftov:item/ui/body_' + shape}})
        write_json(os.path.join(CV, 'items', 'ui', 'body_' + shape + '.json'), {'model': {
            'type': 'minecraft:model', 'model': 'craftov:item/ui/body_' + shape,
            'tints': [{'type': 'minecraft:constant', 'value': -1}, {'type': 'minecraft:dye', 'default': -8486772}]}})


def build_skins():
    save(skins.soldier_skin(), os.path.join(MC, 'textures', 'entity', 'zombie', 'zombie.png'))
    for asset, fn in skins.EQUIPMENT.items():
        save(fn(), os.path.join(CV, 'textures', 'entity', 'equipment', 'humanoid', asset + '.png'))
        write_json(os.path.join(CV, 'equipment', asset + '.json'), {'layers': {'humanoid': [{'texture': 'craftov:' + asset}]}})


def build_vanilla_hud():
    """Hide vanilla hearts, hunger, armor, air and XP: Craftov draws its own HUD."""
    blank9 = Image.new('RGBA', (9, 9), (0, 0, 0, 0))
    names = ['food_empty', 'food_half', 'food_full', 'food_empty_hunger', 'food_half_hunger', 'food_full_hunger',
             'armor_empty', 'armor_half', 'armor_full', 'air', 'air_empty', 'air_bursting']
    for n in names:
        save(blank9, os.path.join(MC, 'textures', 'gui', 'sprites', 'hud', n + '.png'))
    hearts = []
    for kind in ('', 'poisoned_', 'withered_', 'frozen_', 'absorbing_'):
        for hc in ('', 'hardcore_'):
            for part in ('full', 'half'):
                for blink in ('', '_blinking'):
                    hearts.append(kind + hc + part + blink)
    hearts += ['container', 'container_blinking', 'container_hardcore', 'container_hardcore_blinking',
               'vehicle_full', 'vehicle_half', 'vehicle_container', 'full', 'half']
    for n in set(hearts):
        save(blank9, os.path.join(MC, 'textures', 'gui', 'sprites', 'hud', 'heart', n + '.png'))
    save(Image.new('RGBA', (182, 5), (0, 0, 0, 0)), os.path.join(MC, 'textures', 'gui', 'sprites', 'hud', 'experience_bar_background.png'))
    save(Image.new('RGBA', (182, 5), (0, 0, 0, 0)), os.path.join(MC, 'textures', 'gui', 'sprites', 'hud', 'experience_bar_progress.png'))


def build_sounds():
    entries = {}
    for path, fn in sounds.SOUNDS.items():
        sounds.write_ogg(fn(), os.path.join(CV, 'sounds', path + '.ogg'))
        key = path.replace('/', '.')
        entry = {'sounds': [{'name': 'craftov:' + path}]}
        if path in ('throw/explode',):
            entry['sounds'][0]['attenuation_distance'] = 32
        entries[key] = entry
    # Gunfire, bullets, impacts, casings and handling (tools/art/gunaudio.py). Each event lists its
    # variants; the client picks one at random per play. GunAudio.java sets the level per listener.
    import soundfile
    for key, variants, attenuation in gunaudio.catalogue():
        listed = []
        for path, data, sr in variants:
            out = os.path.join(CV, 'sounds', path + '.ogg')
            os.makedirs(os.path.dirname(out), exist_ok=True)
            soundfile.write(out, np.clip(gunaudio.resample(data, sr), -1, 1).astype(np.float32), sr, format='OGG', subtype='VORBIS')
            listed.append({'name': 'craftov:' + path, 'attenuation_distance': attenuation})
        entries[key] = {'sounds': listed}
    write_json(os.path.join(CV, 'sounds.json'), entries)


def build_pack_meta():
    write_text(os.path.join(PACK, 'pack.yml'),
               'enable: true\nnamespace: craftov\nauthor: Snowball_233\nversion: "1.0.0"\n'
               'description: "Craftov extraction shooter: original weapons, gear, loot, UI and sounds"\n')


# ---------------------------------------------------------------- BetterHud

HUD_IMAGES = {
    'craftov/panel_left.png': lambda: hudart.panel(168, 46),
    'craftov/panel_right.png': lambda: hudart.panel(150, 47, side='right'),
    'craftov/panel_info.png': lambda: hudart.panel(168, 46),
    'craftov/hp_empty.png': lambda: hudart.bar(110, 6, (0, 0, 0, 0), empty=True),
    'craftov/hp_bar.png': lambda: hudart.bar(110, 6, rgb('#5BD16B')),
    'craftov/armor_empty.png': lambda: hudart.bar(52, 3, (0, 0, 0, 0), empty=True),
    'craftov/armor_bar.png': lambda: hudart.bar(52, 3, rgb('#4FA3FF')),
    'craftov/helmet_bar.png': lambda: hudart.bar(52, 3, rgb('#C8CCD2')),
    'craftov/reload_bar.png': lambda: hudart.bar(132, 2, rgb('#F2C94C')),
    'craftov/progress_empty.png': lambda: hudart.bar(120, 5, (0, 0, 0, 0), empty=True),
    'craftov/extract_bar.png': lambda: hudart.bar(120, 5, rgb('#5BD16B')),
    'craftov/downed_bar.png': lambda: hudart.bar(120, 5, rgb('#FF4D4D')),
    'craftov/med_bar.png': lambda: hudart.bar(120, 5, rgb('#7FE0D0')),
    'craftov/stamina_empty.png': lambda: hudart.bar(110, 2, (0, 0, 0, 0), empty=True),
    'craftov/stamina_bar.png': lambda: hudart.bar(110, 2, rgb('#F2C94C')),
    'craftov/hit.png': lambda: hudart.marker((240, 240, 240, 255)),
    'craftov/hit_head.png': lambda: hudart.marker(rgb('#FFC23D')),
    'craftov/hit_kill.png': lambda: hudart.marker(rgb('#FF4D4D'), 17, 3, 3),
    **{'craftov/damage_%d.png' % i: (lambda i=i: hudart.damage_arc(i)) for i in range(8)},
    'craftov/compass/n.png': lambda: hudart.glyph('N', rgb('#FF6B5B')),
    'craftov/compass/s.png': lambda: hudart.glyph('S'),
    'craftov/compass/e.png': lambda: hudart.glyph('E'),
    'craftov/compass/w.png': lambda: hudart.glyph('W'),
    'craftov/compass/ne.png': lambda: hudart.glyph('NE', scale=1),
    'craftov/compass/nw.png': lambda: hudart.glyph('NW', scale=1),
    'craftov/compass/se.png': lambda: hudart.glyph('SE', scale=1),
    'craftov/compass/sw.png': lambda: hudart.glyph('SW', scale=1),
    'craftov/compass/chain.png': lambda: hudart.tick(),
    'craftov/compass/point.png': lambda: hudart.pointer(),
    'craftov/compass/extract_open.png': lambda: hudart.diamond(rgb('#5BD16B')),
    'craftov/compass/extract_closed.png': lambda: hudart.diamond(rgb('#8A8F98')),
}

IN_RAID = {'1': {'first': 'craftov_in_raid', 'second': 'true', 'operation': '=='}}


def cond(*pairs):
    out = {}
    for i, (name, value) in enumerate(pairs, 1):
        out[str(i)] = {'first': name, 'second': value, 'operation': '=='}
    return out


def text(pattern, x, y, scale=1.0, align='left', conditions=None):
    t = {'name': 'craftov_font', 'pattern': pattern, 'x': x, 'y': y, 'scale': scale, 'align': align, 'outline': True}
    if conditions:
        t['conditions'] = conditions
    return t


def image(name, x, y, conditions=None):
    i = {'name': name, 'x': x, 'y': y}
    if conditions:
        i['conditions'] = conditions
    return i


def numbered(items):
    return {str(i): v for i, v in enumerate(items, 1)}


def build_betterhud():
    if os.path.exists(BH):
        shutil.rmtree(BH)
    for rel, fn in HUD_IMAGES.items():
        save(fn(), os.path.join(BH, 'assets', rel))
    for part in hudart.BODY_PARTS:
        for state in range(len(hudart.BODY_STATES)):
            save(hudart.body_part(part, state), os.path.join(BH, 'assets', 'craftov', 'body', '%s_%d.png' % (part, state)))
    images = {
        'craftov_panel_left': {'type': 'single', 'file': 'craftov/panel_left.png'},
        'craftov_panel_right': {'type': 'single', 'file': 'craftov/panel_right.png'},
        'craftov_panel_info': {'type': 'single', 'file': 'craftov/panel_info.png'},
        'craftov_hp_empty': {'type': 'single', 'file': 'craftov/hp_empty.png'},
        'craftov_armor_empty': {'type': 'single', 'file': 'craftov/armor_empty.png'},
        'craftov_progress_empty': {'type': 'single', 'file': 'craftov/progress_empty.png'},
        'craftov_hit': {'type': 'single', 'file': 'craftov/hit.png'},
        'craftov_hit_head': {'type': 'single', 'file': 'craftov/hit_head.png'},
        'craftov_hit_kill': {'type': 'single', 'file': 'craftov/hit_kill.png'},
        **{'craftov_damage_%d' % i: {'type': 'single', 'file': 'craftov/damage_%d.png' % i} for i in range(8)},
    }

    def listener(file, value, max_value):
        return {'type': 'listener', 'file': file, 'split': 25, 'split-type': 'left',
                'setting': {'listener': {'class': 'placeholder', 'value': value, 'max': max_value}}}

    images['craftov_hp_bar'] = listener('craftov/hp_bar.png', 'craftov_hp', '100')
    images['craftov_armor_bar'] = listener('craftov/armor_bar.png', 'craftov_armor', '100')
    images['craftov_helmet_bar'] = listener('craftov/helmet_bar.png', 'craftov_helmet', '100')
    images['craftov_reload_bar'] = listener('craftov/reload_bar.png', 'craftov_reload', '100')
    images['craftov_extract_bar'] = listener('craftov/extract_bar.png', 'craftov_extract', '100')
    images['craftov_downed_bar'] = listener('craftov/downed_bar.png', 'craftov_downed', '100')
    images['craftov_med_bar'] = listener('craftov/med_bar.png', 'craftov_med_pct', '100')
    images['craftov_stamina_empty'] = {'type': 'single', 'file': 'craftov/stamina_empty.png'}
    images['craftov_stamina_bar'] = listener('craftov/stamina_bar.png', 'craftov_stamina', '100')
    for part in hudart.BODY_PARTS:
        for state in range(len(hudart.BODY_STATES)):
            images['craftov_bp_%s_%d' % (part, state)] = {'type': 'single', 'file': 'craftov/body/%s_%d.png' % (part, state)}
    write_text(os.path.join(BH, 'images', 'craftov.yml'), yaml.safe_dump(images, allow_unicode=True, sort_keys=False))

    # BetterHud only bundles unifont glyphs for the listed languages; without "china" every CJK character is tofu.
    write_text(os.path.join(BH, 'texts', 'craftov.yml'), yaml.safe_dump(
        {'craftov_font': {'merge-default-bitmap': True, 'use-unifont': True, 'include': ['china']}}, allow_unicode=True, sort_keys=False))

    # Text scale 1.0 renders 16 px tall glyphs, so 0.5 matches vanilla chat size. The font has no ◈ or ⏱ glyphs.
    layouts = {
        'craftov_vitals': {
            'images': numbered([image('craftov_panel_left', 0, 0)]
                               + [image('craftov_bp_%s_%d' % (part, state), 4, 3, cond(('craftov_part_' + part, state)))
                                  for part in hudart.BODY_PARTS for state in range(len(hudart.BODY_STATES))]
                               + [image('craftov_hp_empty', 30, 14), image('craftov_hp_bar', 30, 14),
                                  # Downed players have no stamina to manage, so the bar is hidden.
                                  image('craftov_stamina_empty', 30, 22, cond(('craftov_is_downed', 'false'))),
                                  image('craftov_stamina_bar', 30, 22, cond(('craftov_is_downed', 'false'))),
                                  image('craftov_armor_empty', 30, 35), image('craftov_helmet_bar', 30, 35),
                                  image('craftov_armor_empty', 88, 35), image('craftov_armor_bar', 88, 35)]),
            'texts': numbered([text('<#5BD16B>❤</#5BD16B> [craftov_hp]', 30, 3, 0.6),
                               text('<#FF8A80>[craftov_status]', 62, 4, 0.45),
                               text('<#C8CCD2>头盔 [craftov_helmet_class]级', 30, 26, 0.45),
                               text('<#4FA3FF>护甲 [craftov_armor_class]级', 88, 26, 0.45)]),
            'conditions': cond(('craftov_in_raid', 'true')),
        },
        'craftov_weapon': {
            'images': numbered([image('craftov_panel_right', 0, 0),
                                image('craftov_reload_bar', 9, 44, cond(('craftov_reloading', 'true')))]),
            'texts': numbered([text('<#E8E4D8>[craftov_weapon]', 142, 3, 0.5, 'right'),
                               text('<white>[craftov_ammo]</white> <#8A8F98>/ [craftov_reserve]', 142, 13, 0.75, 'right'),
                               text('<#F2C94C>[craftov_firemode] <#8A8F98>· [craftov_ammo_type]', 142, 27, 0.45, 'right'),
                               text('[craftov_weapon_hint]', 142, 35, 0.45, 'right')]),
            'conditions': cond(('craftov_has_gun', 'true')),
        },
        'craftov_skill': {
            'texts': numbered([text('<#FF9F43>[craftov_operator] <#8A8F98>· [craftov_skill]', 142, 0, 0.45, 'right')]),
            'conditions': cond(('craftov_in_raid', 'true')),
        },
        'craftov_info': {
            'images': numbered([image('craftov_panel_info', 0, 0)]),
            'texts': numbered([text('<#F2E6C8>[craftov_map] <#8A8F98>· [craftov_tier]', 8, 4, 0.5),
                               text('<#8A8F98>剩余 <white>[craftov_time]', 8, 15, 0.65),
                               text('<#8A8F98>价值 <#F2C94C>[craftov_value_short]', 92, 17, 0.5),
                               text('<#9AA0A6>撤离 [craftov_nearest]', 8, 33, 0.45)]),
            'conditions': cond(('craftov_in_raid', 'true')),
        },
        'craftov_extract': {
            'images': numbered([image('craftov_progress_empty', 0, 12), image('craftov_extract_bar', 0, 12)]),
            'texts': numbered([text('<#5BD16B>撤离中 <white>[craftov_extract_left]s', 60, 0, 0.5, 'center')]),
            'conditions': cond(('craftov_extracting', 'true')),
        },
        'craftov_downed': {
            'images': numbered([image('craftov_progress_empty', 0, 12), image('craftov_downed_bar', 0, 12)]),
            'texts': numbered([text('<#FF4D4D>倒地 · 等待队友救援', 60, 0, 0.5, 'center')]),
            'conditions': cond(('craftov_is_downed', 'true')),
        },
        'craftov_med': {
            'images': numbered([image('craftov_progress_empty', 0, 12), image('craftov_med_bar', 0, 12)]),
            'texts': numbered([text('<#7FE0D0>[craftov_med_name] <white>[craftov_med_left]s', 60, 0, 0.5, 'center')]),
            'conditions': cond(('craftov_using_med', 'true')),
        },
        'craftov_lobby': {
            'images': numbered([image('craftov_panel_info', 0, 0)]),
            'texts': numbered([text('<#F2E6C8>Craftov · 大厅', 8, 4, 0.5),
                               text('<#8A8F98>块币 <#F2C94C>[craftov_money]', 8, 15, 0.65),
                               text('<#9AA0A6>战备值 [craftov_value_short] · 干员 [craftov_operator]', 8, 33, 0.45)]),
            'conditions': cond(('craftov_in_lobby', 'true')),
        },
        'craftov_hit_layout': {'images': numbered([image('craftov_hit', 0, 0)])},
        'craftov_hit_head_layout': {'images': numbered([image('craftov_hit_head', 0, 0)])},
        'craftov_hit_kill_layout': {'images': numbered([image('craftov_hit_kill', -1, -1)])},
        **{'craftov_damage_%d_layout' % i: {'images': numbered([image('craftov_damage_%d' % i, 0, 0)])} for i in range(8)},
    }
    # BetterHud centres a layout on its anchor by default; every position above is measured from the top-left corner.
    for layout in layouts.values():
        layout['offset'] = 'left'
    write_text(os.path.join(BH, 'layouts', 'craftov.yml'), yaml.safe_dump(layouts, allow_unicode=True, sort_keys=False))

    def place(name, gx, gy, px, py):
        return {'name': name, 'gui': {'x': gx, 'y': gy}, 'pixel': {'x': px, 'y': py}}

    huds = {'craftov_hud': {'layouts': numbered([
        place('craftov_vitals', 0, 100, 6, -66),
        place('craftov_weapon', 100, 100, -156, -69),
        place('craftov_skill', 100, 100, -156, -79),
        place('craftov_info', 0, 0, 6, 6),
        place('craftov_extract', 50, 50, -60, 26),
        place('craftov_downed', 50, 50, -60, 40),
        place('craftov_med', 50, 50, -60, 54),
        place('craftov_lobby', 0, 0, 6, 6),
    ])}}
    write_text(os.path.join(BH, 'huds', 'craftov.yml'), yaml.safe_dump(huds, allow_unicode=True, sort_keys=False))

    popups = {}
    for name, layout, offset in (('craftov_hit', 'craftov_hit_layout', -7), ('craftov_hit_head', 'craftov_hit_head_layout', -7),
                                 ('craftov_hit_kill', 'craftov_hit_kill_layout', -8)):
        popups[name] = {'duration': 4, 'unique': True, 'group': 'craftov_hits',
                        'layouts': numbered([{'name': layout, 'gui': {'x': 50, 'y': 50}, 'pixel': {'x': offset, 'y': offset}}])}
    # Hit direction: one popup per eighth so hits from several sides show at once (HudService.damageFrom).
    for i in range(8):
        popups['craftov_damage_%d' % i] = {'duration': 30, 'unique': True, 'group': 'craftov_damage_%d' % i,
                                            'layouts': numbered([{'name': 'craftov_damage_%d_layout' % i,
                                                                  'gui': {'x': 50, 'y': 50}, 'pixel': {'x': -48, 'y': -48}}])}
    write_text(os.path.join(BH, 'popups', 'craftov.yml'), yaml.safe_dump(popups, allow_unicode=True, sort_keys=False))

    compass = {'craftov_compass': {
        'type': 'circle', 'default': False,
        'file': {d: {'name': 'craftov/compass/' + d + '.png'} for d in ('n', 's', 'e', 'w', 'ne', 'nw', 'se', 'sw', 'chain')},
        'scale': 1.0, 'scale-equation': '1 - t/30', 'length': 20, 'space': 2,
        'gui': {'x': 50, 'y': 0}, 'pixel': {'x': 0, 'y': 6}, 'apply-opacity': True,
        'custom-icon': {'extract_open': {'name': 'craftov/compass/extract_open.png'},
                        'extract_closed': {'name': 'craftov/compass/extract_closed.png'}},
    }}
    compass['craftov_compass']['file']['point'] = {'name': 'craftov/compass/point.png', 'y': -6}
    compass['craftov_compass']['conditions'] = cond(('craftov_in_raid', 'true'))
    write_text(os.path.join(BH, 'compasses', 'craftov.yml'), yaml.safe_dump(compass, allow_unicode=True, sort_keys=False))


# Vanilla rendertype_lines.fsh (1.21.11) with one extra rule: the normal block-selection outline is drawn
# as black at alpha 102/255, and those fragments are dropped. Firing works by "mining" an invisible block
# in front of the crosshair, so an outline would otherwise float in front of every gun.
LINES_FSH = """#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    if (abs(vertexColor.a - 0.4) < 0.02 && max(vertexColor.r, max(vertexColor.g, vertexColor.b)) < 0.02) {
        discard;
    }
    vec4 color = vertexColor * ColorModulator;
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
"""


def build_trigger_capture():
    """Assets for left-click firing: the capture block (cobweb) is invisible and silent when "mined".

    Also the vanilla sound overrides: the AI are zombies (patrols) and husks (elite) re-skinned as
    soldiers (soldiers.py), so their groans are muted and they hurt, die and walk like people.
    """
    save(Image.new('RGBA', (16, 16), (0, 0, 0, 0)), os.path.join(MC, 'textures', 'block', 'cobweb.png'))
    silence = {'replace': True, 'sounds': [{'name': 'craftov:misc/silence', 'volume': 0.01}]}
    hurt = {'replace': True, 'sounds': [{'name': 'minecraft:damage/hit%d' % i, 'pitch': 0.9} for i in (1, 2, 3)]}
    death = {'replace': True, 'sounds': [{'name': 'minecraft:damage/hit%d' % i, 'pitch': 0.75} for i in (1, 2, 3)]}
    step = {'replace': True, 'sounds': [{'name': 'minecraft:step/stone%d' % i, 'volume': 0.9} for i in range(1, 7)]}
    overrides = {'block.cobweb.hit': silence}
    for mob in ('zombie', 'husk'):
        overrides['entity.%s.ambient' % mob] = silence
        overrides['entity.%s.hurt' % mob] = hurt
        overrides['entity.%s.death' % mob] = death
        overrides['entity.%s.step' % mob] = step
    write_json(os.path.join(MC, 'sounds.json'), overrides)
    save(soldiers.patrol(), os.path.join(MC, 'textures', 'entity', 'zombie', 'zombie.png'))
    save(soldiers.elite(), os.path.join(MC, 'textures', 'entity', 'zombie', 'husk.png'))
    write_text(os.path.join(MC, 'shaders', 'core', 'rendertype_lines.fsh'), LINES_FSH)


def main():
    with open(os.path.join(RES, 'items.yml'), encoding='utf-8') as f:
        item_defs = yaml.safe_load(f)['items']
    if os.path.exists(PACK):
        shutil.rmtree(PACK)
    build_pack_meta()
    build_items(item_defs)
    build_containers()
    build_fx()
    build_ui()
    build_skins()
    build_vanilla_hud()
    build_sounds()
    build_trigger_capture()
    build_betterhud()
    count = sum(len(files) for _, _, files in os.walk(PACK))
    print(f'pack files: {count}, betterhud files: {sum(len(f) for _, _, f in os.walk(BH))}')


if __name__ == '__main__':
    main()
