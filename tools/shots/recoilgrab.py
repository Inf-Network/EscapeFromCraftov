#!/usr/bin/env python3
"""Measures how far the real client's reticle moves off its target while a gun fires aimed (issue
#118: firing through a scope shook the view too much to aim), from frames grabbed as fast as the X
server hands them out.

    python3 recoilgrab.py --display :5 --out DIR --sight dot|scope --power 4 (--auto MS | --clicks T,T,...)
                          [--tail 2] [--label TEXT] [--server-log PATH --player NAME] [--frames]
    python3 recoilgrab.py --chart summary.json [--before old.json] --out chart.png [--label TEXT]

Right mouse must already be held with the sights up. Grabs the view, then fires without touching the
mouse: --auto holds left mouse for MS milliseconds (a full-auto spray), --clicks clicks it at those
seconds (one round a click), and grabs frames until --tail seconds after the last round. A frame
counts from the first grab that shows it (the client draws fewer frames than are grabbed).

Each frame is matched against the view before the first round: square patches of the world, clear
of the reticle's lines (a scope) or around the sight's housing (a red dot), are found again in it by
normalised cross-correlation, so the world's shift on screen is known to a fraction of a pixel, and
the spread of the patches' shifts tells whether the zoom changed. With a red dot the dot itself is
found too, by its colour near the centre: the gun model kicks it about. Where the reticle points, on
the view before the first round, is the dot's offset less the world's shift: the aim, in pixels of
the 1600x900 screen, positive up and right, and in degrees by the sight's power at a 70 degree field
of view. The shake is what is left of the aim's path once its own 150 ms average is taken out.

Writes DIR/recoil.json (the summary, and per frame the ms after the first round, the aim and the
world's zoom) and DIR/sheet.png (frames along the way, the target ringed), and prints the summary.
With --server-log, the server's debug lines for the player's shots (debug: true) give the rounds
fired and the pitch the server saw at each.

Run with the system python3 (Pillow built with XCB, numpy and scipy); the shots venv has none.
"""

import argparse
import json
import math
import re
import subprocess
import time
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageGrab
from scipy.signal import fftconvolve

FONT = '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'
FOV = 70.0
# The scope mask's hole, a share of the screen height (tools/art/models.py MASK_HOLE).
HOLE = {3: 0.47, 4: 0.45, 8: 0.43}
# Half-size of a matched patch and how far from where it is expected it is looked for, in pixels of
# the half-size frame the matching runs on.
PATCH = 32
REACHES = (40, 150)
SMOOTH_MS = 150
GOLD = (242, 201, 76)
GREY = (154, 160, 166)
TEXT = (230, 230, 230)
RED = (255, 77, 77)
BG = (18, 20, 24)


def px_per_degree(power, height):
    """Screen pixels per degree near the centre, at this power and a 70 degree field of view."""
    return (height / 2) * power / math.tan(math.radians(FOV / 2)) * math.pi / 180


def hole_radius(power, height):
    return HOLE.get(int(round(power)), 0.43) * height


def sites(sight, w, h, power):
    """Patch centres (half-size frame pixels) and the rectangles nothing may be matched over."""
    cx, cy = w // 2, h // 2
    if sight == 'scope':
        offsets = [(x, y) for x in (-112, -56, 56, 112) for y in (-112, -56, 56, 112) if abs(x) + abs(y) <= 168]
        return [(cx + x, cy + y) for x, y in offsets], []
    # A red dot or iron sights: the world around the sight's housing (and clear of the HUD: the panel
    # top left, the weapon panel bottom right, the hotbar).
    full = [(700, 110), (900, 110), (1100, 110), (1300, 110), (1490, 110), (1150, 330), (1350, 330), (1500, 330),
            (1150, 520), (1350, 520), (1500, 520), (420, 300), (450, 520)]
    scale_x, scale_y = w / 800, h / 450
    blocked = [(270, 95, 530, 355), (0, 0, 310, 100), (600, 380, 800, 450), (250, 405, 550, 450), (240, 330, 560, 450)]
    return ([(int(x / 2 * scale_x), int(y / 2 * scale_y)) for x, y in full],
            [(int(a * scale_x), int(b * scale_y), int(c * scale_x), int(d * scale_y)) for a, b, c, d in blocked])


def normxcorr(template, image):
    """Normalised cross-correlation of the template at every place it fits in the image."""
    t = template - template.mean()
    norm = math.sqrt(float((t * t).sum()))
    ones = np.ones(template.shape, dtype=np.float32)
    num = fftconvolve(image, t[::-1, ::-1], mode='valid')
    s1 = fftconvolve(image, ones, mode='valid')
    s2 = fftconvolve(image * image, ones, mode='valid')
    var = np.maximum(s2 - s1 * s1 / template.size, 1e-3)
    return num / (norm * np.sqrt(var))


def peak(score):
    """The best place in a score map, to a fraction of a pixel (a parabola through its neighbours)."""
    iy, ix = np.unravel_index(int(np.argmax(score)), score.shape)
    best = float(score[iy, ix])
    sx = sy = 0.0
    if 0 < ix < score.shape[1] - 1:
        a, b, c = score[iy, ix - 1], score[iy, ix], score[iy, ix + 1]
        d = a - 2 * b + c
        sx = 0.5 * (a - c) / d if d < 0 else 0.0
    if 0 < iy < score.shape[0] - 1:
        a, b, c = score[iy - 1, ix], score[iy, ix], score[iy + 1, ix]
        d = a - 2 * b + c
        sy = 0.5 * (a - c) / d if d < 0 else 0.0
    return ix + sx, iy + sy, best


class Matcher:
    """Finds the world's shift (and zoom) of a frame against a key frame."""

    def __init__(self, sight, w, h, power):
        self.sight, self.w, self.h = sight, w, h
        self.sites, self.blocked = sites(sight, w, h, power)
        self.radius = hole_radius(power, h) if sight == 'scope' else None

    def clear(self, x, y):
        """Whether a patch centred here shows only the world."""
        if x - PATCH < 0 or y - PATCH < 0 or x + PATCH > self.w or y + PATCH > self.h:
            return False
        if self.radius is not None and math.hypot(x - self.w / 2, y - self.h / 2) + PATCH * 1.42 > self.radius * 0.97:
            return False
        return not any(x + PATCH > a and x - PATCH < c and y + PATCH > b and y - PATCH < d for a, b, c, d in self.blocked)

    def shift(self, key, frame, guess):
        """(dx, dy, zoom - 1, patches) of the frame's world against the key's, or None: looked for
        near the guess first, then further out (a scope's kick can move the view 150 px in a frame)."""
        for reach in REACHES:
            got = self.shift_within(key, frame, guess, reach)
            if got is not None:
                return got
        return None

    def shift_within(self, key, frame, guess, reach):
        found = []
        for sx, sy in self.sites:
            if not self.clear(sx, sy):
                continue
            template = key[sy - PATCH:sy + PATCH, sx - PATCH:sx + PATCH]
            if float(template.std()) < 4.0:
                continue  # too flat to place
            gx, gy = int(round(sx + guess[0])), int(round(sy + guess[1]))
            x0, y0 = max(0, gx - PATCH - reach), max(0, gy - PATCH - reach)
            x1, y1 = min(self.w, gx + PATCH + reach), min(self.h, gy + PATCH + reach)
            if x1 - x0 < 2 * PATCH + 4 or y1 - y0 < 2 * PATCH + 4:
                continue
            px, py, score = peak(normxcorr(template, frame[y0:y1, x0:x1]))
            if score < 0.75:
                continue
            dx, dy = x0 + px - (sx - PATCH), y0 + py - (sy - PATCH)
            if not self.clear(sx + dx, sy + dy):
                continue
            found.append((sx - self.w / 2, sy - self.h / 2, dx, dy))
        if len(found) < 3:
            return None
        mx = float(np.median([f[2] for f in found]))
        my = float(np.median([f[3] for f in found]))
        kept = [f for f in found if abs(f[2] - mx) < 4 and abs(f[3] - my) < 4]
        if len(kept) < 3:
            return None
        # d = s * p + t over the kept patches: t is the shift at the centre, s the change of zoom.
        rows = []
        rhs = []
        for px, py, dx, dy in kept:
            rows += [[px, 1, 0], [py, 0, 1]]
            rhs += [dx, dy]
        (s, tx, ty), *_ = np.linalg.lstsq(np.array(rows, dtype=float), np.array(rhs, dtype=float), rcond=None)
        return float(tx), float(ty), float(s), len(kept)


def find_dot(image):
    """The red dot's offset from the screen centre (full-size pixels), or None: its colour within 160 px of the centre."""
    w, h = image.size
    box = (w // 2 - 160, h // 2 - 160, w // 2 + 160, h // 2 + 160)
    a = np.asarray(image.crop(box)).astype(np.int16)
    mask = (a[..., 0] > 150) & (a[..., 0] - a[..., 1] > 90) & (a[..., 0] - a[..., 2] > 90)
    ys, xs = np.nonzero(mask)
    if len(xs) < 2:
        return None
    return float(xs.mean() + box[0] - w / 2 + 0.5), float(ys.mean() + box[1] - h / 2 + 0.5)


def halve(image):
    a = np.asarray(image.convert('RGB'), dtype=np.float32)
    h, w = a.shape[0] // 2 * 2, a.shape[1] // 2 * 2
    return a[:h, :w].reshape(h // 2, 2, w // 2, 2, 3).mean(axis=(1, 3))


def shots_in_log(path, offset, player):
    """[(tick, server pitch)] of the player's shots logged since offset (debug: true)."""
    try:
        with open(path, 'rb') as f:
            f.seek(offset)
            text = f.read().decode('utf-8', 'replace')
    except OSError:
        return []
    out = []
    for line in text.splitlines():
        if f'[debug] {player} shot ' in line:
            tick = re.search(r'tick=(\d+)', line)
            pitch = re.search(r'pitch=(-?[\d.]+)', line)
            if tick and pitch:
                out.append((int(tick.group(1)), float(pitch.group(1))))
    return out


def capture(args):
    env = {'DISPLAY': args.display, 'PATH': '/usr/bin:/bin'}
    if args.auto:
        events = [(0.0, 'mousedown'), (args.auto / 1000.0, 'mouseup')]
        rounds_at = [0.0]
        last = max(0.0, args.auto / 1000.0 - 0.04)
    else:
        # A click marked r (7.75r) fires nothing: it starts the reload of an empty magazine.
        clicks = [(float(t.rstrip('r')), t.endswith('r')) for t in args.clicks.split(',')]
        events = sorted([(t, 'mousedown') for t, _ in clicks] + [(t + 0.08, 'mouseup') for t, _ in clicks])
        rounds_at = [t for t, reload in clicks if not reload]
        last = rounds_at[-1]
    log_offset = Path(args.server_log).stat().st_size if args.server_log and Path(args.server_log).exists() else 0
    before = grab(args.display)
    time.sleep(0.05)
    before = grab(args.display)
    frames = []
    start = None
    last_thumb = None
    pending = list(events)
    while True:
        now = 0.0 if start is None else time.monotonic() - start
        while pending and pending[0][0] <= now + 1e-3:
            _, action = pending.pop(0)
            subprocess.run(['xdotool', action, '1'], env=env, check=True)
            if start is None:
                start = time.monotonic()  # the first press is in once xdotool has returned
            now = time.monotonic() - start
        if now > last + args.tail:
            break
        image = grab(args.display)
        at = time.monotonic() - start
        thumb = np.asarray(image.convert('L').resize((200, 112)), dtype=np.float32)
        if last_thumb is not None and float(np.abs(thumb - last_thumb).mean()) < 0.05:
            continue
        last_thumb = thumb
        frames.append({'t': at, 'half': halve(image).astype(np.uint8), 'dot': find_dot(image) if args.sight == 'dot' else None})
    logged = shots_in_log(args.server_log, log_offset, args.player) if args.server_log else []
    return before, frames, rounds_at, last, logged


def grab(display):
    return ImageGrab.grab(xdisplay=display)


def gray(half):
    return half.astype(np.float32) @ np.array([0.299, 0.587, 0.114], dtype=np.float32)


def track(matcher, before_half, frames):
    """The world's shift (full-size pixels) and zoom of each frame against the view before the shots."""
    key = gray(before_half)
    key_offset = (0.0, 0.0)
    prev = (0.0, 0.0)
    prev_gray = key
    rows = []
    for frame in frames:
        image = gray(frame['half'])
        got = matcher.shift(key, image, (prev[0] - key_offset[0], prev[1] - key_offset[1]))
        if got is None and prev_gray is not key:
            # Too far from the key to find its patches: go on from the last frame that was placed.
            key, key_offset = prev_gray, prev
            got = matcher.shift(key, image, (0.0, 0.0))
        if got is None:
            rows.append(None)
            continue
        dx, dy, zoom, n = got
        prev = (key_offset[0] + dx, key_offset[1] + dy)
        prev_gray = image
        rows.append((prev[0] * 2, prev[1] * 2, zoom, n))
    return rows


def reloads(args):
    """[(from ms, to ms)] of each reload: from its click to the next one's (the sights may drop meanwhile)."""
    if not args.clicks:
        return []
    clicks = args.clicks.split(',')
    out = []
    for k, t in enumerate(clicks):
        if t.endswith('r'):
            to = float(clicks[k + 1].rstrip('r')) if k + 1 < len(clicks) else 1e9
            out.append((round(float(t[:-1]) * 1000), round(to * 1000)))
    return out


def analyse(args, before, frames, rounds_at, last, logged):
    w, h = before.size
    matcher = Matcher(args.sight, w // 2, h // 2, args.power)
    shifts = track(matcher, halve(before), frames)
    dot0 = find_dot(before) if args.sight == 'dot' else (0.0, 0.0)
    if args.sight == 'dot' and dot0 is None:
        raise SystemExit('no red dot near the centre of the view before the shots')
    timeline = []
    for frame, shift in zip(frames, shifts):
        if shift is None:
            continue
        dot = frame['dot'] if args.sight == 'dot' else (0.0, 0.0)
        if dot is None:
            continue  # the dot is off the sight's glass or hidden by the flash this frame
        sx, sy, zoom, n = shift
        up = sy - (dot[1] - dot0[1])
        right = (dot[0] - dot0[0]) - sx
        timeline.append({'ms': round(frame['t'] * 1000), 'up': round(up, 2), 'right': round(right, 2),
                         'world_x': round(sx, 2), 'world_y': round(sy, 2), 'dot_x': round(dot[0] - dot0[0], 2),
                         'dot_y': round(dot[1] - dot0[1], 2), 'zoom': round(1 + zoom, 4), 'patches': n})
    summary = {
        'label': args.label, 'sight': args.sight, 'power': args.power, 'px_per_degree': round(px_per_degree(args.power, h), 2),
        'dot0': [round(dot0[0], 2), round(dot0[1], 2)],
        'height': h, 'hole_px': round(hole_radius(args.power, h)) if args.sight == 'scope' else None,
        'rounds': len(logged) if logged else None,
        'server_climb_deg': [round(logged[0][1] - p, 2) for _, p in logged] if logged else [],
        'frames': len(frames),
        'last_round_ms': round(last * 1000), 'rounds_at_ms': [round(t * 1000) for t in rounds_at],
        'reload_ms': reloads(args), 'tail_ms': round(args.tail * 1000),
        'timeline': timeline,
    }
    return summarise(summary)


def summarise(summary):
    """The numbers from the summary's timeline (frames inside a reload left out): how far the reticle
    got from the target, where it was at the last round and once it settled, how much it shook, and
    how far each round took it from where it was just before."""
    timeline = [r for r in summary['timeline'] if not any(a <= r['ms'] < b for a, b in summary.get('reload_ms', []))]
    if len(timeline) < 5:
        raise SystemExit(f"only {len(timeline)} of {summary['frames']} frames could be placed")
    ms = np.array([r['ms'] for r in timeline], dtype=float)
    up = np.array([r['up'] for r in timeline])
    right = np.array([r['right'] for r in timeline])
    dist = np.hypot(up, right)
    last = summary['last_round_ms']
    firing = ms <= last + 200
    # Shake: the path less its own running average (SMOOTH_MS wide), over the firing.
    smooth_up = np.array([up[np.abs(ms - m) <= SMOOTH_MS / 2].mean() for m in ms])
    smooth_right = np.array([right[np.abs(ms - m) <= SMOOTH_MS / 2].mean() for m in ms])
    shake = np.hypot(up - smooth_up, right - smooth_right)
    jumps = np.hypot(np.diff(up), np.diff(right))
    jump_firing = jumps[firing[1:]] if firing[1:].any() else jumps

    def before(t):
        """The last frame drawn before t ms (the client draws a frame only when the view changes)."""
        earlier = np.nonzero(ms < t)[0]
        return int(earlier[-1]) if len(earlier) else 0

    end = before(last + 150)
    rounds_at = summary['rounds_at_ms']
    stop = last + summary.get('tail_ms', 2000)
    per_shot = []
    for k, t in enumerate(rounds_at):
        window = (ms >= t) & (ms < (rounds_at[k + 1] if k + 1 < len(rounds_at) else stop))
        start = before(t)
        if window.any():
            per_shot.append(round(float(np.max(np.hypot(up[window] - up[start], right[window] - right[start]))), 1))
    summary.update({
        'placed': len(timeline), 'frame_ms': round(float(np.median(np.diff(ms)))) if len(ms) > 1 else None,
        'peak_px': round(float(dist.max()), 1), 'peak_up_px': round(float(up.max()), 1),
        'end_up_px': round(float(up[end]), 1), 'end_right_px': round(float(right[end]), 1),
        'settled_px': round(float(dist[-1]), 1), 'settled_up_px': round(float(up[-1]), 1),
        'shake_px': round(float(np.sqrt(np.mean(shake[firing] ** 2))) if firing.any() else 0.0, 2),
        'max_jump_px': round(float(jump_firing.max()) if len(jump_firing) else 0.0, 1),
        'mean_jump_px': round(float(jump_firing.mean()) if len(jump_firing) else 0.0, 2),
        'per_shot_px': per_shot,
        'zoom_range': [round(float(min(r['zoom'] for r in timeline)), 4), round(float(max(r['zoom'] for r in timeline)), 4)],
    })
    return summary


def label_font(size):
    return ImageFont.truetype(FONT, size)


def sheet(path, before, frames, summary):
    """The view before the shots and frames along the firing and after, the target ringed in each."""
    timeline = {r['ms']: r for r in summary['timeline']}
    placed = [f for f in frames if round(f['t'] * 1000) in timeline]
    if not placed:
        return
    last = summary['last_round_ms']
    rows = summary['timeline']
    peak = max(rows, key=lambda r: math.hypot(r['up'], r['right']))['ms']
    wanted = sorted(set([peak] + [round(last * k / 6) for k in range(1, 7)] + [last + 300, last + 900, rows[-1]['ms']]))
    picked = []
    for t in wanted:
        best = min(placed, key=lambda f: abs(f['t'] * 1000 - t))
        if best not in picked:
            picked.append(best)
    picked.sort(key=lambda f: f['t'])
    w, h = before.size
    tw, th = 400, round(400 * h / w)
    cells = [('开火前', np.asarray(before.convert('RGB').resize((w // 2, h // 2))), None)] + \
            [(None, f['half'], timeline[round(f['t'] * 1000)]) for f in picked]
    cols = 4
    head = 64
    canvas = Image.new('RGB', (cols * tw, head + ((len(cells) + cols - 1) // cols) * (th + 30)), BG)
    draw = ImageDraw.Draw(canvas)
    big, small = label_font(28), label_font(19)
    unit = summary['height'] / 100
    draw.text((12, 14), f"{summary['label']}   {summary['rounds'] or '?'} 发   最远偏离 {summary['peak_px']:.0f} px"
              f"（{summary['peak_px'] / unit:.1f}% 屏高）   抖动 {summary['shake_px']:.1f} px   最后一发后 {summary['end_up_px']:.0f} px 上",
              font=big, fill=GOLD)
    for i, (text, half, row) in enumerate(cells):
        x, y = (i % cols) * tw, head + (i // cols) * (th + 30)
        image = Image.fromarray(np.asarray(half, dtype=np.uint8)).resize((tw - 6, th - 4))
        if row is not None:
            # The target: where the point under the reticle before the shots is now on the screen.
            fx = (tw - 6) / w
            tx = (w / 2 + summary['dot0'][0] + row['world_x']) * fx
            ty = (h / 2 + summary['dot0'][1] + row['world_y']) * fx
            d = ImageDraw.Draw(image)
            d.ellipse((tx - 9, ty - 9, tx + 9, ty + 9), outline=(64, 255, 96), width=3)
            text = f"{row['ms']} ms   上 {row['up']:.0f}  右 {row['right']:.0f} px"
        canvas.paste(image, (x + 3, y))
        draw.text((x + 8, y + th - 2), text, font=small, fill=TEXT)
    canvas.save(path)


def chart(results, path, tag='', before=None):
    """Per sight: the target's path around the reticle (left) and its height over time (right), this run
    in gold and an earlier one in grey."""
    old = {r['key']: r for r in before or []}
    row_h, width = 360, 1800
    canvas = Image.new('RGB', (width, 90 + row_h * len(results) + 50), BG)
    draw = ImageDraw.Draw(canvas)
    big, mid, small = label_font(30), label_font(22), label_font(18)
    title = '真客户端逐帧：开镜不动鼠标连开，准星离开原目标的距离（像素，1600×900 屏）'
    draw.text((16, 16), (tag + '  ' if tag else '') + title + ('  灰 = 改动前，金 = 改动后' if old else ''), font=big, fill=GOLD)
    for i, r in enumerate(results):
        top = 80 + i * row_h
        was = old.get(r['key'])
        runs = [(was, GREY), (r, GOLD)] if was else [(r, GOLD)]
        # Left: the scope's view, the target's path around the reticle.
        box = 320
        cx, cy = 30 + box // 2, top + 20 + box // 2
        reach = max(max(abs(p['up']), abs(p['right'])) for run, _ in runs for p in run['timeline'])
        hole = r.get('hole_px')
        span = max(reach * 1.1, hole or 0, 60)
        k = (box / 2) / span
        draw.rectangle((cx - box // 2, cy - box // 2, cx + box // 2, cy + box // 2), outline=(60, 64, 70))
        if hole:
            draw.ellipse((cx - hole * k, cy - hole * k, cx + hole * k, cy + hole * k), outline=(90, 96, 104), width=2)
        draw.line((cx - box // 2, cy, cx + box // 2, cy), fill=(60, 64, 70))
        draw.line((cx, cy - box // 2, cx, cy + box // 2), fill=(60, 64, 70))
        for run, colour in runs:
            pts = [(cx - p['right'] * k, cy + p['up'] * k) for p in run['timeline']]
            draw.line(pts, fill=colour, width=2)
            draw.ellipse((pts[-1][0] - 4, pts[-1][1] - 4, pts[-1][0] + 4, pts[-1][1] + 4), fill=colour)
        draw.text((cx - box // 2, cy + box // 2 + 2), ('圆 = 镜框 · ' if hole else '') + f'框宽 {2 * span:.0f} px · 十字 = 准星，线 = 目标的轨迹',
                  font=small, fill=GREY)
        # Right: how far up the reticle is over time.
        gx0, gx1 = 400, width - 520
        gy0, gy1 = top + 30, top + row_h - 40
        # The client draws no new frame while the view stands still: each line holds its last height
        # to the end of the capture.
        tmax = max(max(run['timeline'][-1]['ms'], run['last_round_ms'] + run.get('tail_ms', 2000)) for run, _ in runs)
        umax = max(max(abs(p['up']) for p in run['timeline']) for run, _ in runs)
        umax = max(umax * 1.1, 20)
        zero = gy0 + (gy1 - gy0) * umax / (2 * umax) if any(p['up'] < -5 for run, _ in runs for p in run['timeline']) else gy1 - 10
        scale_y = (zero - gy0) / umax
        draw.line((gx0, zero, gx1, zero), fill=(60, 64, 70))
        draw.line((gx0, gy0, gx0, gy1), fill=(60, 64, 70))
        for run, colour in runs:
            pts = [(gx0 + (gx1 - gx0) * p['ms'] / tmax, zero - p['up'] * scale_y) for p in run['timeline']]
            end = run['last_round_ms'] + run.get('tail_ms', 2000)
            pts.append((gx0 + (gx1 - gx0) * max(end, run['timeline'][-1]['ms']) / tmax, pts[-1][1]))
            draw.line(pts, fill=colour, width=2)
        for t in r['rounds_at_ms']:
            x = gx0 + (gx1 - gx0) * max(0, t) / tmax
            draw.line((x, gy1 - 6, x, gy1), fill=RED, width=2)
        draw.text((gx0 + 6, gy0 - 28), f"{r['label']}  准星高出目标（px）随时间，红线 = 按下左键", font=mid, fill=TEXT)
        draw.text((gx1 - 110, gy1 + 4), f"0 – {tmax / 1000:.1f} s", font=small, fill=GREY)
        draw.text((gx0 - 60, gy0 - 4), f"{umax:.0f}", font=small, fill=GREY)
        # The numbers.
        tx = width - 500
        lines = []
        for run, colour in runs:
            name = '改动前' if run is was else ('改动后' if was else '本次')
            shots = run['per_shot_px']
            lines.append((f"{name}  {run['rounds'] or '?'} 发", colour))
            lines.append((f"  最远 {run['peak_px']:.0f} px（{run['peak_px'] / (run['height'] / 100):.1f}% 屏高）", colour))
            lines.append((f"  最后一发时高出 {run['end_up_px']:.0f} px，停火 2 秒后 {run['settled_up_px']:.0f}", colour))
            lines.append((f"  抖动 {run['shake_px']:.1f} px，单帧最大跳 {run['max_jump_px']:.0f} px", colour))
            if len(shots) > 1:
                lines.append((f"  每发最大偏离 平均 {np.mean(shots):.0f} px", colour))
        for j, (text, colour) in enumerate(lines):
            draw.text((tx, top + 20 + j * 28), text, font=small, fill=colour)
    draw.text((16, canvas.height - 40), '同一面墙、同一位置；准星位移 = 画面里世界的平移（逐帧块匹配）加上红点在屏幕上的位置；'
              '软件渲染，一帧约 ' + str(results[0].get('frame_ms')) + ' ms', font=small, fill=GREY)
    canvas.save(path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--display')
    ap.add_argument('--out', required=True)
    ap.add_argument('--label', default='')
    ap.add_argument('--sight', choices=('dot', 'scope'), default='scope')
    ap.add_argument('--power', type=float, default=1.0)
    ap.add_argument('--auto', type=int, help='hold left mouse this many ms')
    ap.add_argument('--clicks', help='click left mouse at these seconds')
    ap.add_argument('--tail', type=float, default=2.0)
    ap.add_argument('--server-log')
    ap.add_argument('--player', default='ShotCam')
    ap.add_argument('--frames', action='store_true', help='also save every frame as DIR/<ms>.png')
    ap.add_argument('--chart')
    ap.add_argument('--before')
    ap.add_argument('--reanalyse', help='a summary.json or recoil.json to work the numbers out again from its timelines')
    args = ap.parse_args()
    if args.reanalyse:
        path = Path(args.reanalyse)
        data = json.loads(path.read_text(encoding='utf-8'))
        data = [summarise(r) for r in data] if isinstance(data, list) else summarise(data)
        Path(args.out).write_text(json.dumps(data, indent=1, ensure_ascii=False), encoding='utf-8')
        return
    if args.chart:
        chart(json.loads(Path(args.chart).read_text(encoding='utf-8')), Path(args.out), args.label,
              json.loads(Path(args.before).read_text(encoding='utf-8')) if args.before else None)
        return
    if not args.auto and not args.clicks:
        raise SystemExit('--auto or --clicks')
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    before, frames, rounds_at, last, logged = capture(args)
    summary = analyse(args, before, frames, rounds_at, last, logged)
    (out / 'recoil.json').write_text(json.dumps(summary, indent=1, ensure_ascii=False), encoding='utf-8')
    if args.frames:
        for f in frames:
            Image.fromarray(f['half']).save(out / f"{round(f['t'] * 1000):05d}.png")
    sheet(out / 'sheet.png', before, frames, summary)
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == '__main__':
    main()
