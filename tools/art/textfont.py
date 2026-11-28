"""The text font (#163): JiangChengYuanTi 500W (江城圆体, SIL Open Font License 1.1), the rounded face
backrooms-ng draws its HUD and menu text in (it uses the 300W weight), for everything the client
writes in its default font: item names and lore, menu buttons, chat, titles and the vanilla
screens' labels. Menu titles, the labels drawn into the menu art and the HUD keep the 10 px pixel
font (pixelfont.py).

Lineage: Adobe's Source Han Sans (思源黑体) -> ButTaiwan's GenSen Rounded (源泉圓體), its strokes
rounded, Taiwanese glyph forms -> LiuPeng's JiangChengYuanTi, the same outlines with the forms
changed to the mainland standard (500W is GenSenRounded TW M v1.501: shared glyphs have identical
outlines). Every step is under the OFL; Adobe's copyright notice, with the Reserved Font Name
'Source', carries down and heads OFL.txt.

The game draws Chinese from Unifont, a 16 px bitmap shown 8 GUI px high. At GUI scale 3 each of its
pixels covers one and a half screen pixels, so a stroke comes out one or two pixels wide at random
and the text looks ragged. A TrueType provider has the client rasterise the outlines itself,
antialiased, at SIZE x OVERSAMPLE pixels per em. The client samples font textures without
filtering, so a glyph only stays even when a whole number of texels falls on each screen pixel: at
an oversample of 6 that holds at GUI scale 1, 2, 3 and 6 (one texel in 6, 3, 2 or 1), and at 4 the
texels are small enough that the odd skipped one does not show. Unfiltered sampling also means a
stroke thinner than the texel step can fall between two samples and vanish, so the weight is 500W:
its strokes are about 95 units, 5 texels at 54 px per em (0.85 GUI px), where 300W's were 48 units
(2.6 texels) and dropped out at GUI scale 2. It also keeps the client's 1 GUI px text shadow and
bold offset close to the stroke instead of drawing a separate ghost. backrooms-ng gets its look by
baking the face into bitmap atlases at 4x; it only prints a fixed set of strings, while item names,
lore and chat here can be anything, so the client renders the outlines instead.

Widths: hanzi, digits and the space keep vanilla's advances, so the lines Text.wrap breaks (two
units per hanzi) and Chinese tooltips keep their width; Latin letters and most punctuation take the
face's own proportional widths, which differ from vanilla's 6 px cell (`-` 4 px, `/` 3.2, `%` 8.2),
so Latin-heavy lines come out narrower:
  * the em is 9 GUI px and every ideograph advances exactly one em, like Unifont's 8 px glyph plus
    the 1 px vanilla leaves after it; the face's hanzi are 8 px wide, so that gap stays;
  * Chinese punctuation (、。「」《》【】 ...) and kana are proportional in the face's character
    map; their full-width forms (the `fwid` feature) take those code points instead;
  * digits are tabular at 6 px, vanilla's digit advance, each centred on its old advance so columns
    of numbers line up; a space advances 4 px, as in vanilla;
  * block elements and box drawing (the ▍ of a stat bar) and `|` (Text.bar's marks) are left out,
    so they keep vanilla's narrow widths and the bars keep their length.
The face has no hinting program. FreeType auto-hints such a TrueType font, and in the client's
(normal) mode the auto-hinter may move an advance by a pixel; a one-instruction `prep` program sends
it down the bytecode path instead, which with no glyph instructions changes nothing but keeps the
advances above exact.

`python3 tools/art/textfont.py bake` subsets the pinned source font to the characters the game can
print (pixelfont.charset(): printable ASCII, every GB2312 hanzi, the symbols and whatever the
plugin's text and data files use, plus Latin-1, Greek, Cyrillic and kana for player names and
chat) and writes tools/art/fonts/jiangcheng/text.ttf, with OFL.txt and SOURCE.txt (provenance) next
to it; build.py only copies those into the pack, so the pack builds without the source. The source
is the npm package @fontpkg/jiang-cheng-yuan-ti 3.6.0 (the font files unmodified; its 300W is
byte-identical to backrooms-ng's copy), checked against its registry integrity and the font's
SHA-256; a local copy of the font can be given on the command line instead. Characters the face
lacks (✔ ✖ ❤ ▮ ...) fall through to vanilla's fonts, and so do traditional characters outside
GB2312 that only a zh_TW / zh_HK client's own screens print: the plugin writes simplified Chinese,
and Big5 would add about 2 MB.
"""
import base64
import hashlib
import io
import os
import sys
import tarfile
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pixelfont  # noqa: E402

FONTS = os.path.join(HERE, 'fonts', 'jiangcheng')
CACHE = os.path.join(HERE, '.cache')
OUT = os.path.join(FONTS, 'text.ttf')

WEIGHT = '500W'
PACKAGE = ('https://registry.npmjs.org/@fontpkg/jiang-cheng-yuan-ti/-/jiang-cheng-yuan-ti-3.6.0.tgz',
           'sha512-TTzSoKRg3V/ReEJV8NGQrcOLHlp5dts05Tj6nHE88d0KgkBuEAkBSMZckQP7tzKc0W0QWZBDLh3r17xdpzFu6w==')
MEMBER = ('package/江城圆体 %s.ttf' % WEIGHT, '8e7cc1282823e4d94b416f7ca16a35712d3e2f13c3e240243967ae665b77ec4e')
# GenSen Rounded's licence file: Adobe's notice and the OFL text, as every step of the lineage has it.
LICENSE = ('https://raw.githubusercontent.com/ButTaiwan/gensen-font/'
           '1a2d61e1ea08d7948b86dd77262ae2e5585fa1d5/SIL_Open_Font_License_1.1.txt',
           'd84b652ca46586043946969a33c1dbf5a43028440825ae744a325965bec4c8fb')

# Provider settings (build.py): an em of SIZE GUI px rasterised at SIZE x OVERSAMPLE pixels, moved
# down SHIFT GUI px (a whole number of texels), which puts a hanzi's ink on rows 0-8 of the line the
# way Unifont's sits.
SIZE = 9
OVERSAMPLE = 6
SHIFT = 2 / OVERSAMPLE
UPM = 1000                     # the face's units per em: an ideograph advances 9 px
DIGIT_ADVANCE = round(6 * UPM / SIZE)
SPACE_ADVANCE = round(4 * UPM / SIZE)
# Chinese punctuation and kana: their full-width (fwid) forms replace the proportional ones.
FULL_WIDTH = range(0x3000, 0x3100)
IDEOGRAPHS = (range(0x3400, 0x4DC0), range(0x4E00, 0xA000), range(0xF900, 0xFB00))
# For player names and chat: Latin-1, Greek, Cyrillic and kana, as far as the face has them.
EXTRA = [range(0xA0, 0x100), range(0x370, 0x400), range(0x400, 0x500), range(0x3040, 0x3100)]
# Left to vanilla: block elements and box drawing (the face draws them full-width, and ▍ tiles stat
# bars and rarity tags) and `|`, whose 1 px bar Text.bar repeats at 2 px a mark.
VANILLA = set(range(0x2500, 0x25A0)) | {ord('|')}


def download(url, what):
    print('fetching', url)
    try:
        with urllib.request.urlopen(url, timeout=120) as response:
            return response.read()
    except OSError as e:
        raise SystemExit('could not fetch %s (%s): %s' % (what, url, e))


def source_font(path=None):
    """The pinned source font: the given file, or the member of the cached (or fetched) npm package."""
    name, sha256 = MEMBER
    if path:
        data = open(path, 'rb').read()
    else:
        url, integrity = PACKAGE
        tgz = os.path.join(CACHE, url.rsplit('/', 1)[1])
        if not os.path.exists(tgz):
            os.makedirs(CACHE, exist_ok=True)
            with open(tgz, 'wb') as f:
                f.write(download(url, 'the npm package'))
        packed = open(tgz, 'rb').read()
        algorithm, want = integrity.split('-', 1)
        if base64.b64encode(hashlib.new(algorithm, packed).digest()).decode() != want:
            raise SystemExit('%s does not match the registry integrity %s' % (tgz, integrity))
        with tarfile.open(fileobj=io.BytesIO(packed), mode='r:gz') as tar:
            data = tar.extractfile(tar.getmember(name)).read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != sha256:
        raise SystemExit('%s has sha256 %s, not the pinned %s' % (path or name, digest, sha256))
    return data


def licence():
    """GenSen Rounded's licence file with the lineage below Adobe's notice."""
    url, sha256 = LICENSE
    path = os.path.join(CACHE, 'gensen_' + url.rsplit('/', 1)[1])
    if not os.path.exists(path):
        os.makedirs(CACHE, exist_ok=True)
        with open(path, 'wb') as f:
            f.write(download(url, "GenSen Rounded's licence"))
    data = open(path, 'rb').read()
    if hashlib.sha256(data).hexdigest() != sha256:
        raise SystemExit('%s does not match the pinned sha256 %s' % (path, sha256))
    text = data.decode('utf-8').replace('\r\n', '\n')
    notice, rest = text.split('\n\n', 1)
    return notice + '\n\n' + LINEAGE + '\n' + rest.lstrip('\n')


LINEAGE = '''\
GenSen Rounded (源泉圓體) by ButTaiwan, https://github.com/ButTaiwan/gensen-font,
a Modified Version of Source Han Sans; its licence file carries only the notice above.
JiangChengYuanTi (江城圆体) by LiuPeng (刘鹏), a Modified Version of GenSen Rounded with
mainland Chinese glyph forms; its name table records no copyright notice.
Craftov's text.ttf is a Modified Version of JiangChengYuanTi (tools/art/fonts/jiangcheng/SOURCE.txt).
'''


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


def wanted():
    """Every character the subset should hold, if the face has it."""
    chars = set(pixelfont.charset()) | {' ', '　'}
    for block in EXTRA:
        chars.update(chr(c) for c in block if chr(c).isprintable())
    return sorted(ch for ch in chars if ord(ch) not in VANILLA)


def bake(path=None):
    from fontTools import subset
    from fontTools.ttLib import TTFont, newTable
    from fontTools.ttLib.tables import ttProgram

    data = source_font(path)
    # The source's own timestamp is kept, so the same source always bakes the same bytes.
    font = TTFont(io.BytesIO(data), recalcTimestamp=False)
    cmap = font.getBestCmap()
    full = full_width_forms(font)
    for table in font['cmap'].tables:
        if table.isUnicode():
            for code in FULL_WIDTH:
                if table.cmap.get(code) in full:
                    table.cmap[code] = full[table.cmap[code]]
    chars = wanted()
    kept = [ch for ch in chars if ord(ch) in cmap]
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
        elif any(code in block for block in IDEOGRAPHS):
            hmtx[name] = (UPM, lsb)
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
    with open(os.path.join(FONTS, 'OFL.txt'), 'w', encoding='utf-8') as f:
        f.write(licence())
    lacking = ''.join(ch for ch in chars if ord(ch) not in cmap and ord(ch) not in range(0xA0, 0x500))
    with open(os.path.join(FONTS, 'SOURCE.txt'), 'w', encoding='utf-8') as f:
        f.write(PROVENANCE % {
            'weight': WEIGHT, 'package': PACKAGE[0], 'integrity': PACKAGE[1], 'member': MEMBER[0],
            'member_sha': MEMBER[1], 'license': LICENSE[0], 'license_sha': LICENSE[1], 'chars': len(kept),
            'digit': DIGIT_ADVANCE, 'space': SPACE_ADVANCE,
            'out_sha': hashlib.sha256(out.getvalue()).hexdigest(), 'lacking': lacking})
    print('baked %d characters into %s (%d bytes); not in the face: %s'
          % (len(kept), OUT, len(out.getvalue()), lacking))


def lacking():
    """The characters the game prints that the face has no glyph for, as the last bake recorded them."""
    with open(os.path.join(FONTS, 'SOURCE.txt'), encoding='utf-8') as f:
        for line in f:
            if line.startswith(LACKING):
                return set(line[len(LACKING):].strip())
    return set()


LACKING = 'Not in the face (drawn by the game\'s own fonts): '
PROVENANCE = '''\
JiangChengYuanTi %(weight)s (江城圆体 %(weight)s), Version 3.6 2021/10/22, designer LiuPeng (刘鹏)
License: SIL Open Font License 1.1 (OFL.txt). The name table carries the OFL notice (name IDs 13
and 14) and records no copyright notice and no Reserved Font Name of its own.

Lineage (OFL at every step; Adobe's notice heads OFL.txt):
  Source Han Sans (思源黑体), Copyright 2014-2019 Adobe, Reserved Font Name 'Source'
  -> GenSen Rounded (源泉圓體) v1.501 by ButTaiwan, https://github.com/ButTaiwan/gensen-font:
     the strokes rounded, Taiwanese glyph forms
  -> JiangChengYuanTi 3.6 by LiuPeng: GenSen Rounded with mainland Chinese glyph forms. The 500W
     weight is GenSenRounded TW M: glyphs both share have identical outlines.

Source: %(member)s, sha256 %(member_sha)s
  in the npm package %(package)s
  registry integrity %(integrity)s
  The package ships the five weights unmodified: its 300W is byte-identical to the copy in
  Inf-Network/backrooms-ng, its 500W to the one in Marigold1122/Necobridge. The author's own
  release is a cloud-drive download from https://www.maoken.com/freefonts/4916.html (OFL 1.1).
Licence text: GenSen Rounded's, with the lineage added below Adobe's notice
  %(license)s
  sha256 %(license_sha)s

text.ttf is a Modified Version under the OFL, baked by tools/art/textfont.py: %(chars)d characters
(printable ASCII, Latin-1, Greek, Cyrillic, kana, GB2312 and what the game prints, less block
elements, box drawing and `|`), Chinese punctuation and kana mapped to their full-width (fwid)
forms, every ideograph advancing exactly one em, no OpenType layout tables, tabular digits at
%(digit)d units, the space at %(space)d units and a one-instruction prep program.
sha256 %(out_sha)s
''' + LACKING + '%(lacking)s\n'


def provider():
    """The client's TrueType provider for the baked font (installed as craftov:font/text.ttf). Like
    vanilla's ASCII bitmaps it is off while the player has Force Unicode Font on."""
    return {'type': 'ttf', 'file': 'craftov:text.ttf', 'size': SIZE, 'oversample': OVERSAMPLE,
            'shift': [0, round(SHIFT, 4)], 'skip': '', 'filter': {'uniform': False}}


if __name__ == '__main__':
    if sys.argv[1:2] == ['bake']:
        bake(sys.argv[2] if len(sys.argv) > 2 else None)
    else:
        print(__doc__)
