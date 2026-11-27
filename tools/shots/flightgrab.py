#!/usr/bin/env python3
"""Records what the real client shows during the raid entry camera (issue #166: the fly-in looked
like a few teleports stitched together) and measures how continuously the view moves.

    python3 flightgrab.py --display :5 --out DIR [--label TEXT] [--before 1.0]

Prints `ready` once it grabs, then reads commands from stdin, one a line:

    mark [UNIX_TIME]   the flight starts about now: frames from --before seconds ahead of it on are kept
    stop               (or end of input) measure what was kept, write it out and exit

Frames are grabbed as fast as the X server hands them out and a frame counts from the first grab
that shows it (the client draws far fewer frames than are grabbed). Time 0 is the first frame of the
black curtain over the jump into the raid world (the mark when there is none).

How much the view changed from one drawn frame to the next is read off the picture: the mean
difference between the two (a grey 320x180 copy of each, divided by its own mean brightness so the
curtain fading out does not count), over the screen less the bands where titles, the HUD and the
hotbar are drawn, in % of the brightness, and the same per 50 ms (the software-rendered client
draws frames from 10 to over 150 ms apart). A camera the client moves smoothly changes the picture
a little on every frame it draws; one that jumps from one position to the next holds still between
them (the frames in between change only where something animates: under 1%) and then changes a lot
at once. The flight counts from the curtain lifting (CURTAIN seconds after it came down) to the
landing (LANDED), or the last frame that moved if that is later. The landing itself usually counts
as one jump: the hand, the hotbar and the landing title come up.

Writes DIR/<label>.json (per drawn frame: ms, the change and the change per 50 ms),
DIR/<label>-motion.png (the change per 50 ms of every drawn frame over the flight),
DIR/<label>-strip.png (12 frames in a row from the middle of the flight, each with its time and
change), DIR/<label>.gif (the flight as the screen showed it, 20 frames a second) and
DIR/<label>/*.jpg (every drawn frame), and prints one JSON line: {label, frames, frame_ms, moving,
holds, longest_hold_ms, jumps, ...}: the frames drawn in the flight, how many moved, the still
stretches of HOLD_MS or longer, and the jumps (a frame that changed a lot after frames that hardly
did).

    python3 flightgrab.py --redo DIR/<label>.json --out DIR

measures a run again from its saved frames (after the measure changed), and

    python3 flightgrab.py --compare DIR_A/<a>.json DIR_B/<b>.json --out OUT_PREFIX

puts two runs side by side: OUT_PREFIX.png (both motion charts and strips) and OUT_PREFIX.gif.

Run with the system python3 (Pillow built with XCB, numpy); the shots venv has neither.
"""

import argparse
import json
import queue
import sys
import threading
import time
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageGrab

FONT = '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'
GOLD = (242, 201, 76)
RED = (255, 77, 77)
GREY = (154, 160, 166)
TEXT = (230, 230, 230)
BG = (18, 20, 24)
SMALL = (320, 180)
# Where the world shows on every frame: not the titles' band across the middle, the HUD panels in
# the top corners and at the top (compass), nor the hotbar, chat and weapon panel at the bottom.
MASK = np.ones((SMALL[1], SMALL[0]), bool)
MASK[int(0.28 * SMALL[1]):int(0.64 * SMALL[1]), :] = False
MASK[int(0.80 * SMALL[1]):, :] = False
MASK[int(0.70 * SMALL[1]):, int(0.76 * SMALL[0]):] = False
MASK[:int(0.30 * SMALL[1]), :int(0.30 * SMALL[0])] = False
MASK[:int(0.08 * SMALL[1]), :] = False
# A drawn frame moved when it changed at least this much (% of the brightness) per 50 ms; animated
# water, clouds, titles fading and chunks being drawn in stay under it, and so does the very start of
# an eased move.
MOVED = 1.2
# A jump: a frame that changed at least JUMP_MIN, and JUMP times as much as the frames around it
# (the median of NEAR frames either side) - the view standing still, then all at once somewhere
# else. A still stretch of HOLD_MS or longer in the flight is a hold.
JUMP, JUMP_MIN, NEAR = 4.0, 3.0, 3
HOLD_MS = 250
# The curtain over the jump into the raid world lifts this long after it comes down (Cinematic:
# 4 + 14 + 10 ticks).
CURTAIN = 1.45
# And the squad lands about this long after it came down.
LANDED = 6.9
GIF_FPS = 20


def font(size):
    try:
        return ImageFont.truetype(FONT, size)
    except OSError:
        return ImageFont.load_default()


def grey(image):
    return np.asarray(image.convert('L').resize(SMALL, Image.BILINEAR), dtype=np.float32)


def change(a, b):
    """How much grey frame b differs from a, each taken relative to its own mean brightness, over
    MASK: in % of the brightness."""
    wa, wb = a[MASK], b[MASK]
    return float(np.abs(wa / max(wa.mean(), 1.0) - wb / max(wb.mean(), 1.0)).mean()) * 100


def measure(label, frames, mark, out, save=True):
    """Measures the kept frames, writes the files and returns the summary."""
    out.mkdir(parents=True, exist_ok=True)
    black = next((f['t'] for f in frames if f['t'] >= mark - 1.0 and f['luma'] < 8), None)
    zero = black if black is not None else mark
    prev = None
    for f in frames:
        f['ms'] = round((f['t'] - zero) * 1000)
        f['change'] = None if prev is None or f['luma'] < 8 or prev['luma'] < 8 else change(prev['grey'], f['grey'])
        # The same per 50 ms (a game tick): a frame the client took long to draw changed more for it.
        f['rate'] = None if f['change'] is None else f['change'] * 50 / max(10.0, (f['t'] - prev['t']) * 1000)
        prev = f
    # The flight: from the curtain lifting until the view stops for good, and at least until the
    # landing (Cinematic: 130 + 4 ticks).
    start = zero + CURTAIN
    flight = [f for f in frames if f['t'] >= start and f['change'] is not None]
    last_move = max([f['t'] for f in flight if f['rate'] >= MOVED] + [zero + LANDED])
    flight = [f for f in flight if f['t'] <= last_move]
    gaps = [b['t'] - a['t'] for a, b in zip(flight, flight[1:])]
    frame_ms = 1000 * float(np.median(gaps)) if gaps else None
    moving = [f for f in flight if f['rate'] >= MOVED]
    # A hold: the view sat still (no drawn frame moved) for HOLD_MS or longer, before the camera
    # comes to rest in the squad's eyes (it eases out over the last 0.8 s or so).
    holds, longest, last = 0, 0.0, None
    settling = zero + LANDED - 0.8
    for f in [g for g in moving if g['t'] <= settling] + [g for g in flight if g['t'] <= settling][-1:]:
        held = 0.0 if last is None else f['t'] - last
        if held * 1000 >= HOLD_MS:
            holds += 1
            longest = max(longest, held)
        last = f['t']
    jumps = []
    for i, f in enumerate(flight):
        around = [g['change'] for g in flight[max(0, i - NEAR):i] + flight[i + 1:i + 1 + NEAR]]
        if around and f['change'] >= JUMP_MIN and f['change'] >= JUMP * float(np.median(around)):
            jumps.append(f)
    for f in frames:
        f['jump'] = any(f is j for j in jumps)
    steps = sorted(f['change'] for f in moving)
    median = steps[len(steps) // 2] if steps else 0
    summary = {'label': label, 'frames': len(flight), 'frame_ms': None if frame_ms is None else round(frame_ms),
               'moving': len(moving), 'holds': holds, 'longest_hold_ms': round(longest * 1000), 'jumps': len(jumps),
               'jumps_at_ms': [f['ms'] for f in jumps], 'largest_change': round(max(steps, default=0), 1),
               'flight_ms': [round((start - zero) * 1000), round((last_move - zero) * 1000)],
               'median_change': round(median, 3), 'mark_ms': round((mark - zero) * 1000)}
    if save:
        (out / label).mkdir(exist_ok=True)
        for f in frames:
            f['file'] = f"{label}/{f['ms'] + 5000:06d}.jpg"
            f['image'].save(out / f['file'], quality=88)
    (out / f'{label}.json').write_text(json.dumps({
        **summary,
        'per_frame': [{'ms': f['ms'], 'change': None if f['change'] is None else round(f['change'], 3),
                       'rate': None if f['rate'] is None else round(f['rate'], 3),
                       'jump': f.get('jump', False), 'file': f['file']} for f in frames],
    }, indent=1), encoding='utf-8')
    motion_chart(out / f'{label}-motion.png', summary, frames)
    strip(out / f'{label}-strip.png', summary, frames)
    gif(out / f'{label}.gif', frames, start - 0.3, last_move + 0.6, zero)
    return summary


def motion_chart(path, summary, frames, width=1600, height=420, canvas=None, top=0):
    """Every drawn frame's change per 50 ms over the flight: dots, gold where the view moved, red
    for a jump."""
    own = canvas is None
    if own:
        canvas = Image.new('RGB', (width, height), BG)
    draw = ImageDraw.Draw(canvas)
    left, right, plot_top, bottom = 90, width - 30, top + 84, top + height - 50
    shown = [f for f in frames if f['change'] is not None]
    t0, t1 = summary['flight_ms'][0] - 300, summary['flight_ms'][1] + 300
    peak = max([f['rate'] for f in shown if t0 <= f['ms'] <= t1] + [MOVED * 2])
    x = lambda ms: left + (ms - t0) / max(1, t1 - t0) * (right - left)
    y = lambda v: bottom - min(v, peak) / peak * (bottom - plot_top)
    draw.text((16, top + 8), f"{summary['label']}：客户端画出的每一帧比上一帧变了多少（折算到每 50 毫秒）",
              font=font(24), fill=GOLD)
    draw.text((16, top + 42), f"运镜中画了 {summary['frames']} 帧，跳变 {summary['jumps']} 次，"
              f"停住 {summary['holds']} 次（最长 {summary['longest_hold_ms']} ms），单帧最大变化 {summary['largest_change']}%",
              font=font(22), fill=TEXT)
    for v in np.linspace(0, peak, 5):
        draw.line((left, y(v), right, y(v)), fill=(40, 44, 50))
        draw.text((10, y(v) - 12), f'{v:.1f}%', font=font(18), fill=GREY)
    for ms in range(int(t0 // 1000) * 1000, int(t1) + 1, 1000):
        if ms >= t0:
            draw.line((x(ms), plot_top, x(ms), bottom), fill=(40, 44, 50))
            draw.text((x(ms) - 14, bottom + 8), f'{ms / 1000:.0f} s', font=font(18), fill=GREY)
    prev = None
    for f in shown:
        if not t0 <= f['ms'] <= t1:
            continue
        p = (x(f['ms']), y(f['rate']))
        if prev is not None:
            draw.line((prev, p), fill=(70, 76, 84), width=1)
        prev = p
        colour = RED if f.get('jump') else GOLD if f['rate'] >= MOVED else GREY
        r = 5 if f.get('jump') else 3
        draw.ellipse((p[0] - r, p[1] - r, p[0] + r, p[1] + r), fill=colour)
    draw.text((left, bottom + 28), f'金点 = 镜头在动；灰点 = 几乎没动（不到 {MOVED}%）；红点 = 跳变（停住后一下子变了一大截）。'
              '纵轴：每 50 毫秒的画面变化（亮度的 %）',
              font=font(16), fill=GREY)
    if own:
        canvas.save(path)


def middle_frames(summary, frames, n=12):
    """n drawn frames in a row from the middle of the flight."""
    shown = [f for f in frames if f['change'] is not None
             and summary['flight_ms'][0] <= f['ms'] <= summary['flight_ms'][1]]
    if not shown:
        return []
    mid = len(shown) // 2
    return shown[max(0, mid - n // 2):max(0, mid - n // 2) + n]


def strip(path, summary, frames, canvas=None, top=0, width=1600):
    """12 drawn frames in a row from the middle of the flight, each with its time and change."""
    picks = middle_frames(summary, frames)
    cols, tile_w = 6, width // 6
    tile_h = round(tile_w * 9 / 16)
    rows = (len(picks) + cols - 1) // cols
    own = canvas is None
    if own:
        canvas = Image.new('RGB', (width, 50 + rows * (tile_h + 30)), BG)
    draw = ImageDraw.Draw(canvas)
    draw.text((12, top + 10), f"{summary['label']}：运镜中段连续 {len(picks)} 个画面（客户端画出的每一帧）", font=font(24), fill=GOLD)
    for i, f in enumerate(picks):
        x, y = (i % cols) * tile_w, top + 50 + (i // cols) * (tile_h + 30)
        canvas.paste(f['image'].convert('RGB').resize((tile_w - 6, tile_h - 4)), (x + 3, y))
        moved = f['rate'] >= MOVED
        draw.text((x + 8, y + tile_h - 2), f"{f['ms']} ms  变化 {f['change']:.1f}%"
                  + ('  跳变' if f.get('jump') else '' if moved else '  没动'),
                  font=font(17), fill=RED if f.get('jump') else TEXT if moved else GREY)
    if own:
        canvas.save(path)
    return 50 + rows * (tile_h + 30)


def timeline(frames, t_from, t_to):
    """What the screen showed at GIF_FPS from t_from to t_to: the newest drawn frame at each moment."""
    out, i = [], 0
    t = t_from
    while t <= t_to:
        while i + 1 < len(frames) and frames[i + 1]['t'] <= t:
            i += 1
        out.append(frames[i])
        t += 1 / GIF_FPS
    return out


def gif(path, frames, t_from, t_to, zero, size=(480, 270)):
    shown = timeline(frames, t_from, t_to)
    images = []
    for f in shown:
        im = f['image'].convert('RGB').resize(size, Image.BILINEAR)
        ImageDraw.Draw(im).text((6, 4), f"{(f['t'] - zero):.2f} s", font=font(14), fill=GOLD)
        images.append(im.convert('P', palette=Image.ADAPTIVE, colors=128))
    if images:
        images[0].save(path, save_all=True, append_images=images[1:], duration=round(1000 / GIF_FPS), loop=0,
                       optimize=True)


def load(json_path):
    """A run written by measure(), its frames read back from the saved JPEGs."""
    data = json.loads(Path(json_path).read_text(encoding='utf-8'))
    base = Path(json_path).parent
    frames = []
    for row in data['per_frame']:
        image = Image.open(base / row['file'])
        image.load()
        frames.append({'ms': row['ms'], 't': row['ms'] / 1000, 'change': row.get('change'), 'rate': row.get('rate'),
                       'jump': row.get('jump', False), 'image': image})
    return data, frames


def compare(a_path, b_path, prefix):
    """Two runs side by side: both charts and strips in one PNG, both flights in one GIF."""
    a, fa = load(a_path)
    b, fb = load(b_path)
    width, chart_h = 1600, 420
    strip_h = 50 + 2 * (round(width / 6 * 9 / 16) + 30)
    canvas = Image.new('RGB', (width, 2 * (chart_h + strip_h) + 20), BG)
    top = 0
    for data, frames in ((a, fa), (b, fb)):
        motion_chart(None, data, frames, width, chart_h, canvas, top)
        top += chart_h
        top += strip(None, data, frames, canvas, top, width)
        top += 20
    canvas.save(f'{prefix}.png')
    # The GIF: both flights from their curtain lifting, side by side, as long as the longer one.
    size = (400, 225)
    ta = timeline(fa, a['flight_ms'][0] / 1000 - 0.3, a['flight_ms'][1] / 1000 + 0.6)
    tb = timeline(fb, b['flight_ms'][0] / 1000 - 0.3, b['flight_ms'][1] / 1000 + 0.6)
    images = []
    for i in range(max(len(ta), len(tb))):
        frame = Image.new('RGB', (size[0] * 2 + 8, size[1] + 26), BG)
        draw = ImageDraw.Draw(frame)
        for k, (data, seq) in enumerate(((a, ta), (b, tb))):
            f = seq[min(i, len(seq) - 1)]
            frame.paste(f['image'].convert('RGB').resize(size, Image.BILINEAR), (k * (size[0] + 8), 26))
            draw.text((k * (size[0] + 8) + 6, 3), f"{data['label']}  {f['ms'] / 1000:.2f} s", font=font(16), fill=GOLD)
        images.append(frame.convert('P', palette=Image.ADAPTIVE, colors=128))
    images[0].save(f'{prefix}.gif', save_all=True, append_images=images[1:], duration=round(1000 / GIF_FPS), loop=0,
                   optimize=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('--display')
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--label', default='flight')
    parser.add_argument('--before', type=float, default=1.0)
    parser.add_argument('--compare', nargs=2)
    parser.add_argument('--redo', help='measure a run again from DIR/<label>.json and its saved frames')
    args = parser.parse_args()
    if args.compare:
        compare(args.compare[0], args.compare[1], args.out)
        return
    if args.redo:
        data = json.loads(Path(args.redo).read_text(encoding='utf-8'))
        base = Path(args.redo).parent
        frames = []
        for row in data['per_frame']:
            image = Image.open(base / row['file'])
            image.load()
            g = grey(image)
            frames.append({'t': row['ms'] / 1000, 'image': image, 'grey': g, 'luma': float(g.mean()), 'file': row['file']})
        print(json.dumps(measure(data['label'], frames, data.get('mark_ms', 0) / 1000, base, save=False)))
        return
    args.out.mkdir(parents=True, exist_ok=True)

    commands = queue.Queue()

    def read():
        for line in sys.stdin:
            commands.put((time.time(), line.strip()))
        commands.put((time.time(), 'stop'))
    threading.Thread(target=read, daemon=True).start()

    frames = []
    mark = None
    last = None
    first = True
    while True:
        stop = False
        while not commands.empty():
            at, line = commands.get()
            parts = line.split()
            if parts and parts[0] == 'mark' and mark is None:
                mark = float(parts[1]) if len(parts) > 1 else at
            elif parts and parts[0] == 'stop':
                stop = True
        if stop:
            break
        t = time.time()
        image = ImageGrab.grab(xdisplay=args.display)
        small = image.resize((image.width // 2, image.height // 2), Image.BILINEAR)
        g = grey(small)
        # A frame counts from the first grab that shows it.
        if last is None or float(np.abs(g - last).mean()) >= 0.05:
            frames.append({'t': t, 'image': small, 'grey': g, 'luma': float(g.mean())})
            last = g
        if first:
            print('ready', flush=True)
            first = False
        # Until the mark only the last few seconds are kept.
        horizon = (mark if mark is not None else t) - args.before
        while len(frames) > 1 and frames[1]['t'] < horizon:
            frames.pop(0)
    if mark is None or not frames:
        print(json.dumps({'label': args.label, 'error': 'no mark or no frames'}), flush=True)
        return
    print(json.dumps(measure(args.label, frames, mark, args.out)), flush=True)


if __name__ == '__main__':
    main()
