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

import yaml
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
import hudart  # noqa: E402
import icons  # noqa: E402
import models  # noqa: E402
import skins  # noqa: E402
import sounds  # noqa: E402
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
            write_json(os.path.join(CV, 'models', 'item', 'gun_ai', item_id + '.json'), models.gun_ai_model(item_id))
            write_json(os.path.join(CV, 'items', 'ai_' + item_id + '.json'), {'model': model_ref('craftov:item/gun_ai/' + item_id)})
            held = 'craftov:item/gun/' + item_id
        else:
            icon = icons.render(item_id)
            held = 'craftov:item/' + item_id
            write_json(os.path.join(CV, 'models', 'item', item_id + '.json'), generated('craftov:item/' + item_id))
        save(icon, os.path.join(CV, 'textures', 'item', item_id + '.png'))
        composite = bg.copy()
        composite.alpha_composite(icon)
        save(composite, os.path.join(CV, 'textures', 'item', 'gui', item_id + '.png'))
        write_json(os.path.join(CV, 'models', 'item', 'gui', item_id + '.json'), generated('craftov:item/gui/' + item_id))
        write_json(os.path.join(CV, 'items', item_id + '.json'), gui_select('craftov:item/gui/' + item_id, held))
    save(models.palette_texture(models.GUN_PALETTE), os.path.join(CV, 'textures', 'item', 'gun_palette.png'))
    save(models.palette_texture(models.BOX_PALETTE), os.path.join(CV, 'textures', 'item', 'box_palette.png'))


def build_containers():
    for cid in models.CONTAINERS:
        write_json(os.path.join(CV, 'models', 'item', 'container', cid + '.json'), models.container_model(cid))
        write_json(os.path.join(CV, 'items', 'container', cid + '.json'), {'model': model_ref('craftov:item/container/' + cid)})


def build_ui():
    for name, fn in ui.UI.items():
        img = fn().convert('RGBA')
        save(img, os.path.join(CV, 'textures', 'item', 'ui', name + '.png'))
        write_json(os.path.join(CV, 'models', 'item', 'ui', name + '.json'), generated('craftov:item/ui/' + name))
        write_json(os.path.join(CV, 'items', 'ui', name + '.json'), {'model': model_ref('craftov:item/ui/' + name)})
    save(ui.scope_overlay(), os.path.join(CV, 'textures', 'misc', 'scope.png'))


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
        if path.startswith('gun/') and path.split('/')[1] in ('pistol', 'pistol_heavy', 'smg', 'rifle', 'rifle_heavy', 'shotgun', 'dmr', 'sniper'):
            entry['sounds'][0]['attenuation_distance'] = 24
        if path in ('throw/explode',):
            entry['sounds'][0]['attenuation_distance'] = 32
        entries[key] = entry
    write_json(os.path.join(CV, 'sounds.json'), entries)


def build_pack_meta():
    write_text(os.path.join(PACK, 'pack.yml'),
               'enable: true\nnamespace: craftov\nauthor: Snowball_233\nversion: "1.0.0"\n'
               'description: "Craftov extraction shooter: original weapons, gear, loot, UI and sounds"\n')


# ---------------------------------------------------------------- BetterHud

HUD_IMAGES = {
    'craftov/panel_left.png': lambda: hudart.panel(132, 40),
    'craftov/panel_right.png': lambda: hudart.panel(150, 40, side='right'),
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
    'craftov/hit.png': lambda: hudart.marker((240, 240, 240, 255)),
    'craftov/hit_head.png': lambda: hudart.marker(rgb('#FFC23D')),
    'craftov/hit_kill.png': lambda: hudart.marker(rgb('#FF4D4D'), 17, 3, 3),
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
    write_text(os.path.join(BH, 'images', 'craftov.yml'), yaml.safe_dump(images, allow_unicode=True, sort_keys=False))

    write_text(os.path.join(BH, 'texts', 'craftov.yml'), yaml.safe_dump(
        {'craftov_font': {'merge-default-bitmap': True, 'use-unifont': True, 'include': []}}, allow_unicode=True, sort_keys=False))

    layouts = {
        'craftov_vitals': {
            'images': numbered([image('craftov_panel_left', 0, 0), image('craftov_hp_empty', 12, 20), image('craftov_hp_bar', 12, 20),
                                image('craftov_armor_empty', 12, 31), image('craftov_helmet_bar', 12, 31),
                                image('craftov_armor_empty', 70, 31), image('craftov_armor_bar', 70, 31)]),
            'texts': numbered([text('<#5BD16B>❤</#5BD16B> [craftov_hp]', 12, 6, 1.0),
                               text('<#FF8A80>[craftov_status]', 52, 7, 0.8),
                               text('<#C8CCD2>头盔 [craftov_helmet_class]级', 12, -4, 0.6),
                               text('<#4FA3FF>护甲 [craftov_armor_class]级', 70, -4, 0.6)]),
        },
        'craftov_weapon': {
            'images': numbered([image('craftov_panel_right', 0, 0),
                                image('craftov_reload_bar', 9, 37, cond(('craftov_reloading', 'true')))]),
            'texts': numbered([text('<#E8E4D8>[craftov_weapon]', 142, 5, 0.8, 'right'),
                               text('<white>[craftov_ammo]</white> <#8A8F98>/ [craftov_reserve]', 142, 16, 1.2, 'right'),
                               text('<#F2C94C>[craftov_firemode] <#8A8F98>· [craftov_ammo_type]', 142, 29, 0.6, 'right')]),
        },
        'craftov_skill': {
            'texts': numbered([text('<#FF9F43>[craftov_operator] <#8A8F98>· [craftov_skill]', 142, 0, 0.6, 'right')]),
        },
        'craftov_info': {
            'images': numbered([image('craftov_panel_info', 0, 0)]),
            'texts': numbered([text('<#F2E6C8>[craftov_map] <#8A8F98>· [craftov_tier]', 8, 5, 0.8),
                               text('<white>⏱ [craftov_time]', 8, 17, 1.0),
                               text('<#F2C94C>◈ [craftov_value_short]', 80, 18, 0.8),
                               text('<#9AA0A6>撤离 [craftov_nearest]', 8, 33, 0.6)]),
        },
        'craftov_extract': {
            'images': numbered([image('craftov_progress_empty', 0, 12), image('craftov_extract_bar', 0, 12)]),
            'texts': numbered([text('<#5BD16B>撤离中 <white>[craftov_extract_left]s', 60, 0, 0.8, 'center')]),
        },
        'craftov_downed': {
            'images': numbered([image('craftov_progress_empty', 0, 12), image('craftov_downed_bar', 0, 12)]),
            'texts': numbered([text('<#FF4D4D>倒地 · 等待队友潜行救援', 60, 0, 0.8, 'center')]),
        },
        'craftov_lobby': {
            'images': numbered([image('craftov_panel_info', 0, 0)]),
            'texts': numbered([text('<#F2E6C8>锈港行动 · 大厅', 8, 5, 0.8),
                               text('<#F2C94C>◈ [craftov_money]', 8, 17, 1.0),
                               text('<#9AA0A6>战备值 ◈[craftov_value_short] · 干员 [craftov_operator]', 8, 33, 0.6)]),
        },
        'craftov_hit_layout': {'images': numbered([image('craftov_hit', 0, 0)])},
        'craftov_hit_head_layout': {'images': numbered([image('craftov_hit_head', 0, 0)])},
        'craftov_hit_kill_layout': {'images': numbered([image('craftov_hit_kill', -1, -1)])},
    }
    write_text(os.path.join(BH, 'layouts', 'craftov.yml'), yaml.safe_dump(layouts, allow_unicode=True, sort_keys=False))

    def place(name, gx, gy, px, py, conditions=None):
        entry = {'name': name, 'gui': {'x': gx, 'y': gy}, 'pixel': {'x': px, 'y': py}}
        if conditions:
            entry['conditions'] = conditions
        return entry

    huds = {'craftov_hud': {'layouts': numbered([
        place('craftov_vitals', 0, 100, 6, -62, cond(('craftov_in_raid', 'true'))),
        place('craftov_weapon', 100, 100, -156, -62, cond(('craftov_in_raid', 'true'), ('craftov_has_gun', 'true'))),
        place('craftov_skill', 100, 100, -156, -72, cond(('craftov_in_raid', 'true'))),
        place('craftov_info', 0, 0, 6, 6, cond(('craftov_in_raid', 'true'))),
        place('craftov_extract', 50, 50, -60, 26, cond(('craftov_extracting', 'true'))),
        place('craftov_downed', 50, 50, -60, 40, cond(('craftov_is_downed', 'true'))),
        place('craftov_lobby', 0, 0, 6, 6, cond(('craftov_in_lobby', 'true'))),
    ])}}
    write_text(os.path.join(BH, 'huds', 'craftov.yml'), yaml.safe_dump(huds, allow_unicode=True, sort_keys=False))

    popups = {}
    for name, layout, offset in (('craftov_hit', 'craftov_hit_layout', -7), ('craftov_hit_head', 'craftov_hit_head_layout', -7),
                                 ('craftov_hit_kill', 'craftov_hit_kill_layout', -8)):
        popups[name] = {'duration': 4, 'unique': True, 'group': 'craftov_hits',
                        'layouts': numbered([{'name': layout, 'gui': {'x': 50, 'y': 50}, 'pixel': {'x': offset, 'y': offset}}])}
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


def main():
    with open(os.path.join(RES, 'items.yml'), encoding='utf-8') as f:
        item_defs = yaml.safe_load(f)['items']
    if os.path.exists(PACK):
        shutil.rmtree(PACK)
    build_pack_meta()
    build_items(item_defs)
    build_containers()
    build_ui()
    build_skins()
    build_vanilla_hud()
    build_sounds()
    build_betterhud()
    count = sum(len(files) for _, _, files in os.walk(PACK))
    print(f'pack files: {count}, betterhud files: {sum(len(f) for _, _, f in os.walk(BH))}')


if __name__ == '__main__':
    main()
