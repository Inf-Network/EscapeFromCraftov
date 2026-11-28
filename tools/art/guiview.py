"""Offline previews and checks of the container screens (#10).

Composes every designed screen the way the client draws it: the chest window from generic_54.png
(rows*18+17 px of the top, then the 96 px player part from y=126), the screen's glyph over it from
the window's top-left corner (gui/UiFont.java puts it there), its labels in the text font as the
title writes them (ui/screens.json), and a rarity tile in a few slots, so a screen can be judged
without starting the game; plus the inventory screen (E) with its info cells. Everything is composed
at textfont.TEXTURE_K texels per GUI pixel and sampled down without filtering, as the client does.
Previews are written at GUI scale 2, 3 and 4 to tools/art/.cache/guiview/<screen>@<scale>.png.

  python3 tools/art/guiview.py            previews and checks
  python3 tools/art/guiview.py --check    checks only (build.py runs these after every build)

Checks (exit code 1 on any problem):
  * generic_54.png and inventory.png are 256x256 GUI px at TEXTURE_K texels each, and every vanilla
    slot has an 18x18 well at its
    place: E screen (7, 7+18k), (7+18j, 83+18i), (7+18j, 141) and the five info cells; generic_54
    (7+18j, 17+18i), (7+18j, 139+18i), (7+18j, 197), all in texture pixels;
  * every well drawn in a screen glyph sits on the slot grid (0 px off), and no two glyphs of the
    craftov:ui font share a code point;
  * the lang files are valid JSON, every item whose GUI model fills the slot is oversized_in_gui,
    and every tooltip sprite has its nine-slice .mcmeta.
"""
import json
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import px  # noqa: E402
import screens  # noqa: E402
import textfont  # noqa: E402

K = textfont.TEXTURE_K

RES = os.path.join(HERE, '..', '..', 'src', 'main', 'resources')
ASSETS = os.path.join(RES, 'pack', 'craftov', 'resourcepack', 'assets')
CV = os.path.join(ASSETS, 'craftov')
MC = os.path.join(ASSETS, 'minecraft')
OUT = os.path.join(HERE, '.cache', 'guiview')
WELL_DARK = screens.WELL_DARK[:3]
LINE = px.rgb(screens.P.LINE)[:3]


def is_well(img, x, y):
    """The signature of screens.well at (x, y): dark top and left edges, LINE bottom and right edges."""
    p = img.load()
    w, h = img.size
    if x + 17 >= w or y + 17 >= h:
        return False
    top = all(p[x + i, y][:3] == WELL_DARK and p[x + i, y][3] == 255 for i in range(0, 17))
    left = all(p[x, y + i][:3] == WELL_DARK and p[x, y + i][3] == 255 for i in range(0, 17))
    bottom = all(p[x + i, y + 17][:3] == LINE for i in range(1, 18))
    right = all(p[x + 17, y + i][:3] == LINE for i in range(1, 18))
    return top and left and bottom and right


def texture_problems():
    problems = []
    inv = Image.open(os.path.join(MC, 'textures', 'gui', 'container', 'inventory.png')).convert('RGBA')
    chest = Image.open(os.path.join(MC, 'textures', 'gui', 'container', 'generic_54.png')).convert('RGBA')
    for name, img in (('inventory.png', inv), ('generic_54.png', chest)):
        if img.size != (256 * K, 256 * K):
            problems.append('%s is %dx%d, not %dx%d' % (name, img.width, img.height, 256 * K, 256 * K))
    # One texel per GUI pixel for the well checks: the middle texel of each block.
    inv, chest = (img.resize((256, 256), Image.NEAREST) for img in (inv, chest))
    want = [(7, 7 + 18 * k) for k in range(4)]
    want += [(7 + 18 * j, 83 + 18 * i) for i in range(3) for j in range(9)]
    want += [(7 + 18 * j, 141) for j in range(9)]
    want += list(screens.INFO_CELLS.values())
    for x, y in want:
        if not is_well(inv, x, y):
            problems.append('inventory.png: no slot well at (%d, %d)' % (x, y))
    want = [(7 + 18 * j, 17 + 18 * i) for i in range(6) for j in range(9)]
    want += [(7 + 18 * j, 139 + 18 * i) for i in range(3) for j in range(9)]
    want += [(7 + 18 * j, 197) for j in range(9)]
    for x, y in want:
        if not is_well(chest, x, y):
            problems.append('generic_54.png: no slot well at (%d, %d)' % (x, y))
    return problems


def glyph_problems():
    problems = []
    meta = json.load(open(os.path.join(RES, 'ui', 'screens.json'), encoding='utf-8'))
    chars = [m['char'] for m in meta.values()]
    if len(set(chars)) != len(chars):
        problems.append('screens.json: two screens share a code point')
    font = json.load(open(os.path.join(CV, 'font', 'ui.json'), encoding='utf-8'))
    seen = {}
    for provider in font['providers']:
        found = []
        if provider['type'] == 'bitmap':
            found = [c for row in provider['chars'] for c in row]
        elif provider['type'] == 'space':
            found = list(provider['advances'])
        for c in found:
            if c in seen and c != '\0':
                problems.append('craftov:ui font: U+%04X is in %s and %s' % (ord(c), seen[c], provider.get('file', provider['type'])))
            seen[c] = provider.get('file', provider['type'])
    for sid in screens.SCREENS:
        path = os.path.join(CV, 'textures', 'font', 'screen', sid + '.png')
        if not os.path.exists(path):
            problems.append('screen %s has no glyph texture' % sid)
            continue
        img = Image.open(path).convert('RGBA')
        for y in range(img.height - 17):
            for x in range(img.width - 17):
                if is_well(img, x, y) and ((x - 7) % 18 or (y - 17) % 18):
                    problems.append('screen %s: a well at (%d, %d) is off the slot grid by (%d, %d) px'
                                    % (sid, x, y, (x - 7) % 18, (y - 17) % 18))
    return problems


def pack_problems():
    problems = []
    for lang in ('en_us', 'zh_cn', 'zh_tw', 'zh_hk'):
        try:
            json.load(open(os.path.join(MC, 'lang', lang + '.json'), encoding='utf-8'))
        except (OSError, ValueError) as ex:
            problems.append('lang %s: %s' % (lang, ex))
    items = os.path.join(CV, 'items')
    for name in sorted(os.listdir(items)):
        path = os.path.join(items, name)
        if name.endswith('.json'):
            definition = json.load(open(path, encoding='utf-8'))
            if 'item/gui/' in json.dumps(definition) and not definition.get('oversized_in_gui'):
                problems.append('item %s fills its slot but is not oversized_in_gui' % name)
    for name in getattr(__import__('ui'), 'TILES'):
        definition = json.load(open(os.path.join(items, 'ui', name + '.json'), encoding='utf-8'))
        if not definition.get('oversized_in_gui'):
            problems.append('ui tile %s is not oversized_in_gui' % name)
    sprites = os.path.join(CV, 'textures', 'gui', 'sprites', 'tooltip')
    for name in sorted(os.listdir(sprites)):
        if name.endswith('.png'):
            meta = os.path.join(sprites, name + '.mcmeta')
            try:
                scaling = json.load(open(meta, encoding='utf-8'))['gui']['scaling']
                if scaling.get('type') != 'nine_slice':
                    problems.append('tooltip sprite %s is not nine-slice' % name)
            except (OSError, ValueError, KeyError):
                problems.append('tooltip sprite %s has no nine-slice .mcmeta' % name)
    return problems


def check():
    """Every problem found, as strings (empty when all is well)."""
    return texture_problems() + glyph_problems() + pack_problems()


RARITY = ['#C8CCD2', '#5BD16B', '#4FA3FF', '#B66BFF', '#FFC23D', '#FF4D4D']   # build.RARITY_COLORS


def tile(rank):
    """A rarity tile at one pixel per GUI pixel (its features are whole GUI pixels, so nothing is lost)."""
    return px.rarity_tile(px.rgb(RARITY[rank]), rank).resize((18, 18), Image.NEAREST)


def up(img):
    return img.resize((img.width * K, img.height * K), Image.NEAREST)


def compose(sid, meta=None):
    """Screen `sid` as the client composes it, at K texels per GUI pixel (None without a glyph)."""
    path = os.path.join(CV, 'textures', 'font', 'screen', sid + '.png')
    if not os.path.exists(path):
        return None
    if meta is None:
        meta = json.load(open(os.path.join(RES, 'ui', 'screens.json'), encoding='utf-8'))
    chest = Image.open(os.path.join(MC, 'textures', 'gui', 'container', 'generic_54.png')).convert('RGBA')
    glyph = Image.open(path).convert('RGBA')
    rows = max(1, (glyph.height - 17) // 18)
    h = rows * 18 + 17
    img = Image.new('RGBA', (screens.W * K, (h + 96) * K), (0, 0, 0, 0))
    img.paste(chest.crop((0, 0, screens.W * K, h * K)), (0, 0))
    img.paste(chest.crop((0, 126 * K, screens.W * K, 222 * K)), (0, h * K))
    img.alpha_composite(up(glyph), (0, 0))
    for label in meta.get(sid, {}).get('labels', []):
        top = int(label['font'].split('_')[-1].replace('n', '-'))
        textfont.draw_label(img, label['x'], top, label['text'], px.rgb(label['color']))
    # A few items on the player's part show how tiles sit in the wells.
    for j, rank in enumerate(range(6)):
        img.alpha_composite(up(tile(rank)), ((7 + 18 * j) * K, (h + 13) * K))
    return img


def previews():
    os.makedirs(OUT, exist_ok=True)
    meta = json.load(open(os.path.join(RES, 'ui', 'screens.json'), encoding='utf-8'))
    shots = {}
    for sid in screens.SCREENS:
        img = compose(sid, meta)
        if img is not None:
            shots[sid] = img
    inv = Image.open(os.path.join(MC, 'textures', 'gui', 'container', 'inventory.png')).convert('RGBA').crop((0, 0, 176 * K, 166 * K))
    for j, rank in enumerate(range(6)):
        inv.alpha_composite(up(tile(rank)), ((7 + 18 * j) * K, 101 * K))
    shots['inventory'] = inv
    for sid, img in shots.items():
        backdrop = Image.new('RGBA', (img.width + 16 * K, img.height + 16 * K), (24, 24, 24, 255))
        backdrop.alpha_composite(img, (8 * K, 8 * K))
        for scale in (2, 3, 4):
            # Each screen pixel takes the texel under its centre, as the client samples.
            backdrop.resize((backdrop.width * scale // K, backdrop.height * scale // K), Image.NEAREST).save(
                os.path.join(OUT, '%s@%d.png' % (sid, scale)))
    return sorted(shots)


def main():
    problems = check()
    for p in problems:
        print('guiview:', p)
    if '--check' not in sys.argv:
        names = previews()
        print('guiview: %d previews in %s' % (len(names) * 3, os.path.relpath(OUT)))
    print('guiview: %s' % ('%d problem(s)' % len(problems) if problems else 'all screens line up'))
    sys.exit(1 if problems else 0)


if __name__ == '__main__':
    main()
