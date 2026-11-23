#!/usr/bin/env python3
"""Times what the real client shows after a key or mouse press, from frames grabbed as fast as the
X server hands them out (issue #117: how long right mouse takes to zoom the view).

    python3 framegrab.py --display :5 --out DIR [--button 3] [--hold 1.6] [--watch 1.0] [--label TEXT]
    python3 framegrab.py --summary summary.json [--before old.json] --out chart.png    (the adstime scene's chart)

Grabs a frame before the press, presses the button (xdotool), grabs frames for --watch seconds, one
more as the settled view near the end of --hold, then lets go. A frame counts from the first grab
that shows it (the client draws far fewer frames than are grabbed). Each frame's zoom is measured
against the one before the press: the magnification z whose blow-up of that frame (about the screen
centre) best matches it over a patch just above the centre, which neither the gun model nor a
scope's mask covers. Progress is ln z / ln z_settled, so 0.5 is half the zoom whatever the sight's
power. Where the gun is held at the hip (low right, left of the weapon panel) each frame is also
compared with the settled view, zoomed back out to the frame's own zoom about the centre: there it
shows only the background, so how far the frame is from it, against how far the view before the
press was, is about 1 with the gun at the hip, 0 once it is up, and more while it sweeps through.
A frame that is back at 1 or more after the gun had left (under 0.6) is the gun dropping back to
the hip before the sights come up: the first such frame is reported (iron and 1x sights only: a
scope's mask is not where the settled view has it at another zoom, and zoomed back out from that
power the area would not be on the screen). Writes DIR/timing.json (per
frame: ms after the press, zoom, progress and hip) with the time of the first visible change (2%),
of half way, of settling (95% and staying there) and of a drop back, and DIR/sheet.png, the frames
with their times.

Run with the system python3 (needs Pillow built with XCB, and numpy); the shots venv has neither.
"""

import argparse
import json
import math
import subprocess
import time
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageGrab

FONT = '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'
SETTLED = 0.95
FIRST = 0.02
# The patch the zoom is measured on, as shares of the screen: above the centre, inside even the 8x
# scope's round window.
PATCH = (0.40, 0.18, 0.60, 0.46)
# Where the gun is held at the hip and the sights up never are: low right, left of and above the
# weapon panel (whose state text changes as the sights come up), and high enough that the settled
# view zoomed back out to 1x still covers it.
HIP = (0.60, 0.60, 0.78, 0.82)
# The gun is clear of the hip under this, and back at the hip from BACK on (see the docstring).
CLEAR, BACK = 0.6, 1.0
# Sights up to this power (iron sights, red dot, holo) are checked for that; HIP zoomed back out from
# more would not be on the screen.
HIP_POWER = 1.6


def grab(display):
    return ImageGrab.grab(xdisplay=display)


def patch(image, area=PATCH):
    w, h = image.size
    box = (int(w * area[0]), int(h * area[1]), int(w * area[2]), int(h * area[3]))
    return np.asarray(image.crop(box).convert('L'), dtype=np.float32)


def zoomed_out(settled, k):
    """The HIP area of the settled view as it looks at k times less zoom (about the screen centre)."""
    w, h = settled.size
    cx, cy = w / 2, h / 2
    x0, y0, x1, y1 = int(w * HIP[0]), int(h * HIP[1]), int(w * HIP[2]), int(h * HIP[3])
    box = (cx + (x0 - cx) * k, cy + (y0 - cy) * k, cx + (x1 - cx) * k, cy + (y1 - cy) * k)
    return np.asarray(settled.convert('L').resize((x1 - x0, y1 - y0), Image.BILINEAR, box=box), dtype=np.float32)


def drop_back(rows):
    """The ms of the first frame with the gun back at the hip after it had come up, or None."""
    clear = False
    for row in rows:
        if row['hip'] < CLEAR:
            clear = True
        elif clear and row['hip'] >= BACK:
            return row['ms']
    return None


class Zoom:
    """Measures frames' magnification against the view before the press."""

    def __init__(self, before):
        self.before = before.convert('L')
        self.cache = {}

    def blown_up(self, z):
        """The patch as the view before the press would show it at z times: blown up about the centre."""
        key = round(z, 5)
        if key not in self.cache:
            w, h = self.before.size
            x0, y0, x1, y1 = int(w * PATCH[0]), int(h * PATCH[1]), int(w * PATCH[2]), int(h * PATCH[3])
            cx, cy = w / 2, h / 2
            source = (cx + (x0 - cx) / z, cy + (y0 - cy) / z, cx + (x1 - cx) / z, cy + (y1 - cy) / z)
            self.cache[key] = np.asarray(self.before.resize((x1 - x0, y1 - y0), Image.BILINEAR, box=source),
                                         dtype=np.float32)
        return self.cache[key]

    def of(self, image, top):
        """The magnification in [1, top] that matches best: a coarse sweep, then a fine one."""
        seen = patch(image)
        lo, hi = 1.0, top
        best = 1.0
        for steps in (36, 24, 24):
            zs = np.exp(np.linspace(math.log(lo), math.log(hi), steps))
            errors = [float(np.abs(seen - self.blown_up(z)).mean()) for z in zs]
            i = int(np.argmin(errors))
            best = float(zs[i])
            lo, hi = float(zs[max(0, i - 1)]), float(zs[min(len(zs) - 1, i + 1)])
        return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--display')
    ap.add_argument('--summary')
    ap.add_argument('--before', help='with --summary: an earlier summary.json to draw beside it')
    ap.add_argument('--out', required=True)
    ap.add_argument('--button', default='3')
    ap.add_argument('--hold', type=float, default=1.6)
    ap.add_argument('--watch', type=float, default=1.0)
    ap.add_argument('--label', default='')
    ap.add_argument('--frames', action='store_true', help='also save every frame as DIR/<ms>.png')
    args = ap.parse_args()
    if args.summary:
        chart(json.loads(Path(args.summary).read_text(encoding='utf-8')), Path(args.out), args.label,
              json.loads(Path(args.before).read_text(encoding='utf-8')) if args.before else None)
        return
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    env = {'DISPLAY': args.display, 'PATH': '/usr/bin:/bin'}

    before = grab(args.display)
    time.sleep(0.05)
    before = grab(args.display)
    frames = []
    # The press: xdotool injects it through XTest before it exits, so its end is the latest it can be.
    subprocess.run(['xdotool', 'mousedown', args.button], env=env, check=True)
    pressed = time.monotonic()
    while time.monotonic() - pressed < args.watch:
        image = grab(args.display)
        frames.append((time.monotonic() - pressed, image))
    time.sleep(max(0.0, args.hold - (time.monotonic() - pressed) - 0.1))
    settled = grab(args.display)
    subprocess.run(['xdotool', 'mouseup', args.button], env=env, check=True)

    zoom = Zoom(before)
    final = zoom.of(settled, 12.0)
    checked = final < HIP_POWER
    far = max(1e-6, float(np.abs(patch(before, HIP) - zoomed_out(settled, final)).mean())) if checked else 1
    rows = []
    last = None
    for at, image in frames:
        pixels = np.asarray(image.convert('L').resize((320, 180)), dtype=np.float32)
        if last is not None and float(np.abs(pixels - last).mean()) < 0.05:
            continue  # the same frame as the last grab: the client has not drawn a new one
        last = pixels
        z = zoom.of(image, max(1.02, final * 1.25))
        progress = math.log(z) / math.log(final) if final > 1.001 else 0.0
        hip = float(np.abs(patch(image, HIP) - zoomed_out(settled, min(final, final / z))).mean()) / far if checked else 0.0
        rows.append({'ms': round(at * 1000), 'zoom': round(z, 4), 'progress': round(progress, 3), 'hip': round(hip, 3),
                     'image': image})
    first = next((r['ms'] for r in rows if r['progress'] > FIRST), None)
    half = next((r['ms'] for r in rows if r['progress'] >= 0.5), None)
    settle = None
    for i, r in enumerate(rows):
        if all(x['progress'] >= SETTLED for x in rows[i:]):
            settle = r['ms']
            break
    gaps = [b['ms'] - a['ms'] for a, b in zip(rows, rows[1:])]
    summary = {'label': args.label, 'zoom': round(final, 3), 'first_change_ms': first, 'half_ms': half, 'settled_ms': settle,
               'frames': len(rows), 'frame_ms': round(sorted(gaps)[len(gaps) // 2]) if gaps else None,
               'drop_ms': drop_back(rows) if checked else None, 'drop_checked': checked,
               'timeline': [{'ms': r['ms'], 'zoom': r['zoom'], 'progress': r['progress'], 'hip': r['hip']} for r in rows]}
    (out / 'timing.json').write_text(json.dumps(summary, indent=1), encoding='utf-8')
    if args.frames:
        for r in rows:
            r['image'].save(out / f"{r['ms']:04d}.png")
    sheet(out / 'sheet.png', before, rows, settled, summary)
    print(json.dumps({k: v for k, v in summary.items() if k != 'timeline'}))


def sheet(path, before, rows, settled, summary):
    """The frames from the press to settled (the zoom and the gun) in a grid, each with its time, zoom
    progress; a drop back to the hip is always among them, with the frames either side of it."""
    up = next((r['ms'] for i, r in enumerate(rows) if all(x['hip'] < CLEAR for x in rows[i:])), rows[-1]['ms'] if rows else 0)
    end = max(summary['settled_ms'] if summary['settled_ms'] is not None else rows[-1]['ms'] if rows else 0, up) + 60
    picked = [r for r in rows if r['ms'] <= end]
    if len(picked) > 14:
        keep = set()
        if summary.get('drop_ms') is not None:
            k = next(i for i, r in enumerate(picked) if r['ms'] == summary['drop_ms'])
            keep = {i for i in (k - 1, k, k + 1) if 0 <= i < len(picked)}
        spread = [round(i * (len(picked) - 1) / (13 - len(keep))) for i in range(14 - len(keep))]
        picked = [picked[i] for i in sorted(set(spread) | keep)]
    cells = [('按下前', before)] + [(f"{r['ms']} ms  {r['zoom']:.2f}x  {round(r['progress'] * 100)}%"
                                     + ('  枪回到腰间' if r['ms'] == summary.get('drop_ms') else ''), r['image']) for r in picked] \
        + [(f"稳定后 {summary['zoom']:.2f}x", settled)]
    w, h = before.size
    tw, th = 400, round(400 * h / w)
    cols = 4
    rows_n = (len(cells) + cols - 1) // cols
    head = 64
    canvas = Image.new('RGB', (cols * tw, head + rows_n * (th + 30)), (18, 20, 24))
    draw = ImageDraw.Draw(canvas)
    big = ImageFont.truetype(FONT, 30)
    small = ImageFont.truetype(FONT, 20)
    settle = summary['settled_ms']
    draw.text((12, 14), f"{summary['label']}   按右键到放大完成（95%）：{settle if settle is not None else '?'} ms"
              f"   开始放大 {summary['first_change_ms']} ms   一半 {summary['half_ms']} ms"
              + (f"   {summary['drop_ms']} ms 枪回到腰间" if summary.get('drop_ms') is not None else ''), font=big, fill=(242, 201, 76))
    for i, (text, image) in enumerate(cells):
        x, y = (i % cols) * tw, head + (i // cols) * (th + 30)
        canvas.paste(image.convert('RGB').resize((tw - 6, th - 4)), (x + 3, y))
        draw.text((x + 8, y + th - 2), text, font=small, fill=(230, 230, 230))
    canvas.save(path)


def chart(results, path, tag='', before=None):
    """Every gun's time from the press to a settled view (the middle of its runs, the others as a
    whisker), quickest first; with `before` (an earlier run's summary), its time as a grey bar above."""
    results = sorted(results, key=lambda r: r['settled_ms'])
    old = {r['key']: r for r in before or []}
    most = max([max(r['settled']) for r in results] + [max(r['settled']) for r in old.values()])
    width, left, row = 1700, 300, 60 if old else 44
    canvas = Image.new('RGB', (width, 90 + row * len(results) + 40), (18, 20, 24))
    draw = ImageDraw.Draw(canvas)
    big = ImageFont.truetype(FONT, 30)
    small = ImageFont.truetype(FONT, 22)
    title = f"真客户端：按下右键到画面放大完成（逐帧计时，每把 {len(results[0]['settled'])} 次取中间值）"
    draw.text((16, 16), (tag + '  ' if tag else '') + title + ('  灰 = 改动前' if old else ''), font=big, fill=(242, 201, 76))
    scale = (width - left - 340) / max(1, most)
    for i, r in enumerate(results):
        y = 80 + i * row
        draw.text((16, y + (14 if old else 6)), r['label'], font=small, fill=(230, 230, 230))
        was = old.get(r['key'])
        if was:
            draw.rectangle((left, y + 4, left + was['settled_ms'] * scale, y + 22), fill=(92, 98, 106))
            draw.text((left + was['settled_ms'] * scale + 12, y - 2), f"{was['settled_ms']} ms", font=small, fill=(154, 160, 166))
            y += 22
        draw.rectangle((left, y + 4, left + r['settled_ms'] * scale, y + (28 if old else row - 10)), fill=(242, 201, 76))
        lo, hi = min(r['settled']), max(r['settled'])
        mid = y + (16 if old else row // 2 - 1)
        draw.line((left + lo * scale, mid, left + hi * scale, mid), fill=(154, 160, 166), width=3)
        draw.text((left + max(hi, r['settled_ms']) * scale + 12, y + (4 if old else 6)), f"{r['settled_ms']} ms"
                  + (f"  （放大过程 {r['rise_ms']} ms）" if r.get('rise_ms') is not None else ''), font=small, fill=(230, 230, 230))
    runs = len(results[0]['settled'])
    draw.text((16, canvas.height - 34), f"客户端约 {results[0]['frame_ms']} ms 一帧（软件渲染），计时误差约一帧；细线是 {runs} 次的范围；"
              "放大过程 = 从画面开始放大到完成，不含按键到服务器的延迟", font=small, fill=(154, 160, 166))
    canvas.save(path)


if __name__ == '__main__':
    main()
