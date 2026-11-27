"""Builds the Craftov CraftEngine resource folder and BetterHud layout from scratch.

Usage: python3 tools/art/build.py   (from the repository root or anywhere)
Outputs:
  src/main/resources/pack/craftov/     -> installed to plugins/CraftEngine/resources/craftov/
  src/main/resources/betterhud/        -> installed to plugins/BetterHud/
"""
import json
import math
import os
import re
import shutil
import sys

import numpy as np
import yaml
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
import hudart  # noqa: E402
import icons  # noqa: E402
import knife_inspect  # noqa: E402
import knife_skins  # noqa: E402
import med_use  # noqa: E402
import models  # noqa: E402
import skins  # noqa: E402
import soldiers  # noqa: E402
import sounds  # noqa: E402
import audiocheck  # noqa: E402
import gunaudio  # noqa: E402
import gunsamples  # noqa: E402
import guiview  # noqa: E402
import hands  # noqa: E402
import oggenc  # noqa: E402
import palette  # noqa: E402
import pixelfont  # noqa: E402
import screens  # noqa: E402
import textfont  # noqa: E402
import ui  # noqa: E402
import px  # noqa: E402
from px import rgb  # noqa: E402

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
                      'cases': [{'when': ['gui'], 'model': gui_model}], 'fallback': model_ref(other_model)},
            'oversized_in_gui': True}


# GUI item models cover the whole 18x18 slot (#10): their textures are px.TILE (36) wide with the 32 px
# icon in the middle, and the model is scaled up so the icon keeps its 16 GUI px and the tile reaches the
# well's edges. The client clips a GUI item to 16x16 unless its definition says oversized_in_gui.
GUI_DISPLAY = {'gui': {'scale': [px.TILE_SCALE] * 3}}
# Badges sit a little nearer the eye than the item under them, so the two never fight for depth.
BADGE_DISPLAY = {'gui': {'scale': [px.TILE_SCALE] * 3, 'translation': [0, 0, 2]}}
BADGE_VALUE, BADGE_PLUS = 6, 7   # custom_model_data strings the stash sets on its display copies (StashMenu)


def gui_tile_model(texture, display=None):
    return {'parent': 'minecraft:item/generated', 'textures': {'layer0': texture}, 'display': display or GUI_DISPLAY}


def gui_case(item_id):
    """An item's look in a slot: its tile and icon, and the stash's corner badges over them."""
    return {'type': 'minecraft:composite', 'models': [
        model_ref('craftov:item/gui/' + item_id),
        {'type': 'minecraft:select', 'property': 'minecraft:custom_model_data', 'index': BADGE_VALUE,
         'cases': [{'when': 'v%d' % k, 'model': model_ref('craftov:item/badge/value_%d' % k)} for k in range(1, 5)],
         'fallback': {'type': 'minecraft:empty'}},
        {'type': 'minecraft:select', 'property': 'minecraft:custom_model_data', 'index': BADGE_PLUS,
         'cases': [{'when': 'plus', 'model': model_ref('craftov:item/badge/plus')}],
         'fallback': {'type': 'minecraft:empty'}}]}


def generated(texture, parent='minecraft:item/generated'):
    return {'parent': parent, 'textures': {'layer0': texture}}


ATT_SLOTS = ['muzzle', 'grip', 'tactical', 'mag']   # custom_model_data strings 1..4 (Attachments.java)
# Leaves that are the whole gun body: every attachment and the mounted optic (string 0) ride on them.
BODY_LEAVES = ('item/gun/', 'item/gun_low/', 'item/gun_mid/', 'item/gun_pose/', 'item/gun_ads/', 'item/gun_aimed/',
               'item/gun_lowready/', 'item/gun_adsin/', 'item/gun_draw/')


def attachments_for(spec, item_defs):
    """Attachment ids per slot that fit this gun (same rules as Attachments.gunClass/fits)."""
    gun = spec.get('gun') or {}
    cls = 'pistol' if gun.get('slot') == 'SECONDARY' else 'shotgun' if 'PUMP' in gun.get('modes', []) else 'rifle'
    out = {slot: [] for slot in ATT_SLOTS}
    for aid, aspec in item_defs.items():
        at = aspec.get('attach')
        if at and cls in at.get('mount', ['rifle']) and at.get('slot') in out:
            out[at['slot']].append(aid)
    return out


def wrap_attachments(node, gid, atts):
    """Adds the fitted attachments and the mounted optic to every gun leaf of an item model tree.

    Each is a small model whose parent is the leaf, so it inherits that pose's (or reload or draw
    frame's) display transforms; the leaf becomes a composite of itself plus one select per slot (the
    optic on custom_model_data string 0, the attachments on 1 to 4). A leaf made for one optic (its
    name ends in __<optic>: that sight's aimed pose) carries only that optic. Muzzle flashes disappear
    behind a suppressor.
    """
    if isinstance(node, list):
        return [wrap_attachments(v, gid, atts) for v in node]
    if not isinstance(node, dict):
        return node
    if node.get('type') != 'minecraft:model':
        return {k: wrap_attachments(v, gid, atts) for k, v in node.items()}
    ref = node['model']
    name = ref.split(':', 1)[1]
    if name.startswith('item/gun_flash/'):
        if 'att_suppressor' not in atts['muzzle']:
            return node
        return {'type': 'minecraft:select', 'property': 'minecraft:custom_model_data', 'index': 1,
                'cases': [{'when': 'att_suppressor', 'model': {'type': 'minecraft:empty'}}], 'fallback': node}
    # The aimed poses and the rise to them are made per sight: such a leaf carries its own optic (named
    # in it), or none for iron sights.
    optics = models.optics_for(gid)
    named = re.search(r'__(optic_[a-z0-9]+)', name)
    if named:
        optics = [named.group(1)]
    elif name.startswith(('item/gun_ads/', 'item/gun_adsin/', 'item/gun_pose/ads_')):
        optics = []
    if name.startswith('item/gun_reload/'):
        part = name.rsplit('/', 1)[1].rsplit('_', 1)[0]
        slots = ['optic', 'muzzle', 'grip', 'tactical'] if part == 'body' else ['mag'] if part == 'mag' else []
    elif name.startswith(BODY_LEAVES):
        slots = ['optic'] + ATT_SLOTS
    else:
        slots = []
    selects = []
    for slot in slots:
        cases = []
        for aid in (optics if slot == 'optic' else atts[slot]):
            path = 'gun_att/%s/%s' % (aid, name[len('item/'):].replace('/', '__'))
            out = os.path.join(CV, 'models', 'item', path + '.json')
            if not os.path.exists(out):
                write_json(out, models.optic_attachment_model(ref, gid, aid) if slot == 'optic'
                           else models.attachment_model(ref, gid, aid))
            cases.append({'when': aid, 'model': model_ref('craftov:item/' + path)})
        if cases:
            index = 0 if slot == 'optic' else ATT_SLOTS.index(slot) + 1
            selects.append({'type': 'minecraft:select', 'property': 'minecraft:custom_model_data',
                            'index': index, 'cases': cases, 'fallback': {'type': 'minecraft:empty'}})
    return {'type': 'minecraft:composite', 'models': [node] + selects} if selects else node


def by_optic(gid, make):
    """A select on the mounted optic (custom_model_data string 0) over make(optic id or None for irons)."""
    cases = [{'when': oid, 'model': make(oid)} for oid in models.optics_for(gid)]
    fallback = make(None)
    if not cases:
        return fallback
    return {'type': 'minecraft:select', 'property': 'minecraft:custom_model_data', 'index': 0, 'cases': cases,
            'fallback': fallback}


def build_gun(item_id, spec, item_defs):
    """A gun's models and item definition.

    The definition picks by display context: the inventory icon in the GUI; in first person a tree of
    the fine model in its poses, kicks, sights and the reload and draw frames (mid model); everywhere
    else (third person, the ground, item frames) the low model. In first person the stack's damage
    says what the gun is doing (ItemFactory.setHandMark), the one component the client swaps in
    without lowering the gun: 0 at rest (the cooldown is a shot's kick), 1 coming up after a switch
    (the cooldown runs the draw frames), 2 reloading (the reload frames), 3 the sights coming up (the
    rise frames), 4 aimed, 5 put away (the draw's first frame, so a switch shows the gun low at once).
    The mounted optic and the attachments are composite members of every leaf (wrap_attachments);
    only the aimed poses and the rise to them differ per optic.
    """
    gun_spec = spec.get('gun') or {}
    scoped = bool(gun_spec.get('scope'))
    fine = models.gun_model(item_id)
    # The gloved hands ride in the first-person bodies (models.hand_elements): the right fist on the
    # grip and the left hand on the handguard in the fine model (rest, poses, kicks, sights) and the mid
    # one (draw frames), the right fist alone in the reload's body part, whose frames put the left
    # hand on the magazine (models.reload_hand). The low model, which everyone else sees, has none.
    write_json(os.path.join(CV, 'models', 'item', 'gun', item_id + '.json'), models.with_hands(fine, models.hand_elements(item_id)))
    skin = models.gun_skin_image(item_id)
    if skin is not None:
        save(skin, os.path.join(CV, 'textures', 'item', 'gun', item_id + '_skin.png'))
    held = 'craftov:item/gun/' + item_id
    # Aimed, the eye is down on the stock right over the grip: a long gun's right fist would fill the
    # view (and is below the screen anyway), so the sights coming up and the aimed poses use a body with
    # the left hand alone. A pistol, held out at arm's length, keeps both fists.
    aim_held = held
    if item_id not in models.PISTOLS:
        aim_held = 'craftov:item/gun_aimhands/' + item_id
        write_json(os.path.join(CV, 'models', 'item', 'gun_aimhands', item_id + '.json'),
                   models.with_hands(fine, models.fore_hand(item_id)))
    if models.has_lods(item_id):
        write_json(os.path.join(CV, 'models', 'item', 'gun_low', item_id + '.json'), models.gun_low_model(item_id))
        write_json(os.path.join(CV, 'models', 'item', 'gun_mid', item_id + '.json'),
                   models.with_hands(models.gun_mid_model(item_id), models.hand_elements(item_id)))
        for part in models.voxel_gun(item_id)['mid']:
            mid = models.gun_mid_model(item_id, part)
            write_json(os.path.join(CV, 'models', 'item', 'gun_mid', '%s_%s.json' % (item_id, part)),
                       models.with_hands(mid, models.grip_hand(item_id)) if part == 'body' else mid)
    else:
        for part in models.gun_parts(item_id):
            write_json(os.path.join(CV, 'models', 'item', 'gun_part', item_id + '_' + part + '.json'),
                       models.gun_part_model(item_id, part))
    write_json(os.path.join(CV, 'models', 'item', 'gun_ai', item_id + '.json'), models.gun_ai_model(item_id))
    write_json(os.path.join(CV, 'items', 'ai_' + item_id + '.json'), {'model': model_ref('craftov:item/gun_ai/' + item_id)})

    kicks = models.kick_frames(gun_spec.get('rpm', 600))
    strength = models.kick_strength(gun_spec.get('recoil', 1.0))
    hip_display = fine['display']

    def key(k, on, off):
        return {'type': 'minecraft:condition', 'property': 'minecraft:keybind_down', 'keybind': k,
                'on_true': on, 'on_false': off}

    def leaf(path, model):
        write_json(os.path.join(CV, 'models', 'item', path + '.json'), model)
        return model_ref('craftov:item/' + path)

    def pose(kind, name, base, display, pose_name, pose_spec):
        return leaf('gun_pose/%s_%s/%s' % (kind, pose_name, name), models.pose_model(base, display, pose_spec))

    def flash(kind, name, base, display, pose_spec):
        fire = models.pose_model(base, display, pose_spec)['display']
        return leaf('gun_flash/%s/%s' % (kind, name),
                    models.flash_model(item_id, fire['firstperson_righthand'], fire['firstperson_lefthand']))

    def strafe(base, poses):
        return key('key.left', poses['left'], key('key.right', poses['right'], base))

    def kicked(kind, name, base, display, rest, power=1.0):
        # Shots are not a key: the server starts the gun's cooldown on every shot it really fires
        # (WeaponService.shoot), and the gun kicks back and flashes while the client counts it down.
        entries = []
        for threshold, level, lit in kicks:
            kick = models.kick_pose(kind, models.KICK_LEVELS[level], strength, power)
            model = pose(kind, name, base, display, 'kick%d' % level, kick)
            if lit:
                model = {'type': 'minecraft:composite', 'models': [model, flash(kind, name, base, display, kick)]}
            entries.append({'threshold': threshold, 'model': model})
        return {'type': 'minecraft:range_dispatch', 'property': 'minecraft:cooldown', 'entries': entries, 'fallback': rest}

    # At the hip: sprinting carries the gun low and canted, strafing leans it (held keys, no packets).
    hp = {n: pose('hip', item_id, held, hip_display, n, sp) for n, sp in models.HIP_POSES.items()}
    hip_idle = strafe(model_ref(held), hp)
    # Left mouse held on a sprint fires from the hip (run-and-gun): the gun comes up out of the sprint
    # carry while the trigger is down, so a spray kicks and flashes from one pose instead of dropping
    # back to the carry between rounds.
    sprint = key('key.attack', hip_idle, hp['sprint'])
    # The carry only on a real sprint: forward and sprint held, and neither crouch (which stops a sprint)
    # nor back (forward and back cancel out, and nobody runs).
    running = key('key.sneak', hip_idle, key('key.back', hip_idle, sprint))
    hip_rest = key('key.forward', key('key.sprint', running, hip_idle), hip_idle)
    hip_tree = kicked('hip', item_id, held, hip_display, hip_rest)

    # Aimed, per sight: iron sights and 1x sights show the gun aimed (and kick), a scope its mask.
    def aimed(oid):
        if oid is None and scoped:
            return {'type': 'minecraft:composite', 'models': [
                model_ref('craftov:item/gun_ads/' + item_id), model_ref('craftov:item/scope_mask/' + gun_spec['scope'])]}
        if oid is not None and oid in models.MASK_HOLE:
            return model_ref('craftov:item/scope_mask/' + oid)
        name = item_id if oid is None else item_id + '__' + oid
        display = models.ads_display(item_id, oid)
        ads = leaf('gun_ads/' + name, {'parent': aim_held, 'display': display})
        ads_ref = 'craftov:item/gun_ads/' + name
        ap = {n: pose('ads', name, ads_ref, display, n, sp) for n, sp in models.ADS_POSES.items()}
        return kicked('ads', name, ads_ref, display, strafe(ads, ap), models.sight_power(gun_spec, oid, item_defs))
    if scoped:
        leaf('gun_ads/' + item_id, models.gun_ads_model(item_id, True))

    # The sights coming up: the gun eases from the hip to the aimed pose (a scoped gun to its eyepiece)
    # as the server's ads cooldown counts down. Without the cooldown it is at the hip.
    def rise(oid):
        name = item_id if oid is None else item_id + '__' + oid
        if oid is None:
            target = models.gun_rise_display(item_id) if scoped else models.ads_display(item_id)
        else:
            target = models.optic_rise_display(item_id, oid)
        entries = []
        for k, (threshold, progress) in enumerate(models.ads_rise_frames()):
            ref = leaf('gun_adsin/%s/%d' % (name, k), {'parent': aim_held, 'display': {
                side: models.lerp_display(hip_display[side], target[side], progress)
                for side in ('firstperson_righthand', 'firstperson_lefthand')}})
            entries.append({'threshold': threshold, 'model': ref})
        return {'type': 'minecraft:range_dispatch', 'property': 'minecraft:cooldown', 'entries': entries,
                'fallback': entries[0]['model']}

    # Reloading: one frame a tick at most of the cooldown the client counts for the gun's reload, each a
    # composite of its moving parts.
    reload_entries = []
    reload_ticks = models.reload_cooldown(gun_spec.get('reload', 48))
    for k, (threshold, parts, hand) in enumerate(models.reload_timeline(item_id, reload_ticks)):
        members = []
        for part, (parent, right) in parts.items():
            members.append(leaf('gun_reload/%s/%s_%d' % (item_id, part, k), models.frame_model(parent, right)))
        if hand is not None:
            members.append(leaf('gun_reload/%s/hand_%d' % (item_id, k), models.reload_hand_model(*hand)))
        reload_entries.append({'threshold': threshold, 'model': {'type': 'minecraft:composite', 'models': members}})
    reload_tree = {'type': 'minecraft:range_dispatch', 'property': 'minecraft:cooldown', 'entries': reload_entries,
                   'fallback': hip_rest}

    # Coming up after a switch: one frame a tick of the draw time (models.draw_frames).
    draw_entries = []
    for k, (threshold, right, left) in enumerate(models.draw_frames(item_id, models.draw_ticks(gun_spec))):
        ref = leaf('gun_draw/%s/%d' % (item_id, k), {'parent': models.mid_ref(item_id), 'display': {
            'firstperson_righthand': right, 'firstperson_lefthand': left}})
        draw_entries.append({'threshold': threshold, 'model': ref})
    draw_tree = {'type': 'minecraft:range_dispatch', 'property': 'minecraft:cooldown', 'entries': draw_entries,
                 'fallback': model_ref(held)}
    holstered = draw_entries[0]['model']

    first_person = {'type': 'minecraft:range_dispatch', 'property': 'minecraft:damage', 'normalize': False,
                    'entries': [{'threshold': 0.5, 'model': draw_tree}, {'threshold': 1.5, 'model': reload_tree},
                                {'threshold': 2.5, 'model': by_optic(item_id, rise)},
                                {'threshold': 3.5, 'model': by_optic(item_id, aimed)},
                                {'threshold': 4.5, 'model': holstered}],
                    'fallback': hip_tree}

    # Everyone else sees the low model, posed for the stance StanceService shows them: shouldered, the
    # gun comes as a charged crossbow (the two-handed aim; gun_aimed keeps the barrel straight); carried
    # low, it comes with custom_model_data flag 0 and a crossbow's use (gun_lowready holds it low and
    # level in the crossbow-charge pose). Without either (the ground, an item frame) it is the plain one.
    # Flag 1 says the holder is crouching, whose arms swing back with the body's lean: the _crouch
    # variants turn the gun back in the hand by as much.
    low = models.low_ref(item_id)

    def flag(index, on, off):
        return {'type': 'minecraft:condition', 'property': 'minecraft:custom_model_data', 'index': index,
                'on_true': on, 'on_false': off}

    def stances(folder, make):
        return flag(1, leaf('%s/%s_crouch' % (folder, item_id), make(item_id, low, True)),
                    leaf('%s/%s' % (folder, item_id), make(item_id, low)))
    others = {'type': 'minecraft:select', 'property': 'minecraft:charge_type',
              'cases': [{'when': 'arrow', 'model': stances('gun_aimed', models.gun_aimed_model)}],
              'fallback': flag(0, stances('gun_lowready', models.gun_lowready_model), model_ref(low))}

    atts = attachments_for(spec, item_defs)
    tree = {'type': 'minecraft:select', 'property': 'minecraft:display_context',
            'cases': [{'when': ['gui'], 'model': gui_case(item_id)},
                      {'when': ['firstperson_righthand', 'firstperson_lefthand'],
                       'model': wrap_attachments(first_person, item_id, atts)}],
            'fallback': wrap_attachments(others, item_id, atts)}
    # Gun trees are large (poses x sights x frames x attachments): written compact.
    # hand_animation_on_swap false: the client never lowers and raises the gun when its stack changes
    # (the magazine written back at the end of a reload or when it runs dry, a switch to it); the
    # model's own frames are the only motion (ItemInHandRenderer otherwise dips for every component
    # but damage).
    path = os.path.join(CV, 'items', item_id + '.json')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump({'model': tree, 'hand_animation_on_swap': False, 'oversized_in_gui': True}, f, separators=(',', ':'))


def build_items(item_defs):
    for item_id, spec in item_defs.items():
        rarity = spec.get('rarity', 'WHITE')
        bg = px.rarity_tile(rgb(RARITY_COLORS[rarity]), list(RARITY_COLORS).index(rarity))
        if item_id in models.GUNS:
            icon = models.gun_icon(item_id)
            held = 'craftov:item/gun/' + item_id
        elif item_id in models.OPTICS:
            icon = models.optic_icon(item_id)
            held = 'craftov:item/optic/' + item_id
            save(models.optic_skin_image(item_id), os.path.join(CV, 'textures', 'item', 'optic', item_id + '_skin.png'))
            if item_id in models.RETICLE:
                kind = models.RETICLE[item_id]['kind']
                save(models.reticle_image(kind), os.path.join(CV, 'textures', 'item', 'optic', 'reticle_' + kind + '.png'))
            write_json(os.path.join(CV, 'models', 'item', 'optic', item_id + '.json'), models.optic_item_model(item_id))
        else:
            icon = icons.render(item_id)
            held = 'craftov:item/' + item_id
            # Melee weapons are held like a sword; everything else like a flat item.
            if spec.get('category') == 'MELEE':
                write_json(os.path.join(CV, 'models', 'item', item_id + '.json'), knife_model('craftov:item/' + item_id))
            else:
                write_json(os.path.join(CV, 'models', 'item', item_id + '.json'), generated('craftov:item/' + item_id))
        save(icon, os.path.join(CV, 'textures', 'item', item_id + '.png'))
        composite = bg.copy()
        composite.alpha_composite(icon, (px.TILE_ICON, px.TILE_ICON))
        save(composite, os.path.join(CV, 'textures', 'item', 'gui', item_id + '.png'))
        write_json(os.path.join(CV, 'models', 'item', 'gui', item_id + '.json'), gui_tile_model('craftov:item/gui/' + item_id))
        if item_id in models.GUNS:
            build_gun(item_id, spec, item_defs)
        elif item_id in knife_inspect.KEYS:
            write_knife_item(item_id, item_id, item_id, held)
        elif item_id in med_use.ITEMS:
            write_med_item(item_id, spec, held)
        else:
            write_json(os.path.join(CV, 'items', item_id + '.json'), gui_select(gui_case(item_id), held))
    save(models.palette_texture(models.GUN_PALETTE), os.path.join(CV, 'textures', 'item', 'gun_palette.png'))
    save(hands.texture(), os.path.join(CV, 'textures', 'item', 'hands.png'))
    write_json(os.path.join(CV, 'models', 'item', 'gun_part', 'shell.json'), models.shell_model())
    save(models.palette_texture(models.BOX_PALETTE), os.path.join(CV, 'textures', 'item', 'box_palette.png'))


def knife_model(texture):
    """A knife's sprite held like a sword (minecraft:item/handheld), at rest in first person as
    knife_inspect.BASE has it: up enough for the gloved fist to show."""
    model = generated(texture, 'minecraft:item/handheld')
    model['display'] = {'firstperson_righthand': knife_inspect.BASE,
                        'firstperson_lefthand': models.reload.mirrored(knife_inspect.BASE)}
    return model


# How far a knife drops in first person after a cut or a miss, and comes up when drawn, as a share of
# vanilla's (the item definition's swap_animation_scale). The client lowers the item in hand after every
# attack and raises it again over the attack interval (Player.resetAttackStrengthTicker): a knife,
# held up enough for the gloved fist to show, dropped out of view at full scale and its 8-tick stab
# (the STAB swing, which tips it forward at the crosshair) was never seen.
KNIFE_SWAP_SCALE = 0.25


def write_knife_item(name, frames_name, knife, held):
    """The item definition craftov:<name> of a knife, or of a skin of one (#106): its icon in menus
    (craftov:item/gui/<name>), otherwise `held`, held like a sword. F with the knife in hand runs its
    craftov:inspect cooldown (InspectService), and the client steps through the knife's inspect frames
    (knife_inspect/<frames_name>/<k>, each `held` in that frame's pose) while it counts down, as for the
    gun reload."""
    # In first person the gloved fist holds it, and a sleeve runs back from it (models.knife_hand_frames):
    # one fist and one sleeve model per knife, and per inspect frame a child of each with that frame's
    # displays, drawn with the frame.
    glove, sleeve = 'knife_hand/%s_glove' % knife, 'knife_hand/sleeve'
    write_json(os.path.join(CV, 'models', 'item', glove + '.json'), models.knife_glove_model(knife))
    write_json(os.path.join(CV, 'models', 'item', sleeve + '.json'), models.knife_sleeve_model())
    hand_frames = models.knife_hand_frames(knife)

    def gloved(ref, k):
        fist, arm = hand_frames[k]
        refs = []
        for part, parent, display in (('glove', glove, fist), ('sleeve', sleeve, arm)):
            path = 'knife_hand/%s/%s_%d' % (frames_name, part, k)
            write_json(os.path.join(CV, 'models', 'item', path + '.json'), models.frame_model('craftov:item/' + parent, display))
            refs.append(model_ref('craftov:item/' + path))
        return {'type': 'minecraft:composite', 'models': [model_ref(ref)] + refs}
    entries = []
    for k, (right, threshold) in enumerate(zip(knife_inspect.frames(knife), knife_inspect.thresholds())):
        path = 'knife_inspect/%s/%d' % (frames_name, k)
        write_json(os.path.join(CV, 'models', 'item', path + '.json'), models.frame_model(held, right))
        entries.append({'threshold': threshold, 'model': gloved('craftov:item/' + path, k + 1)})
    write_json(os.path.join(CV, 'items', name + '.json'), {'model': {
        'type': 'minecraft:select', 'property': 'minecraft:display_context',
        'cases': [{'when': ['gui'], 'model': gui_case(name)},
                  {'when': ['firstperson_righthand', 'firstperson_lefthand'],
                   'model': {'type': 'minecraft:range_dispatch', 'property': 'minecraft:cooldown',
                             'entries': entries, 'fallback': gloved(held, 0)}}],
        'fallback': model_ref(held)}, 'oversized_in_gui': True, 'swap_animation_scale': KNIFE_SWAP_SCALE})


def write_med_item(item_id, spec, held):
    """The item definition of a medical item (#41): its icon in menus, otherwise `held`, and while it
    is being used the frames of its motion (med_use.py), picked by how far through its use time
    (items.yml med.use) the holder is, as the vanilla bow picks its pull."""
    seconds = spec['med']['use']
    entries = []
    for k, (threshold, fp, tp) in enumerate(med_use.frames(item_id, seconds)):
        path = 'med_use/%s/%d' % (item_id, k)
        write_json(os.path.join(CV, 'models', 'item', path + '.json'), med_use.frame_model(held, fp, tp))
        entries.append({'threshold': threshold, 'model': model_ref('craftov:item/' + path)})
    write_json(os.path.join(CV, 'items', item_id + '.json'), {'model': {
        'type': 'minecraft:select', 'property': 'minecraft:display_context',
        'cases': [{'when': ['gui'], 'model': gui_case(item_id)}],
        'fallback': {'type': 'minecraft:condition', 'property': 'minecraft:using_item',
                     'on_true': {'type': 'minecraft:range_dispatch', 'property': 'minecraft:use_duration',
                                 'scale': round(1.0 / max(1, round(seconds * 20)), 6),
                                 'entries': entries, 'fallback': model_ref(held)},
                     'on_false': model_ref(held)}}, 'oversized_in_gui': True})


def load_knife_skins():
    """skins.yml (the plugin's catalogue of knife skins), checked against the art in knife_skins.py."""
    with open(os.path.join(RES, 'skins.yml'), encoding='utf-8') as f:
        catalogue = yaml.safe_load(f)['skins']
    missing = sorted(set(catalogue) - set(knife_skins.SKINS))
    unused = sorted(set(knife_skins.SKINS) - set(catalogue))
    assert not missing, 'skins.yml lists skins that knife_skins.py does not draw: %s' % missing
    assert not unused, 'knife_skins.py draws skins that skins.yml does not list: %s' % unused
    for sid, spec in catalogue.items():
        assert spec['knife'] == knife_skins.knife_of(sid), '%s: skins.yml says %s, knife_skins.py draws %s' % (
            sid, spec['knife'], knife_skins.knife_of(sid))
    return catalogue


def build_knife_skins(item_defs):
    """Knife skins (#106). SkinService puts the item model craftov:knife_skin/<id> on the knife in the
    melee slot: a definition shaped like the knife's own, with the skin's sprite held, in menus (on the
    knife's rarity background) and in every inspect frame."""
    for sid, spec in load_knife_skins().items():
        knife = spec['knife']
        name = 'knife_skin/' + sid
        icon = knife_skins.render(sid)
        save(icon, os.path.join(CV, 'textures', 'item', name + '.png'))
        write_json(os.path.join(CV, 'models', 'item', name + '.json'),
                   knife_model('craftov:item/' + name))
        rarity = item_defs[knife].get('rarity', 'WHITE')
        composite = px.rarity_tile(rgb(RARITY_COLORS[rarity]), list(RARITY_COLORS).index(rarity))
        composite.alpha_composite(icon, (px.TILE_ICON, px.TILE_ICON))
        save(composite, os.path.join(CV, 'textures', 'item', 'gui', name + '.png'))
        write_json(os.path.join(CV, 'models', 'item', 'gui', name + '.json'), gui_tile_model('craftov:item/gui/' + name))
        write_knife_item(name, sid, knife, 'craftov:item/' + name)


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
    """Muzzle flash, tactical light, drone and bullet-hole decals.

    The flash is a still picture: the gun's item model shows it for the first tick of each shot the
    server fires (models.kick_frames), so it no longer needs to flicker on its own.
    """
    for kind in ('side', 'front'):
        save(flash_frame(kind, 1.0, 1), os.path.join(CV, 'textures', 'item', 'fx', 'muzzle_%s.png' % kind))
    # Tactical light (TacticalLight.java): a glowing spot laid on the lit surface and a lens flare at the
    # muzzle for onlookers, both full-bright item displays, double-sided planes at z=8.
    import numpy as np
    n = 64
    yy, xx = np.mgrid[0:n, 0:n] / (n - 1) * 2 - 1
    r = np.hypot(xx, yy)
    spot = np.clip(1 - r, 0, 1) ** 1.6
    flare = np.clip(1 - r / 0.35, 0, 1) ** 1.2 + 0.55 * np.clip(1 - np.abs(yy) / 0.06, 0, 1) * np.clip(1 - np.abs(xx), 0, 1) ** 2 \
        + 0.35 * np.clip(1 - np.abs(xx) / 0.06, 0, 1) * np.clip(1 - np.abs(yy), 0, 1) ** 2 + 0.4 * np.clip(1 - r, 0, 1) ** 3
    for name, a, tint in (('light_spot', spot * 0.8, (255, 247, 220)), ('light_flare', np.clip(flare, 0, 1), (255, 250, 232))):
        rgba = np.zeros((n, n, 4), np.uint8)
        rgba[..., :3] = tint
        rgba[..., 3] = (a * 255).astype(np.uint8)
        save(Image.fromarray(rgba).resize((32, 32), Image.LANCZOS), os.path.join(CV, 'textures', 'item', 'fx', name + '.png'))
        face = {'uv': [0, 0, 16, 16], 'texture': '#t'}
        write_json(os.path.join(CV, 'models', 'item', 'fx', name + '.json'), {
            'textures': {'t': 'craftov:item/fx/' + name, 'particle': 'craftov:item/fx/' + name},
            'elements': [{'from': [0, 0, 8], 'to': [16, 16, 8], 'shade': False, 'faces': {'south': face, 'north': face}}]})
        write_json(os.path.join(CV, 'items', 'fx', name + '.json'), {'model': model_ref('craftov:item/fx/' + name)})
    # Recon drone (DroneService.java): a small quadcopter with spinning rotor discs.
    from PIL import ImageDraw
    frames = []
    for k in range(4):
        f = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
        d = ImageDraw.Draw(f)
        d.ellipse([0, 0, 15, 15], fill=(40, 42, 44, 70))
        a = math.pi / 4 * k
        for b in (a, a + math.pi):
            d.line([(7.5, 7.5), (7.5 + 7 * math.cos(b), 7.5 + 7 * math.sin(b))], fill=(30, 31, 33, 220), width=2)
        frames.append(f)
    save_animated(frames, os.path.join(CV, 'textures', 'item', 'fx', 'rotor.png'), frametime=1)
    body = Image.new('RGBA', (16, 16), (58, 61, 66, 255))
    ImageDraw.Draw(body).rectangle([0, 12, 15, 15], fill=(36, 38, 41, 255))
    save(body, os.path.join(CV, 'textures', 'item', 'fx', 'drone_body.png'))

    def cube(a, b, tex, rot=None, faces=('north', 'south', 'east', 'west', 'up', 'down')):
        e = {'from': a, 'to': b, 'faces': {f: {'uv': [0, 0, 16, 16], 'texture': tex} for f in faces}}
        if rot:
            e['rotation'] = {'angle': rot, 'axis': 'y', 'origin': [8, 8, 8]}
        return e
    drone = [cube([5.5, 7, 5.5], [10.5, 9, 10.5], '#b'),
             cube([1.5, 8.2, 7.4], [14.5, 8.8, 8.6], '#b', 45), cube([7.4, 8.2, 1.5], [8.6, 8.8, 14.5], '#b', 45),
             cube([7, 6.2, 9.8], [9, 7, 10.8], '#b'), cube([7.6, 6.4, 10.75], [8.4, 6.8, 10.85], '#l', None, ('south',))]
    for x, z in ((3.3, 3.3), (12.7, 3.3), (3.3, 12.7), (12.7, 12.7)):
        drone.append(cube([x - 0.4, 8.8, z - 0.4], [x + 0.4, 9.6, z + 0.4], '#b'))
        drone.append(cube([x - 2.8, 9.7, z - 2.8], [x + 2.8, 9.7, z + 2.8], '#r', None, ('up', 'down')))
    led = Image.new('RGBA', (16, 16), (230, 40, 30, 255))
    save(led, os.path.join(CV, 'textures', 'item', 'fx', 'drone_led.png'))
    write_json(os.path.join(CV, 'models', 'item', 'fx', 'drone.json'), {
        'textures': {'b': 'craftov:item/fx/drone_body', 'r': 'craftov:item/fx/rotor', 'l': 'craftov:item/fx/drone_led',
                     'particle': 'craftov:item/fx/drone_body'},
        'elements': drone})
    write_json(os.path.join(CV, 'items', 'fx', 'drone.json'), {'model': model_ref('craftov:item/fx/drone')})
    # The range's 50 m calibration target (RangeService): one face at z=8, seen from +Z, scaled by the display.
    save(ui.calibration_target(), os.path.join(CV, 'textures', 'item', 'fx', 'calibration.png'))
    write_json(os.path.join(CV, 'models', 'item', 'fx', 'calibration.json'), {
        'textures': {'t': 'craftov:item/fx/calibration', 'particle': 'craftov:item/fx/calibration'},
        'elements': [{'from': [0, 0, 8], 'to': [16, 16, 8], 'shade': False,
                      'faces': {'south': {'uv': [0, 0, 16, 16], 'texture': '#t'}, 'north': {'uv': [16, 0, 0, 16], 'texture': '#t'}}}]})
    write_json(os.path.join(CV, 'items', 'fx', 'calibration.json'), {'model': model_ref('craftov:item/fx/calibration')})
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
    # Slot-filling tiles (locked carry cells) and the stash's corner badges: 36 px, scaled over the well.
    for name, fn in ui.TILES.items():
        save(fn(), os.path.join(CV, 'textures', 'item', 'ui', name + '.png'))
        write_json(os.path.join(CV, 'models', 'item', 'ui', name + '.json'), gui_tile_model('craftov:item/ui/' + name))
        write_json(os.path.join(CV, 'items', 'ui', name + '.json'),
                   {'model': model_ref('craftov:item/ui/' + name), 'oversized_in_gui': True})
    for name, fn in ui.BADGES.items():
        save(fn(), os.path.join(CV, 'textures', 'item', 'badge', name + '.png'))
        write_json(os.path.join(CV, 'models', 'item', 'badge', name + '.json'),
                   gui_tile_model('craftov:item/badge/' + name, BADGE_DISPLAY))
    # The load gauge in the inventory's info cell: CarryInfo puts the frame in custom_model_data float 0.
    write_json(os.path.join(CV, 'items', 'ui', 'info_weight.json'), {'model': {
        'type': 'minecraft:range_dispatch', 'property': 'minecraft:custom_model_data', 'index': 0,
        'entries': [{'threshold': k, 'model': model_ref('craftov:item/ui/info_weight_%d' % k)} for k in range(ui.WEIGHT_LEVELS)],
        'fallback': model_ref('craftov:item/ui/info_weight_0')}})
    save(ui.scope_overlay('4x'), os.path.join(CV, 'textures', 'misc', 'scope.png'))
    save(models.mask_image(), os.path.join(CV, 'textures', 'item', 'scope', 'mask.png'))
    for oid in models.MASK_HOLE:
        write_json(os.path.join(CV, 'models', 'item', 'scope_mask', oid + '.json'), models.scope_mask_model(oid))
    save(ui.suppression_overlay(), os.path.join(CV, 'textures', 'misc', 'suppressed.png'))
    save(ui.hit_overlay(), os.path.join(CV, 'textures', 'misc', 'hit.png'))
    for level in range(1, len(ui.BLOOD_ALPHA) + 1):
        save(ui.blood_overlay(level), os.path.join(CV, 'textures', 'misc', 'blood_%d.png' % level))
    save(ui.strobe_overlay(), os.path.join(CV, 'textures', 'misc', 'strobe.png'))
    for level in range(1, len(ui.FLASH_ALPHA) + 1):
        save(ui.flash_overlay(level), os.path.join(CV, 'textures', 'misc', 'flash_%d.png' % level))
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


# craftov:ui font (gui/UiFont.java): its own key, so nothing clashes with the U+E000 glyphs CraftEngine
# puts in minecraft:default. Shift characters move the pen (negative 2^k at U+F800+k, positive at
# U+F810+k), screen backgrounds sit at U+E100 onwards, and the pixel font fills in the text.
SHIFT_NEG, SHIFT_POS, SCREEN_BASE = 0xF800, 0xF810, 0xE100


def build_screens():
    """Container skin, slot silhouettes, label lang strings and the craftov:ui font with the screens."""
    save(screens.generic_54(), os.path.join(MC, 'textures', 'gui', 'container', 'generic_54.png'))
    save(screens.inventory(), os.path.join(MC, 'textures', 'gui', 'container', 'inventory.png'))
    for kind in ('helmet', 'chestplate', 'leggings', 'boots'):
        save(screens.silhouette(kind), os.path.join(MC, 'textures', 'gui', 'sprites', 'container', 'slot', kind + '.png'))
    # The offhand is never used and the recipe book has nothing in it: neither shows (#10). The
    # recipe-book button is still there to click, so the inventory art keeps its spot clear.
    save(Image.new('RGBA', (16, 16), (0, 0, 0, 0)),
         os.path.join(MC, 'textures', 'gui', 'sprites', 'container', 'slot', 'shield.png'))
    for sprite in ('button', 'button_highlighted'):
        save(Image.new('RGBA', (20, 18), (0, 0, 0, 0)),
             os.path.join(MC, 'textures', 'gui', 'sprites', 'recipe_book', sprite + '.png'))
    for lang in ('zh_cn', 'zh_tw', 'zh_hk', 'en_us'):
        write_json(os.path.join(MC, 'lang', lang + '.json'), screens.LANG)
    ratios = screens.contrast_report()
    print('title / label contrast on the window body:', ratios)
    for name in ('TEXT', 'MUTED', 'ACCENT'):
        assert ratios[name] >= 4.5, 'menu titles and labels need 4.5:1 against the window (%s is %s)' % (name, ratios[name])
    advances = {' ': 4, '　': 11}
    for k in range(10):
        advances[chr(SHIFT_NEG + k)] = -(1 << k)
        advances[chr(SHIFT_POS + k)] = 1 << k
    providers = [{'type': 'space', 'advances': advances}]
    sheet, rows, _ = pixelfont.load()
    save(sheet, os.path.join(CV, 'textures', 'font', 'pixel10.png'))
    # Ascent 8 puts the glyphs' ink where vanilla's own 8 px text starts.
    providers.append({'type': 'bitmap', 'file': 'craftov:font/pixel10.png', 'height': pixelfont.CELL_H, 'ascent': 8,
                      'chars': rows})
    meta = {}
    # The skin screens go last: a screen's glyph is its place in this order, and the menus that are
    # older keep theirs.
    all_screens = {**screens.SCREENS, **screens.skin_screens(load_knife_skins())}
    for i, (sid, make) in enumerate(all_screens.items()):
        img, left, top = make()
        cp = chr(SCREEN_BASE + i)
        save(img, os.path.join(CV, 'textures', 'font', 'screen', sid + '.png'))
        # A menu title is drawn 6 px below the window top: ascent 13 puts the image's top row there.
        providers.append({'type': 'bitmap', 'file': 'craftov:font/screen/%s.png' % sid, 'height': img.height,
                          'ascent': 13 + top, 'chars': [cp]})
        box = img.getchannel('A').getbbox()
        # The client advances a bitmap glyph by its inked width plus one.
        meta[sid] = {'char': cp, 'advance': (box[2] if box else 0) + 1, 'left': left}
    # U+E0FF: the black transition curtain (UiFont.blackout). A title draws at 4x and centres its text,
    # so 64x16 at height 192 covers 3072x768 GUI px; ascent 93 centres it on the screen's middle.
    save(screens.blackout(), os.path.join(CV, 'textures', 'font', 'blackout.png'))
    providers.append({'type': 'bitmap', 'file': 'craftov:font/blackout.png', 'height': 192, 'ascent': 93, 'chars': ['']})
    # Anything the pixel font lacks (a rare character in a player-made name) falls back to the game's font.
    providers.append({'type': 'reference', 'id': 'minecraft:default'})
    write_json(os.path.join(CV, 'font', 'ui.json'), {'providers': providers})
    write_json(os.path.join(RES, 'ui', 'screens.json'), meta)


def build_fonts():
    """The text font (textfont.py, #163) in front of vanilla's in minecraft:default, so item names,
    lore, menu buttons, chat and on-screen titles are drawn from smooth outlines instead of Unifont's
    16 px bitmap (menu titles stay in craftov:ui's pixel font, matching the labels in the menu art).
    Its glyphs take vanilla's room (hanzi 9 px, digits 6, space 4), so nothing laid out for the
    vanilla font moves; whatever the face lacks falls through to vanilla's own providers, which the
    client keeps after these. CraftEngine merges this file with the glyphs it adds itself. Force
    Unicode Font switches to minecraft:uniform, which this leaves alone. The licence and provenance
    ship next to the font file."""
    os.makedirs(os.path.join(CV, 'font'), exist_ok=True)
    shutil.copyfile(textfont.OUT, os.path.join(CV, 'font', 'text.ttf'))
    with open(os.path.join(textfont.FONTS, 'SOURCE.txt'), encoding='utf-8') as f:
        source = f.read()
    with open(os.path.join(textfont.FONTS, 'OFL.txt'), encoding='utf-8') as f:
        licence = f.read()
    write_text(os.path.join(CV, 'font', 'text.license.txt'), source + '\n' + licence)
    write_json(os.path.join(MC, 'font', 'default.json'), {'providers': [textfont.provider()]})


def build_tooltips():
    """Tooltip styles (ItemFactory, UiItems): craftov:rarity_<rarity> frames an item's tooltip in its
    rarity colour, craftov:ui is the plain one for interface buttons. Same nine-slice sizes as vanilla."""
    styles = {'rarity_' + r.lower(): c for r, c in RARITY_COLORS.items()}
    styles['ui'] = '#3A424E'
    base = os.path.join(CV, 'textures', 'gui', 'sprites', 'tooltip')
    for name, color in styles.items():
        save(screens.tooltip_background(), os.path.join(base, name + '_background.png'))
        write_json(os.path.join(base, name + '_background.png.mcmeta'),
                   {'gui': {'scaling': {'type': 'nine_slice', 'width': 100, 'height': 100, 'border': 9}}})
        save(screens.tooltip_frame(color), os.path.join(base, name + '_frame.png'))
        write_json(os.path.join(base, name + '_frame.png.mcmeta'),
                   {'gui': {'scaling': {'type': 'nine_slice', 'width': 100, 'height': 100, 'border': 10, 'stretch_inner': True}}})


def build_skins():
    save(skins.soldier_skin(), os.path.join(MC, 'textures', 'entity', 'zombie', 'zombie.png'))
    for asset, fn in skins.EQUIPMENT.items():
        save(fn(), os.path.join(CV, 'textures', 'entity', 'equipment', 'humanoid', asset + '.png'))
        write_json(os.path.join(CV, 'equipment', asset + '.json'), {'layers': {'humanoid': [{'texture': 'craftov:' + asset}]}})


def build_vanilla_hud():
    """Hide vanilla hearts, hunger, armor, air, XP and the crosshair: Craftov draws its own HUD."""
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
    # The crosshair is BetterHud's (craftov_crosshair: an outlined dot whose arms open with the spread,
    # hidden while aiming, when the sights are the aim point); the vanilla one is cleared.
    save(hudart.vanilla_crosshair(), os.path.join(MC, 'textures', 'gui', 'sprites', 'hud', 'crosshair.png'))
    save(Image.new('RGBA', (182, 5), (0, 0, 0, 0)), os.path.join(MC, 'textures', 'gui', 'sprites', 'hud', 'experience_bar_progress.png'))


# Footsteps (FootstepService): the server plays craftov:step.<surface>.<kind> to each listener at its own
# loudness, so these only name the client's own step recordings (nothing is added to the pack). A run is
# the same steps a little heavier, a landing the same at a low pitch.
STEP_FILES = {'stone': ('step/stone', 6), 'wood': ('step/wood', 6), 'metal': ('block/iron/step', 4),
              'gravel': ('step/gravel', 4), 'grass': ('step/grass', 6), 'sand': ('step/sand', 5),
              'snow': ('step/snow', 4), 'cloth': ('step/cloth', 4)}
STEP_KINDS = {'walk': 1.0, 'run': 0.88, 'land': 0.7}


def build_sounds():
    entries = {}
    for path, fn in sounds.SOUNDS.items():
        sounds.write_ogg(fn(), os.path.join(CV, 'sounds', path + '.ogg'))
        key = path.replace('/', '.')
        entry = {'sounds': [{'name': 'craftov:' + path}]}
        if path in ('throw/explode',):
            entry['sounds'][0]['attenuation_distance'] = 32
        entries[key] = entry
    for surface, (stem, count) in STEP_FILES.items():
        for kind, pitch in STEP_KINDS.items():
            entries['step.%s.%s' % (surface, kind)] = {'sounds': [
                {'name': 'minecraft:%s%d' % (stem, i), 'pitch': pitch} for i in range(1, count + 1)]}
    # Bullets, impacts, casings and handling (tools/art/gunaudio.py). Each event lists its variants;
    # the client picks one at random per play.
    for key, variants, attenuation in gunaudio.catalogue():
        listed = []
        for path, data, sr in variants:
            oggenc.write(os.path.join(CV, 'sounds', path + '.ogg'), gunaudio.resample(data, sr), sr)
            listed.append({'name': 'craftov:' + path, 'attenuation_distance': attenuation})
        entries[key] = {'sounds': listed}
    # Gun reports, cut from CC0 recordings (tools/art/gunsamples.py). GunAudio.java picks the variant
    # (through the packet's seed) and sets the level per listener. Preloaded, so the first shot after
    # joining is not late while its file is read.
    reports, sources = gunsamples.catalogue()
    for key, variants, attenuation in reports:
        listed = []
        for path, data, sr in variants:
            oggenc.write(os.path.join(CV, 'sounds', path + '.ogg'), gunsamples.resample(data, gunsamples.SR, sr), sr)
            listed.append({'name': 'craftov:' + path, 'attenuation_distance': attenuation, 'preload': True})
        entries[key] = {'sounds': listed}
    write_json(os.path.join(CV, 'sounds.json'), entries)
    write_json(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'audio_sources.json'), gunsamples.source_manifest(sources))
    variants = gunsamples.variant_manifest(reports, sources)
    write_text(os.path.join(RES, 'gun_variants.json'),
               '{\n' + ',\n'.join(' %s: %s' % (json.dumps(k), json.dumps(v, separators=(',', ':'))) for k, v in variants.items()) + '\n}\n')
    for what, target, reached in gunsamples.SHORT:
        print('gunsamples: %s reaches %.1f dB of its %.1f dB loudness' % (what, reached, target))


def build_pack_meta():
    write_text(os.path.join(PACK, 'pack.yml'),
               'enable: true\nnamespace: craftov\nauthor: Snowball_233\nversion: "1.0.0"\n'
               'description: "Craftov extraction shooter: original weapons, gear, loot, UI and sounds"\n')


# ---------------------------------------------------------------- BetterHud
#
# HUD composition (#6, #66). Text comes in two fonts, each drawn so one font pixel is a whole number of
# GUI pixels, which keeps it sharp at every GUI scale: craftov_pixel, the 10 px pixel font (pixelfont.py)
# at scale 1.0 for every label and anything Chinese, and craftov_font, vanilla's ASCII bitmap, at 1.0
# (2 px per font pixel) for the big numbers. Unifont at the old 0.45-0.75 scales resampled each Chinese
# glyph to a fraction of its size, which is what blurred the HUD at GUI scale 3.
#
# Placement: the vitals sit in the bottom-left band below the chat (the chat's bottom line is 40 px above
# the screen edge) and stop short of the offhand slot even on a 426 px wide GUI (1280x720 at scale 3);
# the weapon panel sits bottom-right above the hotbar's height; info and the squad top-left, the kill
# feed top-right, alerts under the compass, prompts and progress under the crosshair.

TEXT, MUTED, DIM, ACCENT_HEX = palette.TEXT, palette.MUTED, palette.DIM, palette.ACCENT
OK_HEX, DANGER_HEX, INFO_HEX = palette.OK, palette.DANGER, palette.INFO

HUD_IMAGES = {
    'craftov/panel_vitals.png': lambda: hudart.panel(88, 38),
    'craftov/panel_weapon.png': lambda: hudart.panel(118, 38, side='right'),
    'craftov/panel_skill.png': lambda: hudart.panel(132, 12, accent=rgb(palette.ACCENT)),
    'craftov/panel_info.png': lambda: hudart.panel(132, 44),
    'craftov/panel_mate.png': lambda: hudart.panel(132, 12, accent=rgb(palette.LINE)),
    # The new-player guide's objective line: wide in the lobby, the squad rows' width in a raid, where
    # the kill feed's rows reach in from the right on a 320 px wide GUI.
    'craftov/panel_objective.png': lambda: hudart.panel(200, 12, accent=rgb(palette.ACCENT)),
    'craftov/alert_band.png': lambda: hudart.band(300, 14),
    # Behind the interaction prompt, which is amber or white text that vanished on a sunlit floor.
    'craftov/prompt_band.png': lambda: hudart.band(200, 12, accent=rgb(palette.LINE)),
    'craftov/hp_empty.png': lambda: hudart.bar(64, 3, (0, 0, 0, 0), empty=True),
    'craftov/hp_bar.png': lambda: hudart.bar(64, 3, rgb(palette.OK)),
    'craftov/armor_empty.png': lambda: hudart.bar(30, 2, (0, 0, 0, 0), empty=True),
    'craftov/armor_bar.png': lambda: hudart.bar(30, 2, rgb(palette.INFO)),
    'craftov/helmet_bar.png': lambda: hudart.bar(30, 2, rgb(palette.TEXT)),
    'craftov/reload_bar.png': lambda: hudart.bar(112, 2, rgb(palette.ACCENT)),
    # The gun coming up after a switch takes the reload bar's place, in grey.
    'craftov/draw_bar.png': lambda: hudart.bar(112, 2, rgb(palette.MUTED)),
    'craftov/progress_empty.png': lambda: hudart.bar(120, 3, (0, 0, 0, 0), empty=True),
    'craftov/extract_bar.png': lambda: hudart.bar(120, 3, rgb(palette.OK)),
    'craftov/downed_bar.png': lambda: hudart.bar(120, 3, rgb(palette.DANGER)),
    'craftov/med_bar.png': lambda: hudart.bar(120, 3, rgb('#7FC8D1')),
    'craftov/stamina_empty.png': lambda: hudart.bar(64, 1, (0, 0, 0, 0), empty=True),
    'craftov/stamina_bar.png': lambda: hudart.bar(64, 1, rgb(palette.ACCENT)),
    'craftov/stamina_bar_heavy.png': lambda: hudart.bar(64, 1, rgb(palette.DANGER)),
    **{'craftov/icon_%s.png' % k: (lambda k=k: hudart.icon(k)) for k in ('bleed', 'heavy', 'fracture', 'pain')},
    'craftov/hit.png': lambda: hudart.marker((240, 240, 240, 255)),
    'craftov/hit_head.png': lambda: hudart.marker(rgb('#FFC23D')),
    'craftov/hit_kill.png': lambda: hudart.marker(rgb('#FF4D4D'), 17, 3, 3),
    'craftov/hit_knock.png': lambda: hudart.knock_marker(rgb('#FF9A3C')),
    **{'craftov/damage_%d.png' % i: (lambda i=i: hudart.damage_arc(i)) for i in range(8)},
    **{'craftov/crosshair/%d.png' % i: (lambda g=g: hudart.crosshair(g)) for i, g in enumerate(hudart.CROSSHAIR_GAPS)},
    'craftov/crosshair/%d.png' % len(hudart.CROSSHAIR_GAPS): lambda: hudart.dot_crosshair(),
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
    'craftov/compass/objective.png': lambda: hudart.diamond(rgb(palette.ACCENT)),
}

IN_RAID = {'1': {'first': 'craftov_in_raid', 'second': 'true', 'operation': '=='}}
# Kill feed rows, KillFeedService.LINES of them.
FEED_LINES = (1, 2, 3, 4, 5)


def cond(*pairs):
    out = {}
    for i, (name, value) in enumerate(pairs, 1):
        out[str(i)] = {'first': name, 'second': value, 'operation': '=='}
    return out


def text(pattern, x, y, scale=1.0, align='left', conditions=None, font='craftov_pixel'):
    """A HUD text. Only scale 1.0 (and 0.5 for craftov_font) keeps font pixels whole on screen."""
    if font == 'craftov_pixel':
        y += pixelfont.ASCENT  # BetterHud puts a bitmap glyph's ascent row at y; this makes y the cell top
    t = {'name': font, 'pattern': pattern, 'x': x, 'y': y, 'scale': scale, 'align': align, 'outline': True}
    if conditions:
        t['conditions'] = conditions
    return t


def big(pattern, x, y, align='left', conditions=None):
    """Big ASCII digits: vanilla's 8 px bitmap at 2 GUI px per font pixel."""
    return text(pattern, x, y, 1.0, align, conditions, font='craftov_font')


def image(name, x, y, conditions=None):
    i = {'name': name, 'x': x, 'y': y}
    if conditions:
        i['conditions'] = conditions
    return i


def numbered(items):
    return {str(i): v for i, v in enumerate(items, 1)}


def c(hex_color):
    return '<' + hex_color + '>'


def hud_pixel_font():
    """The 10 px pixel font (pixelfont.py) as a BetterHud bitmap text font: one sheet of full rows plus
    the last, shorter row on its own, since every row of a sheet must hold the same number of glyphs.
    BetterHud draws a bitmap font at scale x cell height, so at scale 1.0 one font pixel is one GUI pixel."""
    sheet, rows, _ = pixelfont.load()
    full = [r for r in rows if '\0' not in r]
    last = rows[len(full):]
    chars = {}
    body = sheet.crop((0, 0, sheet.width, len(full) * pixelfont.CELL_H))
    save(body, os.path.join(BH, 'assets', 'craftov', 'pixel', '0.png'))
    chars['1'] = {'file': 'craftov/pixel/0.png', 'codepoints': full, 'ascent': pixelfont.ASCENT}
    for i, row in enumerate(last, 1):
        row = row.rstrip('\0')
        y = (len(full) + i - 1) * pixelfont.CELL_H
        save(sheet.crop((0, y, len(row) * pixelfont.CELL_W, y + pixelfont.CELL_H)),
             os.path.join(BH, 'assets', 'craftov', 'pixel', '%d.png' % i))
        chars[str(i + 1)] = {'file': 'craftov/pixel/%d.png' % i, 'codepoints': [row], 'ascent': pixelfont.ASCENT}
    return {'type': 'bitmap', 'chars': chars}


def build_betterhud():
    if os.path.exists(BH):
        shutil.rmtree(BH)
    for rel, fn in HUD_IMAGES.items():
        save(fn(), os.path.join(BH, 'assets', rel))
    for part in hudart.BODY_PARTS:
        for state in range(len(hudart.BODY_STATES)):
            save(hudart.body_part(part, state), os.path.join(BH, 'assets', 'craftov', 'body', '%s_%d.png' % (part, state)))
    singles = ['panel_vitals', 'panel_weapon', 'panel_info', 'panel_mate', 'panel_skill', 'panel_objective', 'alert_band', 'prompt_band', 'hp_empty', 'armor_empty',
               'progress_empty', 'stamina_empty', 'hit', 'hit_head', 'hit_kill', 'hit_knock',
               'icon_bleed', 'icon_heavy', 'icon_fracture', 'icon_pain'] + ['damage_%d' % i for i in range(8)]
    images = {'craftov_' + n: {'type': 'single', 'file': 'craftov/%s.png' % n} for n in singles}
    images.update({'craftov_crosshair_%d' % i: {'type': 'single', 'file': 'craftov/crosshair/%d.png' % i}
                   for i in range(len(hudart.CROSSHAIR_GAPS) + 1)})

    def listener(file, value, max_value):
        return {'type': 'listener', 'file': file, 'split': 32, 'split-type': 'left',
                'setting': {'listener': {'class': 'placeholder', 'value': value, 'max': max_value}}}

    images['craftov_hp_bar'] = listener('craftov/hp_bar.png', 'craftov_hp', '100')
    images['craftov_armor_bar'] = listener('craftov/armor_bar.png', 'craftov_armor', '100')
    images['craftov_helmet_bar'] = listener('craftov/helmet_bar.png', 'craftov_helmet', '100')
    images['craftov_reload_bar'] = listener('craftov/reload_bar.png', 'craftov_reload', '100')
    images['craftov_draw_bar'] = listener('craftov/draw_bar.png', 'craftov_draw', '100')
    images['craftov_extract_bar'] = listener('craftov/extract_bar.png', 'craftov_extract', '100')
    images['craftov_downed_bar'] = listener('craftov/downed_bar.png', 'craftov_downed', '100')
    images['craftov_med_bar'] = listener('craftov/med_bar.png', 'craftov_med_pct', '100')
    images['craftov_stamina_bar'] = listener('craftov/stamina_bar.png', 'craftov_stamina', '100')
    images['craftov_stamina_bar_heavy'] = listener('craftov/stamina_bar_heavy.png', 'craftov_stamina', '100')
    for part in hudart.BODY_PARTS:
        for state in range(len(hudart.BODY_STATES)):
            images['craftov_bp_%s_%d' % (part, state)] = {'type': 'single', 'file': 'craftov/body/%s_%d.png' % (part, state)}
    write_text(os.path.join(BH, 'images', 'craftov.yml'), yaml.safe_dump(images, allow_unicode=True, sort_keys=False))

    # craftov_font: vanilla's ASCII bitmap, used only at scale 1.0 for big digits. BetterHud bundles
    # unifont glyphs only for the listed languages, so "china" keeps a stray CJK character from being tofu.
    texts = {'craftov_font': {'merge-default-bitmap': True, 'use-unifont': True, 'include': ['china']},
             'craftov_pixel': hud_pixel_font()}
    write_text(os.path.join(BH, 'texts', 'craftov.yml'), yaml.safe_dump(texts, allow_unicode=True, sort_keys=False))

    hp_colour = [(OK_HEX, 0), (ACCENT_HEX, 1), (DANGER_HEX, 2)]
    time_colour = [(TEXT, 0), (ACCENT_HEX, 1), (DANGER_HEX, 2)]
    layouts = {
        'craftov_vitals': {
            'images': numbered([image('craftov_panel_vitals', 0, 0)]
                               + [image('craftov_bp_%s_%d' % (part, state), 3, 2, cond(('craftov_part_' + part, state)))
                                  for part in hudart.BODY_PARTS for state in range(len(hudart.BODY_STATES))]
                               + [image('craftov_hp_empty', 22, 13), image('craftov_hp_bar', 22, 13),
                                  # Downed players have no stamina to manage, so the bar is hidden; it turns
                                  # red while overloaded (no sprinting).
                                  image('craftov_stamina_empty', 22, 18, cond(('craftov_is_downed', 'false'))),
                                  image('craftov_stamina_bar', 22, 18, cond(('craftov_is_downed', 'false'), ('craftov_overloaded', 'false'))),
                                  image('craftov_stamina_bar_heavy', 22, 18, cond(('craftov_is_downed', 'false'), ('craftov_overloaded', 'true'))),
                                  image('craftov_armor_empty', 22, 34), image('craftov_helmet_bar', 22, 34),
                                  image('craftov_armor_empty', 56, 34), image('craftov_armor_bar', 56, 34),
                                  # Wound icons, one slot each: bleeding (heavy replaces light), fracture, painkiller.
                                  image('craftov_icon_bleed', 58, 2, cond(('craftov_inj_bleed', 'true'), ('craftov_inj_heavy', 'false'))),
                                  image('craftov_icon_heavy', 58, 2, cond(('craftov_inj_heavy', 'true'))),
                                  image('craftov_icon_fracture', 68, 2, cond(('craftov_inj_fracture', 'true'))),
                                  image('craftov_icon_pain', 78, 2, cond(('craftov_inj_pain', 'true')))]),
            'texts': numbered([text(c(col) + '❤ [craftov_hp]', 22, 1, conditions=cond(('craftov_hp_state', state)))
                               for col, state in hp_colour]
                              + [text(c(MUTED) + '盔' + c(TEXT) + '[craftov_helmet_class]', 22, 22),
                                 text(c(MUTED) + '甲' + c(INFO_HEX) + '[craftov_armor_class]', 56, 22)]),
            'conditions': cond(('craftov_in_raid', 'true')),
        },
        # Bottom-right band, the same 38 px under the chat line as the vitals: on a 426 px GUI the chat
        # reaches x 328, so anything higher up on the right would sit under it. The bottom line shows
        # fire mode and ammo, or the weapon hint (reloading, empty, [Q]) when there is one.
        'craftov_weapon': {
            'images': numbered([image('craftov_panel_weapon', 0, 0),
                                image('craftov_reload_bar', 2, 36, cond(('craftov_reloading', 'true'))),
                                image('craftov_draw_bar', 2, 36, cond(('craftov_drawing', 'true')))]),
            # Big magazine count (up to three digits, 36 px) at the left, reserve after it and the fire
            # mode at the right of the same row; the bottom row keeps clear of the digits.
            'texts': numbered([text(c(TEXT) + '[craftov_weapon]', 114, 0, align='right'),
                               big('<white>[craftov_ammo]', 40, 12, 'right', cond(('craftov_ammo_empty', 'false'))),
                               big(c(DANGER_HEX) + '[craftov_ammo]', 40, 12, 'right', cond(('craftov_ammo_empty', 'true'))),
                               text(c(MUTED) + '／[craftov_reserve]', 42, 15),
                               text(c(ACCENT_HEX) + '[craftov_firemode]', 114, 13, align='right'),
                               text(c(MUTED) + '[craftov_ammo_type]', 114, 25, align='right', conditions=cond(('craftov_has_hint', 'false'))),
                               text('[craftov_weapon_hint]', 114, 25, align='right', conditions=cond(('craftov_has_hint', 'true')))]),
            'conditions': cond(('craftov_has_gun', 'true')),
        },
        # Top left, under the raid info: the operator skill and its cooldown.
        'craftov_skill': {
            'images': numbered([image('craftov_panel_skill', 0, 0)]),
            'texts': numbered([text(c(ACCENT_HEX) + '[craftov_operator] ' + c(MUTED) + '· [craftov_skill]', 6, 0)]),
            'conditions': cond(('craftov_in_raid', 'true')),
        },
        'craftov_info': {
            'images': numbered([image('craftov_panel_info', 0, 0)]),
            'texts': numbered([text(c(MUTED) + '[craftov_map] · [craftov_tier]', 6, 1)]
                              + [big(c(col) + '[craftov_time]', 6, 13, conditions=cond(('craftov_time_state', state)))
                                 for col, state in time_colour]
                              + [text(c(ACCENT_HEX) + '◈ [craftov_value_short]', 72, 17),
                                 text(c(MUTED) + '撤离 ' + c(TEXT) + '[craftov_nearest]', 6, 31)]),
            'conditions': cond(('craftov_in_raid', 'true')),
        },
        **{'craftov_mate_%d' % i: {
            'images': numbered([image('craftov_panel_mate', 0, 0)]),
            'texts': numbered([text('[craftov_mate_%d]' % i, 6, 0)]),
            'conditions': cond(('craftov_in_raid', 'true'), ('craftov_has_mate_%d' % i, 'true')),
        } for i in (1, 2)},
        # Kill feed (KillFeedService): the last five lines, oldest on top, each for a few seconds, one
        # layout per line so an empty one takes no room.
        **{'craftov_feed_%d' % i: {
            'texts': numbered([text('[craftov_feed_%d]' % i, 0, 0, align='right')]),
            'conditions': cond(('craftov_has_feed_%d' % i, 'true')),
        } for i in FEED_LINES},
        # The new-player guide's goal (TutorialService): under the lobby panel, and in a raid under the
        # squad rows that are there (one layout per row count, so it never leaves a gap or covers one).
        'craftov_objective': {
            'images': numbered([image('craftov_panel_objective', 0, 0)]),
            'texts': numbered([text('[craftov_objective]', 6, 0)]),
            'conditions': cond(('craftov_in_lobby', 'true'), ('craftov_has_objective', 'true')),
        },
        **{'craftov_objective_raid_%d' % mates: {
            'images': numbered([image('craftov_panel_skill', 0, 0)]),
            'texts': numbered([text('[craftov_objective]', 6, 0)]),
            'conditions': cond(('craftov_in_raid', 'true'), ('craftov_has_objective', 'true'),
                               *(('craftov_has_mate_%d' % i, 'true' if i <= mates else 'false') for i in (1, 2))),
        } for mates in (0, 1, 2)},
        'craftov_alert': {
            'images': numbered([image('craftov_alert_band', 0, 0)]),
            'texts': numbered([text('[craftov_alert]', 150, 1, align='center')]),
            'conditions': cond(('craftov_has_alert', 'true')),
        },
        'craftov_prompt': {
            'images': numbered([image('craftov_prompt_band', 0, -1)]),
            'texts': numbered([text('[craftov_prompt]', 100, 0, align='center')]),
            'conditions': cond(('craftov_has_prompt', 'true'), ('craftov_extracting', 'false')),
        },
        'craftov_extract': {
            'images': numbered([image('craftov_progress_empty', 0, 30), image('craftov_extract_bar', 0, 30)]),
            'texts': numbered([text(c(OK_HEX) + '撤离中', 60, 0, align='center'),
                               big('<white>[craftov_extract_left]', 60, 12, 'center')]),
            'conditions': cond(('craftov_extracting', 'true')),
        },
        'craftov_downed': {
            'images': numbered([image('craftov_progress_empty', 0, 13), image('craftov_downed_bar', 0, 13)]),
            # The hint says to use the injector when no squadmate is up to revive (HudData.downedHint).
            'texts': numbered([text(c(DANGER_HEX) + '倒地 · [craftov_downed_hint]', 60, 0, align='center')]),
            'conditions': cond(('craftov_is_downed', 'true')),
        },
        'craftov_med': {
            'images': numbered([image('craftov_progress_empty', 0, 13), image('craftov_med_bar', 0, 13)]),
            'texts': numbered([text(c('#7FC8D1') + '[craftov_med_name] <white>[craftov_med_left]s', 60, 0, align='center')]),
            'conditions': cond(('craftov_using_med', 'true')),
        },
        'craftov_lobby': {
            'images': numbered([image('craftov_panel_info', 0, 0)]),
            'texts': numbered([text(c(MUTED) + '大厅 · 干员 ' + c(TEXT) + '[craftov_operator]', 6, 1),
                               text(c(ACCENT_HEX) + '◈', 6, 17),
                               big(c(ACCENT_HEX) + '[craftov_money_text]', 18, 13),
                               text(c(MUTED) + '战备值 ' + c(TEXT) + '◈ [craftov_value_short]', 6, 31)]),
            'conditions': cond(('craftov_in_lobby', 'true')),
        },
        # The crosshair (HudData.crosshair): an outlined dot and four arms, one picture per gap, opening
        # with the cone of the next shot; the dot alone without a gun; none while aiming down the sights,
        # where the vanilla dot under it is left on the sights.
        'craftov_crosshair': {
            'images': numbered([image('craftov_crosshair_%d' % i, 0, 0, cond(('craftov_crosshair', i)))
                                for i in range(len(hudart.CROSSHAIR_GAPS) + 1)]),
        },
        # Kill streak badge above the crosshair, for a moment after each kill.
        'craftov_streak': {
            'texts': numbered([text('[craftov_streak]', 0, 0, align='center')]),
            'conditions': cond(('craftov_has_streak', 'true')),
        },
        'craftov_hit_layout': {'images': numbered([image('craftov_hit', 0, 0)])},
        'craftov_hit_head_layout': {'images': numbered([image('craftov_hit_head', 0, 0)])},
        'craftov_hit_kill_layout': {'images': numbered([image('craftov_hit_kill', -1, -1)])},
        'craftov_hit_knock_layout': {'images': numbered([image('craftov_hit_knock', -1, -1)])},
        **{'craftov_damage_%d_layout' % i: {'images': numbered([image('craftov_damage_%d' % i, 0, 0)])} for i in range(8)},
    }
    # BetterHud centres a layout on its anchor by default; every position above is measured from the top-left corner.
    for layout in layouts.values():
        layout['offset'] = 'left'
    write_text(os.path.join(BH, 'layouts', 'craftov.yml'), yaml.safe_dump(layouts, allow_unicode=True, sort_keys=False))

    def place(name, gx, gy, px, py):
        return {'name': name, 'gui': {'x': gx, 'y': gy}, 'pixel': {'x': px, 'y': py}}

    huds = {'craftov_hud': {'layouts': numbered([
        place('craftov_vitals', 0, 100, 2, -40),
        place('craftov_weapon', 100, 100, -120, -40),
        place('craftov_info', 0, 0, 4, 4),
        place('craftov_skill', 0, 0, 4, 51),
        place('craftov_mate_1', 0, 0, 4, 66),
        place('craftov_mate_2', 0, 0, 4, 79),
        *[place('craftov_objective_raid_%d' % mates, 0, 0, 4, 66 + 13 * mates) for mates in (0, 1, 2)],
        # The kill feed starts under the corner where the client draws advancement and recipe toasts
        # (32 px; Craftov's own effects carry no icon): at the very top a kill's "Monster Hunter" toast
        # hid it. Its last row ends at 100, above the chat on a 240 px high GUI (1280x720 at scale 3).
        *[place('craftov_feed_%d' % i, 100, 0, -6, 40 + 12 * (i - 1)) for i in FEED_LINES],
        place('craftov_alert', 50, 0, -150, 30),
        place('craftov_prompt', 50, 50, -100, 14),
        place('craftov_extract', 50, 50, -60, 26),
        place('craftov_downed', 50, 50, -60, 40),
        place('craftov_med', 50, 50, -60, 58),
        place('craftov_lobby', 0, 0, 4, 4),
        place('craftov_objective', 0, 0, 4, 51),
        place('craftov_streak', 50, 50, 0, -44),
    ])},
        # The crosshair is a HUD of its own (PackService turns both on), so the main HUD's layout never
        # moves it. Its centre pixel lands on the vanilla dot's (column W/2 - 1, row H/2 - 1 of a GUI W x H
        # with even sides, checked with the real client at GUI 2, 3 and 4): BetterHud puts the middle
        # of the picture there in columns but one row lower, hence the extra pixel up. A character
        # BetterHud's font lacks anywhere in the HUD pushes all of it sideways (HudData.BEARINGS).
        'craftov_crosshair': {'layouts': numbered([
            place('craftov_crosshair', 50, 50, -(hudart.CROSSHAIR_SIZE // 2), -(hudart.CROSSHAIR_SIZE // 2) - 1),
        ])}}
    write_text(os.path.join(BH, 'huds', 'craftov.yml'), yaml.safe_dump(huds, allow_unicode=True, sort_keys=False))

    popups = {}
    for name, layout, offset in (('craftov_hit', 'craftov_hit_layout', -7), ('craftov_hit_head', 'craftov_hit_head_layout', -7),
                                 ('craftov_hit_kill', 'craftov_hit_kill_layout', -8),
                                 ('craftov_hit_knock', 'craftov_hit_knock_layout', -9)):
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
    }}
    compass['craftov_compass']['file']['point'] = {'name': 'craftov/compass/point.png', 'y': -6}
    # Marker pictures by PointedLocation icon. BetterHud reads them from the same section as the point
    # (under `file`): at the compass's top level they were never loaded, and every extract was drawn
    # with the plain amber point instead of its green or grey diamond.
    compass['craftov_compass']['file']['custom-icon'] = {
        'extract_open': {'name': 'craftov/compass/extract_open.png'},
        'extract_closed': {'name': 'craftov/compass/extract_closed.png'},
        'objective': {'name': 'craftov/compass/objective.png'}}
    # In a raid (the extracts), and in the lobby or on the range while the guide points at a station.
    compass['craftov_compass']['conditions'] = cond(('craftov_compass', 'true'))
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
    """Assets for left-click firing: the capture block (a sculk vein on all six faces, see Nms.CAPTURE_BLOCK)
    is invisible and silent when "mined", stood in (its step sound replaces the floor's) or landed on.

    Also the vanilla sound overrides: the AI are zombies (patrols) and husks (elite) re-skinned as
    soldiers (soldiers.py), so their groans are muted and they walk like people. Everyone who can be
    shot (players, soldiers, and the mannequins that stand in for players who left) cries out with
    the voices of sounds.py when hurt or killed, instead of vanilla's "oof" (#41).
    """
    save(Image.new('RGBA', (16, 16), (0, 0, 0, 0)), os.path.join(MC, 'textures', 'block', 'sculk_vein.png'))
    # The ceiling that keeps a downed player crawling on a slab (DownedService.HALF_CEILING): a top purpur
    # slab sent to that player alone, drawn as nothing. Purpur is in no Craftov map.
    write_json(os.path.join(CV, 'models', 'block', 'empty.json'),
               {'textures': {'particle': 'minecraft:block/purpur_block'}, 'elements': []})
    write_json(os.path.join(MC, 'blockstates', 'purpur_slab.json'), {'variants': {
        'type=bottom': {'model': 'minecraft:block/purpur_slab'},
        'type=double': {'model': 'minecraft:block/purpur_block'},
        'type=top': {'model': 'craftov:block/empty'}}})
    silence = {'replace': True, 'sounds': [{'name': 'craftov:misc/silence', 'volume': 0.01}]}
    hurt = {'replace': True, 'sounds': [{'name': 'craftov:voice/hurt%d' % i, 'volume': 0.9} for i in range(1, 5)]}
    death = {'replace': True, 'sounds': [{'name': 'craftov:voice/death%d' % i} for i in range(1, 4)]}
    step = {'replace': True, 'sounds': [{'name': 'minecraft:step/stone%d' % i, 'volume': 0.9} for i in range(1, 7)]}
    overrides = {'block.sculk_vein.%s' % event: silence for event in ('hit', 'step', 'fall')}
    # A player's hurt sound depends on what hurt them; a mannequin (a stand-in) has the generic ones.
    for event in ('hurt', 'hurt_on_fire', 'hurt_drown', 'hurt_freeze', 'hurt_sweet_berry_bush'):
        overrides['entity.player.' + event] = hurt
    overrides['entity.player.death'] = death
    overrides['entity.generic.hurt'] = hurt
    overrides['entity.generic.death'] = death
    for mob in ('zombie', 'husk', 'drowned'):
        overrides['entity.%s.ambient' % mob] = silence
        overrides['entity.%s.hurt' % mob] = hurt
        overrides['entity.%s.death' % mob] = death
        overrides['entity.%s.step' % mob] = step
    for event in ('ambient_water', 'swim', 'shoot'):
        overrides['entity.drowned.' + event] = silence
    overrides['entity.drowned.hurt_water'] = hurt
    overrides['entity.drowned.death_water'] = death
    write_json(os.path.join(MC, 'sounds.json'), overrides)
    save(soldiers.patrol(), os.path.join(MC, 'textures', 'entity', 'zombie', 'zombie.png'))
    save(soldiers.elite(), os.path.join(MC, 'textures', 'entity', 'zombie', 'husk.png'))
    # Rival operators are drowned, whose model uses the modern layout; their outer layer stays empty.
    save(soldiers.modern(soldiers.operator()), os.path.join(MC, 'textures', 'entity', 'zombie', 'drowned.png'))
    save(Image.new('RGBA', (64, 64), (0, 0, 0, 0)), os.path.join(MC, 'textures', 'entity', 'zombie', 'drowned_outer_layer.png'))
    save(soldiers.modern(soldiers.operator()), os.path.join(CV, 'textures', 'entity', 'soldier', 'operator.png'))
    # Corpses are mannequins (player model), which need the modern layout.
    save(soldiers.modern(soldiers.patrol()), os.path.join(CV, 'textures', 'entity', 'soldier', 'patrol.png'))
    save(soldiers.modern(soldiers.elite()), os.path.join(CV, 'textures', 'entity', 'soldier', 'elite.png'))
    write_text(os.path.join(MC, 'shaders', 'core', 'rendertype_lines.fsh'), LINES_FSH)


def main():
    with open(os.path.join(RES, 'items.yml'), encoding='utf-8') as f:
        item_defs = yaml.safe_load(f)['items']
    if os.path.exists(PACK):
        shutil.rmtree(PACK)
    build_pack_meta()
    build_items(item_defs)
    build_knife_skins(item_defs)
    build_containers()
    build_fx()
    build_ui()
    build_screens()
    build_fonts()
    build_tooltips()
    build_skins()
    build_vanilla_hud()
    build_sounds()
    build_trigger_capture()
    build_betterhud()
    count = sum(len(files) for _, _, files in os.walk(PACK))
    print(f'pack files: {count}, betterhud files: {sum(len(f) for _, _, f in os.walk(BH))}')
    # Slot wells, glyph grid, code points, oversized GUI models, tooltip nine-slices: a failure here
    # means a screen would not line up in the game (tools/art/guiview.py makes previews of them).
    problems = guiview.check()
    if problems:
        print('guiview: %d problem(s)' % len(problems))
        for problem in problems:
            print('  ' + problem)
        sys.exit(1)
    print('guiview: all screens line up')
    # The gun reports as the client will decode them: a failure here means the pack must not ship.
    failures = audiocheck.check(os.path.join(CV, 'sounds'), verbose=False)
    if failures:
        print('audiocheck: %d problem(s)' % len(failures))
        for failure in failures:
            print('  ' + failure)
        sys.exit(1)
    print('audiocheck: all gun reports pass')


if __name__ == '__main__':
    main()
