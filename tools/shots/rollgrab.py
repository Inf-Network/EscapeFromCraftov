#!/usr/bin/env python3
"""Measures the real client's camera roll around moments a scene marks (issue #157: which drops of
health tilt the player's own view, vanilla's hurt shake), from frames grabbed as fast as the X
server hands them out.

    python3 rollgrab.py --display :5 --out DIR [--before 0.8] [--after 1.0] [--still 0.5]

Prints `ready` once it grabs, then reads commands from stdin, one a line:

    mark LABEL [UNIX_TIME]   a moment to look at (now, or the time given): the frames from --before
                             seconds ahead of it to --after past it are kept for LABEL
    stop                     (or end of input) measure what was kept, write it out and exit

Only the last few seconds are kept otherwise, so it can run through a whole scene.

The roll is read off a horizon the scene builds: a light wall in front of a dark one, whose top edge
runs across the screen near its middle (scenes/hurtshake.py). In every column on either side of the
screen (clear of the crosshair, an open chest menu, the HUD panels and the hand) the edge is where
the picture first turns from the dark above to the light below, half way between them (sharp, or
blurred behind a menu); a line through those points (RANSAC, then least squares on its inliers)
gives the edge's angle on screen. A straight edge in the world stays straight whatever the camera
does, and only a roll turns it: moving the camera (a crawl lowers it) moves the line without turning
it. The roll of a frame is its angle less the one of the still frame, --still seconds before the
mark; its rise is how far the edge's middle moved up, in degrees of a 70 degree view.

Vanilla's hurt shake turns the view by 14 sin(pi f^4) degrees, f falling from 1 to 0 over the 10
ticks after the hurt (GameRenderer.bobHurt, times the Damage Tilt option): it peaks at 14 degrees
about 100 ms in and is still over 5 at 200 ms. It turns about the direction the last hit came from
(the hurt direction a hit with knockback sends): a roll when that is 0 (nothing has set it since the
player entered the world) or a hit from the side, a nod (rise, no roll) for a hit from in front. A
view that does not shake stays within a few tenths of a degree.

Writes DIR/<label>.json (per frame: ms from the mark, roll, rise, and how many columns found the
edge), DIR/<label>-sheet.png (the still and seven frames from the mark on, the peak among them, with
the edge drawn and the roll written on them) and DIR/<label>-peak.png (the frame rolled most, at half
size), and prints one JSON line per label: {label, peak, at_ms, rise, frames, still_ms, shakes}
(shakes: a roll of 3 degrees or more). A mark whose label was used before gets -2, -3 ... added.

Run with the system python3 (Pillow built with XCB, numpy); the shots venv has neither.
"""

import argparse
import json
import math
import queue
import sys
import threading
import time
from collections import deque
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageGrab

FONT = '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'
# Where the edge is looked for, as shares of the screen: two side bands (the middle has the
# crosshair, and a chest menu when one is open) between the HUD's top panels and the hand.
COLUMNS = ((0.03, 0.30), (0.70, 0.97))
ROWS = (0.28, 0.78)
SHAKES = 3.0       # degrees of roll that count as the view shaking
SEEN = 0.25        # share of the side columns that must find the edge for a frame to count
GOLD = (242, 201, 76)
RED = (255, 77, 77)
TEXT = (230, 230, 230)
BG = (18, 20, 24)


def grab(display):
    return ImageGrab.grab(xdisplay=display)


def edge_line(image):
    """The horizon in `image`: (slope, intercept) in its pixels and the columns on the line, or
    (None, n) when too few columns found it."""
    gray = np.asarray(image.convert('L'), dtype=np.float32)
    h, w = gray.shape
    top, bottom = int(ROWS[0] * h), int(ROWS[1] * h)
    xs, ys = [], []
    for lo, hi in COLUMNS:
        for x in range(int(lo * w), int(hi * w)):
            col = gray[top:bottom, x]
            # The first crossing, from the top, of the level half way between the dark wall above
            # and the light one below: where the edge is, sharp or blurred (an open menu blurs the
            # world behind it and darkens it), whatever lies further down.
            dark, light = float(np.median(col[:8])), float(col.max())
            if light - dark < 25:
                continue
            level = (dark + light) / 2
            above = np.nonzero(col >= level)[0]
            if len(above) == 0 or above[0] == 0:
                continue
            i = int(above[0])
            y = i - 1 + (level - col[i - 1]) / max(col[i] - col[i - 1], 1e-3)
            xs.append(x)
            ys.append(top + y)
    total = sum(int(hi * w) - int(lo * w) for lo, hi in COLUMNS)
    if len(xs) < SEEN * total:
        return None, len(xs)
    xs, ys = np.array(xs, dtype=np.float64), np.array(ys, dtype=np.float64)
    rng = np.random.default_rng(157)
    best = None
    for _ in range(200):
        i, j = rng.choice(len(xs), 2, replace=False)
        if abs(xs[i] - xs[j]) < 0.2 * w:
            continue
        slope = (ys[j] - ys[i]) / (xs[j] - xs[i])
        inliers = np.abs(ys - (ys[i] + slope * (xs - xs[i]))) < 2.0
        if best is None or inliers.sum() > best.sum():
            best = inliers
    if best is None or best.sum() < SEEN * total:
        return None, len(xs)
    slope, intercept = np.polyfit(xs[best], ys[best], 1)
    return (float(slope), float(intercept)), int(best.sum())


def font(size):
    try:
        return ImageFont.truetype(FONT, size)
    except OSError:
        return ImageFont.load_default()


def sheet(path, label, frames, still, peak):
    """The still and seven frames from the mark on (the peak among them), each with its edge, its
    roll and how far the edge rose."""
    tile_w, tile_h = 400, 225
    picks = frames
    if len(frames) > 7:
        picks = sorted({frames[round(i * (len(frames) - 1) / 6)]['t']: frames[round(i * (len(frames) - 1) / 6)]
                        for i in range(7)}.values(), key=lambda f: f['t'])
        if peak is not None and peak not in picks:
            picks = sorted(picks[:-1] + [peak], key=lambda f: f['t'])
    out = Image.new('RGB', (tile_w * 4, (tile_h + 28) * 2 + 40), BG)
    draw = ImageDraw.Draw(out)
    draw.text((10, 8), label, fill=GOLD, font=font(22))
    for n, f in enumerate([still] + picks):
        tile = f['image'].resize((tile_w, tile_h))
        sx = tile_w / f['image'].width
        if f['line']:
            slope, intercept = f['line']
            ImageDraw.Draw(tile).line([(0, intercept * sx), (tile_w, (intercept + slope * f['image'].width) * sx)],
                                      fill=RED, width=1)
        x, y = (n % 4) * tile_w, 40 + (n // 4) * (tile_h + 28)
        out.paste(tile, (x, y))
        if n == 0:
            caption = f'still ({f["ms"]:+.0f} ms)'
        elif f['roll'] is None:
            caption = f'{f["ms"]:+.0f} ms  no edge'
        else:
            caption = f'{f["ms"]:+.0f} ms  roll {f["roll"]:+.1f}°  rise {f["rise"]:+.1f}°'
        hot = n > 0 and f['roll'] is not None and abs(f['roll']) >= SHAKES
        draw.text((x + 6, y + tile_h + 2), caption, fill=RED if hot else TEXT, font=font(18))
    out.save(path)


def measure(label, mark, frames, args, out_dir):
    """Measures one mark's frames and writes its files; returns the summary."""
    for f in frames:
        f['line'], f['columns'] = edge_line(f['image'])
        f['ms'] = (f['t'] - mark) * 1000
        w, h = f['image'].size
        # The edge's angle, and its height at the middle of the screen in degrees of a 70 degree view.
        f['angle'] = None if f['line'] is None else math.degrees(math.atan(f['line'][0]))
        f['height'] = None if f['line'] is None else \
            math.degrees(math.atan((h / 2 - (f['line'][1] + f['line'][0] * w / 2)) / (h / 2 / math.tan(math.radians(35)))))
    # The frame on the screen --still seconds before the mark (or the nearest one with an edge).
    shown = [f for f in frames if f['angle'] is not None and f['t'] <= mark - args.still]
    still = shown[-1] if shown else min((f for f in frames if f['angle'] is not None and f['t'] <= mark),
                                        key=lambda f: abs(f['t'] - (mark - args.still)), default=None)
    for f in frames:
        known = still is not None and f['angle'] is not None
        f['roll'] = f['angle'] - still['angle'] if known else None
        f['rise'] = f['height'] - still['height'] if known else None
    # From a little ahead of the mark: a moment noticed by polling comes after the drop it notices.
    after = [f for f in frames if f['t'] >= mark - 0.15]
    rolled = [f for f in after if f['roll'] is not None and f['t'] <= mark + args.after]
    peak = max(rolled, key=lambda f: abs(f['roll']), default=None)
    rise = max(rolled, key=lambda f: abs(f['rise']), default=None)
    summary = {
        'label': label,
        'peak': None if peak is None else round(peak['roll'], 2),
        'at_ms': None if peak is None else round(peak['ms']),
        'rise': None if rise is None else round(rise['rise'], 2),
        'frames': len(rolled),
        'still_ms': None if still is None else round(still['ms']),
        'shakes': peak is not None and abs(peak['roll']) >= SHAKES,
    }
    (out_dir / f'{label}.json').write_text(json.dumps({
        **summary,
        'per_frame': [{'ms': round(f['ms']), 'roll': None if f['roll'] is None else round(f['roll'], 3),
                       'rise': None if f['rise'] is None else round(f['rise'], 3), 'columns': f['columns']}
                      for f in frames],
    }, indent=1))
    if still is not None:
        sheet(out_dir / f'{label}-sheet.png', label, [f for f in after if f['t'] <= mark + 0.7], still, peak)
    if peak is not None:
        peak['image'].save(out_dir / f'{label}-peak.png')
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('--display', required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--before', type=float, default=0.8)
    parser.add_argument('--after', type=float, default=1.0)
    parser.add_argument('--still', type=float, default=0.5)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    commands = queue.Queue()

    def read():
        for line in sys.stdin:
            commands.put((time.time(), line.strip()))
        commands.put((time.time(), 'stop'))
    threading.Thread(target=read, daemon=True).start()

    buffer = deque()      # (time, half-size frame), the newest last
    pending = []          # (label, mark time) whose window is still open
    kept = []             # (label, mark time, frames)
    used = {}
    stopping = False
    last = None
    first = True
    while True:
        while not commands.empty():
            at, line = commands.get()
            parts = line.split()
            if not parts:
                continue
            if parts[0] == 'stop':
                stopping = True
            elif parts[0] == 'mark' and len(parts) >= 2:
                label = parts[1]
                used[label] = used.get(label, 0) + 1
                if used[label] > 1:
                    label = f'{label}-{used[label]}'
                pending.append((label, float(parts[2]) if len(parts) > 2 else at))
        if stopping and not pending:
            break
        t = time.time()
        frame = grab(args.display)
        small = frame.resize((frame.width // 2, frame.height // 2))
        # A frame counts from the first grab that shows it.
        thumb = small.resize((64, 36)).tobytes()
        if thumb != last:
            buffer.append((t, small))
            last = thumb
        if first:
            print('ready', flush=True)
            first = False
        for label, mark in list(pending):
            if t >= mark + args.after:
                # From the frame on the screen when the window opens: a still screen (a menu open,
                # nothing moving) may not have changed for longer than the window.
                frames = [(ft, im) for ft, im in buffer if ft <= mark + args.after]
                first = max((i for i, (ft, _) in enumerate(frames) if ft <= mark - args.before), default=0)
                kept.append((label, mark, [{'t': ft, 'image': im} for ft, im in frames[first:]]))
                pending.remove((label, mark))
        oldest = min([mark - args.before for _, mark in pending], default=t - args.before - 1.0)
        # Keeps the newest frame from before that too: it is what the screen showed then.
        while len(buffer) > 1 and buffer[1][0] < min(oldest, t - args.before - 1.0):
            buffer.popleft()

    for label, mark, frames in kept:
        print(json.dumps(measure(label, mark, frames, args, args.out)), flush=True)


if __name__ == '__main__':
    main()
