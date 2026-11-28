"""The 10 px pixel font: once the HUD's, the menu titles' and the screen art's (now the rounded text
font, textfont.py), still the HUD's glyphs for the few symbols that face lacks and the ring numbers
on the range's paper targets.

Chinese glyphs drawn from Unifont (16 px) at any height other than 8 or 16 GUI pixels are resampled
by the client: at the HUD's old 0.45-0.65 text scales, and even at 8 px on GUI scale 3, rows and
columns of a stroke come out one or two screen pixels wide at random, which reads as blurry (#66). A
font drawn at 10 px and shown 10 GUI pixels high maps every font pixel onto exactly N screen pixels
at every GUI scale, so it stays sharp everywhere.

The glyphs come from Fusion Pixel Font 10px proportional (zh_hans), SIL Open Font License 1.1
(tools/art/fonts/OFL.txt and LICENSES/): `python3 tools/art/pixelfont.py bake` downloads the pinned
release into tools/art/.cache/ and bakes the character set below into tools/art/fonts/pixel10.png
(white glyphs, 11 x 12 cells) and pixel10.txt (one line of characters per row of cells). build.py
only reads those two files, so the pack builds without the download.

Character set: printable ASCII, the punctuation and symbols the game prints, every GB2312 hanzi and
whatever else the plugin's text and data files use. The few symbols the font lacks (✔ ✖ ⚠ ❤ ...)
are drawn here by hand; any other character it lacks is left out and shows as nothing.
"""
import hashlib
import io
import os
import re
import sys
import urllib.request
import zipfile

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
FONTS = os.path.join(HERE, 'fonts')
CACHE = os.path.join(HERE, '.cache')
SHEET = os.path.join(FONTS, 'pixel10.png')
CHARS = os.path.join(FONTS, 'pixel10.txt')

VERSION = '2026.11.10'
ZIP = 'fusion-pixel-font-10px-proportional-pcf-v%s.zip' % VERSION
URL = 'https://github.com/TakWolf/fusion-pixel-font/releases/download/%s/%s' % (VERSION, ZIP)
PCF = 'fusion-pixel-10px-proportional-zh_hans.pcf'

CELL_W, CELL_H = 11, 12   # widest glyph (10) plus one column; 12 rows from cap height to descender
TOP = 2                   # font rows above the cell (blank in every glyph of this font)
ASCENT = 9                # baseline sits 9 rows below the cell top
COLUMNS = 64

EXTRA = ('·×÷°±‰—–…‘’“”•※←↑→↓↖↗↘↙↔⇄■□▪▫▲△▶▷▼▽◀◁◆◇○●◎★☆✓✔✕✖✗✘⚠☠⊕⊗♥❤⏱▮▯│┃─━'
         '、。〃〈〉《》「」『』【】〔〕〖〗～！＂＃％＆＇（）＊＋，－．／０１２３４５６７８９：；＜＝＞？＠［＼］＾＿｀｛｜｝'
         'ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ①②③④⑤⑥⑦⑧⑨⑩✔✖✕⚠❤⊕⊗⚡−⬆✦☢⛑')


def pinned():
    path = os.path.join(FONTS, 'SOURCE.txt')
    if os.path.exists(path):
        m = re.search(r'sha256 ([0-9a-f]{64})', open(path, encoding='utf-8').read())
        return m.group(1) if m else None
    return None


def download():
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, ZIP)
    if not os.path.exists(path):
        print('downloading', URL)
        with urllib.request.urlopen(URL) as r:
            data = r.read()
        with open(path, 'wb') as f:
            f.write(data)
    data = open(path, 'rb').read()
    digest = hashlib.sha256(data).hexdigest()
    want = pinned()
    if want and want != digest:
        raise SystemExit('%s does not match the pinned sha256 %s' % (path, want))
    return data, digest


def source_chars():
    """Every non-ASCII character the game's own content prints: item and map names, code strings."""
    chars = set()
    sources = [os.path.join(ROOT, 'src', 'main', 'resources')]
    sources.append(os.path.join(ROOT, 'src', 'main', 'java'))
    for base in sources:
        for dirpath, _, files in os.walk(base):
            if os.sep + 'pack' + os.sep in dirpath + os.sep or os.sep + 'betterhud' in dirpath:
                continue
            for name in files:
                if name.endswith(('.yml', '.java')):
                    with open(os.path.join(dirpath, name), encoding='utf-8', errors='ignore') as f:
                        chars.update(ch for ch in f.read() if ord(ch) > 0x7F and ch.isprintable())
    return chars


def charset():
    chars = set(chr(c) for c in range(0x20, 0x7F))
    chars.update(EXTRA)
    for hi in range(0xB0, 0xF8):           # GB2312 level 1 and 2 hanzi
        for lo in range(0xA1, 0xFF):
            try:
                chars.add(bytes([hi, lo]).decode('gb2312'))
            except UnicodeDecodeError:
                pass
    chars.update(source_chars())
    chars.discard(' ')
    chars.discard('　')
    return sorted(c for c in chars if ord(c) < 0xFFF0 and not 0xE000 <= ord(c) <= 0xF8FF)


# Symbols the font does not have, drawn here (rows end on the baseline): status marks, the heart,
# the warning sign and the few pictograms the game prints.
HAND = {
    '✔': ['........#', '.......##', '......##.', '#....##..', '##..##...', '.####....', '..##.....'],
    '✖': ['##...##', '###.###', '.#####.', '..###..', '.#####.', '###.###', '##...##'],
    '✕': ['#.....#', '.#...#.', '..#.#..', '...#...', '..#.#..', '.#...#.', '#.....#'],
    '⚠': ['....#....', '...###...', '...#.#...', '..##.##..', '..#.#.#..', '.#..#..#.', '.#.....#.', '#...#...#',
          '#########'],
    '❤': ['.##...##.', '####.####', '#########', '#########', '.#######.', '..#####..', '...###...', '....#....'],
    '⊕': ['..#####..', '.#..#..#.', '#...#...#', '#...#...#', '#########', '#...#...#', '#...#...#', '.#..#..#.',
          '..#####..'],
    '⊗': ['..#####..', '.#.....#.', '#.#...#.#', '#..#.#..#', '#...#...#', '#..#.#..#', '#.#...#.#', '.#.....#.',
          '..#####..'],
    '⚡': ['...###', '..###.', '.###..', '######', '..###.', '.###..', '.##...', '.#....'],
    '−': ['#####', '.....', '.....'],
    '⬆': ['...#...', '..###..', '.#####.', '#######', '..###..', '..###..', '..###..'],
    '✦': ['...#...', '...#...', '..###..', '#######', '..###..', '...#...', '...#...'],
    '☢': ['..#####..', '.##...##.', '###...###', '####.####', '....#....', '...###...', '..#####..', '...###...'],
    '⛑': ['..#####..', '.#######.', '###.#.###', '#.......#', '#########'],
}


def hand_glyph(rows):
    img = Image.new('L', (CELL_W, CELL_H), 0)
    top = ASCENT - len(rows)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == '#':
                img.putpixel((x, top + y), 255)
    return img


def bake():
    data, digest = download()
    z = zipfile.ZipFile(io.BytesIO(data))
    pcf = os.path.join(CACHE, PCF)
    with open(pcf, 'wb') as f:
        f.write(z.read(PCF))
    os.makedirs(os.path.join(FONTS, 'LICENSES'), exist_ok=True)
    for name in z.namelist():
        if name.endswith(('OFL.txt', 'LICENSE.txt')):
            out = os.path.join(FONTS, name)
            os.makedirs(os.path.dirname(out), exist_ok=True)
            with open(out, 'wb') as f:
                f.write(z.read(name))
    font = ImageFont.truetype(pcf, 10)

    def render(ch):
        img = Image.new('L', (CELL_W + 4, CELL_H + TOP + 4), 0)
        d = ImageDraw.Draw(img)
        d.fontmode = '1'
        d.text((0, 0), ch, font=font, fill=255)
        return img.crop((0, TOP, CELL_W, TOP + CELL_H))

    tofu = render('\u0378').tobytes()  # an unassigned code point: the font's notdef box
    kept = []
    glyphs = []
    for ch in charset():
        img = render(ch)
        if img.tobytes() == tofu or not img.getbbox():
            if ch not in HAND:
                continue
            img = hand_glyph(HAND[ch])
        glyphs.append(img)
        kept.append(ch)
    rows = [kept[i:i + COLUMNS] for i in range(0, len(kept), COLUMNS)]
    sheet = Image.new('RGBA', (COLUMNS * CELL_W, len(rows) * CELL_H), (0, 0, 0, 0))
    white = Image.new('RGBA', (CELL_W, CELL_H), (255, 255, 255, 255))
    for i, img in enumerate(glyphs):
        x, y = (i % COLUMNS) * CELL_W, (i // COLUMNS) * CELL_H
        sheet.paste(white, (x, y), img)
    sheet.save(SHEET, optimize=True)
    with open(CHARS, 'w', encoding='utf-8') as f:
        for row in rows:
            f.write(''.join(row).ljust(COLUMNS, '\u0000') + '\n')
    with open(os.path.join(FONTS, 'SOURCE.txt'), 'w', encoding='utf-8') as f:
        f.write('Fusion Pixel Font %s, 10px proportional zh_hans (%s)\n%s\nsha256 %s\n'
                'License: SIL Open Font License 1.1 (OFL.txt, LICENSES/). Baked by tools/art/pixelfont.py.\n'
                % (VERSION, PCF, URL, digest))
    print('baked %d glyphs into %s' % (len(kept), SHEET))


# ---------------------------------------------------------------- reading the baked font

_cache = {}


def load():
    """(sheet, rows, {char: (x, y, width)}): the baked font, with each glyph's inked width."""
    if 'font' in _cache:
        return _cache['font']
    sheet = Image.open(SHEET).convert('RGBA')
    with open(CHARS, encoding='utf-8') as f:
        rows = [line.rstrip('\n') for line in f]
    alpha = sheet.getchannel('A')
    glyphs = {}
    for r, row in enumerate(rows):
        for c, ch in enumerate(row):
            if ch == '\u0000':
                continue
            box = alpha.crop((c * CELL_W, r * CELL_H, (c + 1) * CELL_W, (r + 1) * CELL_H)).getbbox()
            glyphs[ch] = (c * CELL_W, r * CELL_H, box[2] if box else 0)
    _cache['font'] = (sheet, rows, glyphs)
    return _cache['font']


def advance(ch):
    """How far the client moves after a glyph: its inked width plus one, as for any bitmap font."""
    if ch == ' ':
        return 4
    if ch == '　':
        return 11
    _, _, glyphs = load()
    g = glyphs.get(ch)
    return g[2] + 1 if g else 0


def width(text):
    return sum(advance(ch) for ch in text) - (1 if text else 0)


def draw(img, x, y, text, color, shadow=None):
    """Draws text onto an RGBA image with its cell top at y, the way the client would lay it out."""
    sheet, _, glyphs = load()
    for ch in text:
        g = glyphs.get(ch)
        if g:
            cell = sheet.crop((g[0], g[1], g[0] + CELL_W, g[1] + CELL_H))
            mask = cell.getchannel('A')
            if shadow:
                img.paste(Image.new('RGBA', cell.size, shadow), (x + 1, y + 1), mask)
            img.paste(Image.new('RGBA', cell.size, color), (x, y), mask)
        x += advance(ch)
    return x


if __name__ == '__main__':
    if sys.argv[1:] == ['bake']:
        bake()
    else:
        print(__doc__)
