"""The text font (#163): JiangChengYuanTi 300W (江城圆体, SIL Open Font License 1.1), the rounded face
backrooms-ng draws its HUD and menu text in, for everything the client writes in its default font:
item names and lore, menu buttons, chat, titles and the vanilla screens' labels. Menu titles, the
labels drawn into the menu art and the HUD keep the 10 px pixel font (pixelfont.py).

The game draws Chinese from Unifont, a 16 px bitmap shown 8 GUI px high. At GUI scale 3 each of its
pixels covers one and a half screen pixels, so a stroke comes out one or two pixels wide at random
and the text looks ragged. A TrueType provider has the client rasterise the outlines itself,
antialiased, at SIZE x OVERSAMPLE pixels per em. The client samples font textures without
filtering, so a glyph only stays even when a whole number of texels falls on each screen pixel: at
an oversample of 6 that holds at GUI scale 1, 2, 3 and 6 (one texel in 6, 3, 2 or 1), and at 4 the
texels are small enough that the odd skipped one does not show. An oversample of 3 is exact at
scale 3 only and broke hanzi strokes apart at scale 2. backrooms-ng gets the same look by baking
the face into bitmap atlases at 4x; it only prints a fixed set of strings, while item names, lore
and chat here can be anything, so the client renders the outlines instead.

The text takes the room vanilla's does, so nothing laid out for it moves (tooltip widths, Text.wrap's
two units per hanzi, chat lines); the advances are the ones backrooms-ng gives its text:
  * the em is 9 GUI px and a full-width glyph (hanzi, Chinese punctuation, arrows, the ◈ of a price)
    advances its whole em, like Unifont's 8 px glyph plus the 1 px vanilla leaves after it; the
    face's hanzi are 8 px wide, so that gap stays;
  * Chinese punctuation (、。「」《》【】 ...) is half-width in the face's character map; its own
    full-width forms (the `fwid` feature) take those code points instead;
  * digits are tabular at 6 px, vanilla's digit advance, each centred on its old advance so columns
    of numbers line up; a space advances 4 px, as in vanilla;
  * Latin letters and the other punctuation keep the face's proportional spacing;
  * block elements and box drawing (the ▍ of a stat bar) are left out, so they keep vanilla's
    narrow widths and tile as before.
The face has no hinting program. FreeType auto-hints such a TrueType font, and in the client's
(normal) mode the auto-hinter may move an advance by a pixel; a one-instruction `prep` program sends
it down the bytecode path instead, which with no glyph instructions changes nothing but keeps the
advances above exact.

`python3 tools/art/textfont.py bake` subsets the pinned source font to the characters the game can
print (pixelfont.charset(): printable ASCII, every GB2312 hanzi, the symbols and whatever the
plugin's text and data files use) and writes tools/art/fonts/jiangcheng/text.ttf, with the OFL text
and SOURCE.txt (provenance) next to it; build.py only copies those into the pack, so the pack
builds without the source. The source is the copy in backrooms-ng's content pack, fetched with `gh`
(the repository is private) or given on the command line, and checked against its SHA-256.
Characters the face lacks (✔ ✖ ❤ ▮ ...) fall through to vanilla's fonts.
"""
import hashlib
import io
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pixelfont  # noqa: E402

FONTS = os.path.join(HERE, 'fonts', 'jiangcheng')
CACHE = os.path.join(HERE, '.cache')
OUT = os.path.join(FONTS, 'text.ttf')

REPO = 'Inf-Network/backrooms-ng'
COMMIT = '39be66bedbbb86b96ae872eba6b1e97f6fc692c8'
DIR = 'content-pack/src/hud/presentation/font/'
SOURCE = ('jiangcheng_yuanti_300w_v3_6.ttf', '3d1f578d270b9e9ec90d7541806df5bd53d97f720f0d31031cda38c70204bc21')
LICENSE = ('jiangcheng_yuanti_OFL_1_1.txt', '58a449e34b1aa50202fdca51cd73651fe0c5f78e5731e089c5e2c670b76eb299')

# Provider settings (build.py): an em of SIZE GUI px rasterised at SIZE x OVERSAMPLE pixels, moved
# down SHIFT GUI px (a whole number of texels), which puts a hanzi's ink on rows 0-8 of the line the
# way Unifont's sits.
SIZE = 9
OVERSAMPLE = 6
SHIFT = 2 / OVERSAMPLE
UPM = 1000                     # the face's units per em: a full-width glyph already advances 9 px
DIGIT_ADVANCE = round(6 * UPM / SIZE)
SPACE_ADVANCE = round(4 * UPM / SIZE)
# CJK punctuation (、。「」《》【】 ...) is half-width in the face's cmap; its `fwid` forms are the
# full-width ones Chinese text and vanilla's Unifont use, so those replace them.
FULL_WIDTH = range(0x3000, 0x3040)
# Block elements and box drawing tile at vanilla's narrow widths (the ▍ of a stat bar or a rarity
# tag): the face draws them full-width, so they are left to vanilla.
VANILLA = range(0x2500, 0x25A0)


def fetch(pinned, path=None):
    """A pinned file of backrooms-ng's font folder: the given path, the cached copy, or fetched."""
    name, sha256 = pinned
    path = path or os.path.join(CACHE, name)
    if not os.path.exists(path):
        os.makedirs(CACHE, exist_ok=True)
        print('fetching %s from %s@%s' % (name, REPO, COMMIT[:7]))
        data = subprocess.run(['gh', 'api', '-H', 'Accept: application/vnd.github.raw',
                               'repos/%s/contents/%s%s?ref=%s' % (REPO, DIR, name, COMMIT)],
                              check=True, capture_output=True).stdout
        with open(path, 'wb') as f:
            f.write(data)
    data = open(path, 'rb').read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != sha256:
        raise SystemExit('%s has sha256 %s, not the pinned %s' % (path, digest, sha256))
    return data


def full_width_forms(font):
    """{glyph: its full-width form} from the face's `fwid` single substitutions."""
    gsub = font['GSUB'].table
    out = {}
    for record in gsub.FeatureList.FeatureRecord:
        if record.FeatureTag != 'fwid':
            continue
        for index in record.Feature.LookupListIndex:
            for sub in gsub.LookupList.Lookup[index].SubTable:
                sub = getattr(sub, 'ExtSubTable', sub)
                out.update(getattr(sub, 'mapping', None) or {})
    return out


def bake(path=None):
    from fontTools import subset
    from fontTools.ttLib import TTFont, newTable
    from fontTools.ttLib.tables import ttProgram

    data = fetch(SOURCE, path)
    # The source's own timestamp is kept, so the same source always bakes the same bytes.
    font = TTFont(io.BytesIO(data), recalcTimestamp=False)
    cmap = font.getBestCmap()
    full = full_width_forms(font)
    for table in font['cmap'].tables:
        if table.isUnicode():
            for code in FULL_WIDTH:
                if table.cmap.get(code) in full:
                    table.cmap[code] = full[table.cmap[code]]
    chars = sorted(set(pixelfont.charset()) | {' ', '　'})
    kept = [ch for ch in chars if ord(ch) in cmap and ord(ch) not in VANILLA]
    opts = subset.Options()
    opts.layout_features = []   # the client applies no OpenType layout
    opts.hinting = False
    opts.drop_tables += ['GSUB', 'GPOS', 'GDEF', 'BASE', 'DSIG', 'vhea', 'vmtx', 'gasp']
    opts.name_IDs = ['*']        # keeps the licence notice (name IDs 13 and 14)
    opts.name_languages = ['*']
    opts.glyph_names = False
    opts.notdef_outline = True
    subsetter = subset.Subsetter(opts)
    subsetter.populate(unicodes=[ord(ch) for ch in kept])
    subsetter.subset(font)

    cmap = font.getBestCmap()
    hmtx, glyf = font['hmtx'], font['glyf']
    done = set()
    for code, name in sorted(cmap.items()):
        if name in done:
            continue
        done.add(name)
        advance, lsb = hmtx[name]
        ch = chr(code)
        if ch.isdigit() and ch.isascii():
            dx = round((DIGIT_ADVANCE - advance) / 2)
            glyph = glyf[name]
            if glyph.isComposite():
                for component in glyph.components:
                    component.x += dx
            elif glyph.numberOfContours > 0:
                glyph.coordinates.translate((dx, 0))
            glyph.recalcBounds(glyf)
            hmtx[name] = (DIGIT_ADVANCE, lsb + dx)
        elif ch == ' ':
            hmtx[name] = (SPACE_ADVANCE, lsb)
    font['hhea'].advanceWidthMax = max(a for a, _ in hmtx.metrics.values())
    prep = newTable('prep')
    prep.program = ttProgram.Program()
    prep.program.fromAssembly(['SVTCA[0]'])
    font['prep'] = prep

    os.makedirs(FONTS, exist_ok=True)
    out = io.BytesIO()
    font.save(out)
    with open(OUT, 'wb') as f:
        f.write(out.getvalue())
    with open(os.path.join(FONTS, 'OFL.txt'), 'wb') as f:
        f.write(fetch(LICENSE))
    lacking = ''.join(ch for ch in chars if ord(ch) not in cmap and ord(ch) not in VANILLA)
    with open(os.path.join(FONTS, 'SOURCE.txt'), 'w', encoding='utf-8') as f:
        f.write(PROVENANCE % {
            'repo': REPO, 'commit': COMMIT, 'dir': DIR, 'source': SOURCE[0], 'source_sha': SOURCE[1],
            'license': LICENSE[0], 'license_sha': LICENSE[1], 'chars': len(kept),
            'digit': DIGIT_ADVANCE, 'space': SPACE_ADVANCE,
            'out_sha': hashlib.sha256(out.getvalue()).hexdigest(), 'lacking': lacking})
    print('baked %d characters into %s (%d bytes); not in the face: %s'
          % (len(kept), OUT, len(out.getvalue()), lacking))


PROVENANCE = '''\
JiangChengYuanTi 300W (江城圆体 300W), Version 3.6 2021/10/22, designer LiuPeng, vendor ZIHI
License: SIL Open Font License 1.1 (OFL.txt). The font's name table carries the OFL notice (name IDs
13 and 14); it records no copyright holder and no Reserved Font Name.

Source: %(source)s, sha256 %(source_sha)s
  from %(repo)s@%(commit)s, %(dir)s
  (backrooms-ng took it in as a local file; the same folder holds the OFL text, %(license)s,
  sha256 %(license_sha)s, copied here as OFL.txt)

text.ttf is a Modified Version under the OFL, baked by tools/art/textfont.py: %(chars)d characters
(printable ASCII, GB2312 and what the game prints, less block elements and box drawing), CJK
punctuation mapped to its full-width (fwid) forms, no OpenType layout tables, tabular digits at
%(digit)d units, the space at %(space)d units and a one-instruction prep program.
sha256 %(out_sha)s
Not in the face (drawn by the game's own fonts): %(lacking)s
'''


def provider():
    """The client's TrueType provider for the baked font (installed as craftov:font/text.ttf)."""
    return {'type': 'ttf', 'file': 'craftov:text.ttf', 'size': SIZE, 'oversample': OVERSAMPLE,
            'shift': [0, round(SHIFT, 4)], 'skip': ''}


if __name__ == '__main__':
    if sys.argv[1:2] == ['bake']:
        bake(sys.argv[2] if len(sys.argv) > 2 else None)
    else:
        print(__doc__)
