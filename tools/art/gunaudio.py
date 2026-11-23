"""Bullets, impacts, casings and handling foley, all original synthesis.

The gun reports themselves are recordings (gunsamples.py); everything around them is generated here
from a few primitives (filtered noise, envelopes, struck-metal modes):
  bullet     the supersonic crack (an N-wave) or subsonic whiz of a round passing close by
  impact     a round hitting concrete, metal, wood, glass, soil, flesh, a plate or a helmet
  casing     brass or a shotgun hull landing on hard or soft ground
  foley      magazines, charging handles, pumps and bolts for each action family, the dry click, and the
             gun coming up into the hands after a switch
  tinnitus   the ringing after a flashbang
"""
import zlib

import numpy as np
from scipy import signal

SR = 44100


# ---------------------------------------------------------------- primitives

def seed_of(*parts):
    """Stable across runs (str hash() is salted per process), so rebuilds produce identical files."""
    return zlib.crc32(repr(parts).encode())

def n_of(sec, sr=SR):
    return max(1, int(round(sec * sr)))


def tvec(n, sr=SR):
    return np.arange(n) / sr


def noise(rng, sec, sr=SR):
    return rng.standard_normal(n_of(sec, sr)) * 0.5


def _sos(kind, f, order, sr):
    nyq = sr / 2
    if kind == 'band':
        lo, hi = f
        return signal.butter(order, [max(10, lo) / nyq, min(0.99 * nyq, hi) / nyq], btype='band', output='sos')
    return signal.butter(order, min(0.99 * nyq, max(10, f)) / nyq, btype=kind, output='sos')


def hp(x, f, order=2, sr=SR):
    return signal.sosfilt(_sos('high', f, order, sr), x)


def lp(x, f, order=2, sr=SR):
    return signal.sosfilt(_sos('low', f, order, sr), x)


def bp(x, lo, hi, order=2, sr=SR):
    return signal.sosfilt(_sos('band', (lo, hi), order, sr), x)


def reson(x, f, q, sr=SR):
    b, a = signal.iirpeak(min(f, sr * 0.45) / (sr / 2), q)
    return signal.lfilter(b, a, x)


def env(n, attack, decay, sr=SR, hold=0.0):
    t = tvec(n, sr)
    a = np.clip(t / max(attack, 1e-6), 0, 1)
    d = np.exp(-np.maximum(0, t - attack - hold) / max(decay, 1e-6))
    return a * d


def sweep(f0, f1, sec, tau, sr=SR, phase=0.0):
    t = tvec(n_of(sec, sr), sr)
    f = f1 + (f0 - f1) * np.exp(-t / tau)
    return np.sin(2 * np.pi * np.cumsum(f) / sr + phase)


def modal(rng, partials, sec, sr=SR):
    """Struck metal: damped sines (freq Hz, decay s, amplitude)."""
    t = tvec(n_of(sec, sr), sr)
    out = np.zeros_like(t)
    for f, decay, amp in partials:
        out += amp * np.exp(-t / decay) * np.sin(2 * np.pi * f * t + rng.uniform(0, 2 * np.pi))
    return out


def place(buf, x, at, gain=1.0, sr=SR):
    o = int(round(at * sr))
    if o >= len(buf) or gain == 0:
        return buf
    seg = x[:len(buf) - o]
    buf[o:o + len(seg)] += seg * gain
    return buf


def peak(x, level):
    m = np.max(np.abs(x))
    return x if m < 1e-9 else x / m * level


def fade(x, sec, sr=SR):
    n = min(len(x), n_of(sec, sr))
    if x.ndim == 1:
        x[-n:] *= np.linspace(1, 0, n) ** 2
    else:
        x[-n:, :] *= (np.linspace(1, 0, n) ** 2)[:, None]
    return x


def resample(x, sr_to):
    if sr_to == SR:
        return x
    g = np.gcd(SR, sr_to)
    return signal.resample_poly(x, sr_to // g, SR // g, axis=0)


def tick(rng, sec=0.004, f=2200, gain=1.0):
    return hp(noise(rng, sec), f) * env(n_of(sec), 0.0002, sec / 4) * gain


def stereo(left, right):
    n = max(len(left), len(right))
    out = np.zeros((n, 2))
    out[:len(left), 0] = left
    out[:len(right), 1] = right
    return out


# ---------------------------------------------------------------- bullets, impacts, casings

def crack(v):
    rng = np.random.default_rng(1000 + v)
    out = np.zeros(n_of(.35))
    # N-wave: a sharp rise then a ramp down through zero, ~1.5 ms long.
    n = n_of(.0016)
    nw = np.concatenate([np.linspace(1, -1, n), np.zeros(4)])
    place(out, hp(nw, 900), 0, 1.0)
    place(out, hp(noise(rng, .006), 3000) * env(n_of(.006), .0001, .0012), 0, .8)
    zip_ = bp(noise(rng, .05), 3000 + v * 400, 7000) * env(n_of(.05), .001, .012)
    place(out, zip_, .002, .35)
    slap = lp(noise(rng, .25), 2500) * env(n_of(.25), .005, .06)
    place(out, slap, .01, .18)
    return fade(peak(out, .95), .05)


def whiz(v):
    rng = np.random.default_rng(1100 + v)
    sec = .3
    x = noise(rng, sec)
    t = tvec(len(x))
    f_hi = [5200, 4300, 6100][v % 3]
    # Doppler: the band falls as the bullet passes.
    pieces = [bp(x, f * .7, f * 1.25, order=2) for f in (f_hi, f_hi * .8, f_hi * .62)]
    w = np.clip((t - .08) / .1, 0, 1)
    y = np.where(w < .5, pieces[0] * (1 - 2 * w) + pieces[1] * 2 * w, pieces[1] * (2 - 2 * w) + pieces[2] * (2 * w - 1))
    g = np.exp(-((t - .1) / .045) ** 2)
    return fade(peak(y * g, .7), .05)


def impact(material, v):
    rng = np.random.default_rng(seed_of(material, v))
    s = [1, 1.1, .9][v % 3]
    out = np.zeros(n_of(.5))
    if material == 'concrete':
        place(out, lp(noise(rng, .06), 950 * s) * env(n_of(.06), .0004, .013), 0, 1.1)
        place(out, sweep(150 * s, 70, .05, .012) * env(n_of(.05), .0005, .016), 0, .6)
        place(out, hp(noise(rng, .012), 2000) * env(n_of(.012), .0002, .002), 0, .6)
        for _ in range(14):
            place(out, bp(noise(rng, .006), 1800, 6000) * env(n_of(.006), .0003, .0015), rng.uniform(.02, .3), rng.uniform(.05, .2) * (1 - _ / 16))
    elif material == 'metal':
        f = [640, 880, 745][v % 3] * rng.uniform(.97, 1.03)
        place(out, hp(noise(rng, .015), 1500) * env(n_of(.015), .0002, .003), 0, 1.0)
        place(out, modal(rng, [(f * r, .13 / (1 + i * .55), 1 / (1 + i * .5)) for i, r in enumerate((1, 1.58, 2.24, 2.92, 3.71, 4.93, 6.31, 8.1))], .48), .0005, .55)
    elif material == 'wood':
        place(out, modal(rng, [(238 * s, .03, 1), (512 * s, .022, .8), (893 * s, .016, .6), (1410 * s, .01, .4)], .2), 0, .8)
        place(out, lp(noise(rng, .03), 2100) * env(n_of(.03), .0003, .006), 0, .8)
        for _ in range(6):
            place(out, bp(noise(rng, .005), 1500, 3500) * env(n_of(.005), .0003, .0015), rng.uniform(.01, .15), .15)
    elif material == 'glass':
        place(out, hp(noise(rng, .01), 2500) * env(n_of(.01), .0002, .002), 0, 1.0)
        for _ in range(10):
            f = rng.uniform(3200, 9500)
            place(out, modal(rng, [(f, rng.uniform(.01, .03), 1), (f * 1.47, .01, .5)], .1), rng.uniform(0, .25) ** 1.5, rng.uniform(.1, .35))
    elif material == 'soft':
        place(out, lp(noise(rng, .08), 600 * s) * env(n_of(.08), .001, .02), 0, 1.0)
        place(out, sweep(110 * s, 55, .06, .015) * env(n_of(.06), .001, .02), 0, .5)
        for _ in range(8):
            place(out, bp(noise(rng, .01), 800, 2600) * env(n_of(.01), .001, .003), rng.uniform(.01, .2), .12)
    elif material == 'flesh':
        place(out, lp(noise(rng, .06), 1400 * s) * env(n_of(.06), .0006, .012), 0, 1.0)
        place(out, sweep(180 * s, 75, .08, .02) * env(n_of(.08), .0005, .025), 0, .8)
        place(out, bp(noise(rng, .05), 300, 900) * env(n_of(.05), .002, .02), .004, .5)
    elif material == 'armor':
        # Plate takes the round: a dull heavy slap with a short ring.
        place(out, lp(noise(rng, .05), 1600) * env(n_of(.05), .0003, .01), 0, 1.0)
        place(out, sweep(160, 80, .07, .02) * env(n_of(.07), .0005, .022), 0, .8)
        place(out, modal(rng, [(1900 * s, .05, 1), (3100 * s, .035, .6), (4700 * s, .02, .4)], .2), .001, .35)
    elif material == 'helmet':
        # The unmistakable "tink" of a round glancing off a helmet.
        place(out, hp(noise(rng, .008), 3000) * env(n_of(.008), .0001, .0015), 0, .8)
        place(out, modal(rng, [(2350 * s, .12, 1), (3720 * s, .08, .6), (5480 * s, .05, .4), (7610 * s, .03, .25)], .45), 0, .9)
    elif material == 'ricochet':
        x = noise(rng, .35)
        t = tvec(len(x))
        f = 2600 * s
        y = reson(x, f, 18) * np.exp(-t / .12)
        tone = np.sin(2 * np.pi * np.cumsum(f * (1 - .35 * t / .35)) / SR) * np.exp(-t / .1)
        place(out, y * .6 + tone * .25, 0, 1.0)
    else:
        raise ValueError(material)
    return fade(peak(out, .9), .05)


def casing(kind, v):
    rng = np.random.default_rng(seed_of(kind, v))
    out = np.zeros(n_of(.6))
    s = [1, 1.09, .93][v % 3]
    at, g = 0.0, 1.0
    bounces = 3 if kind == 'brass' else 2
    for i in range(bounces):
        c = s * rng.uniform(.96, 1.05)
        if kind == 'brass':
            place(out, modal(rng, [(4180 * c, rng.uniform(.03, .055), 1), (6830 * c, .03, .7), (9760 * c, .02, .5), (2470 * c, .012, .3)], .22), at, g * .5)
            place(out, tick(rng, .004, 3500), at, g * .4)
        elif kind == 'brass_soft':
            place(out, bp(noise(rng, .02), 1200, 4000) * env(n_of(.02), .0005, .004), at, g * .6)
        else:  # shotgun hull: plastic and brass base
            place(out, modal(rng, [(1250 * c, .02, 1), (2150 * c, .015, .6), (3400 * c, .01, .3)], .1), at, g * .5)
            place(out, lp(noise(rng, .02), 2500) * env(n_of(.02), .0005, .004), at, g * .5)
        at += rng.uniform(.085, .15) * (.75 if i else 1)
        g *= rng.uniform(.45, .62)
    return fade(peak(out, .6), .05)


# ---------------------------------------------------------------- reload foley

def friction(rng, sec, lo, hi):
    x = bp(noise(rng, sec), lo, hi)
    t = tvec(len(x))
    grain = np.repeat(rng.uniform(.25, 1, len(x) // 180 + 1), 180)[:len(x)]
    return x * grain * np.sin(np.pi * np.clip(t / sec, 0, 1) ** .7)


def foley(family, part, v=0):
    rng = np.random.default_rng(seed_of(family, part, v))
    heavy = family in ('ak', 'lmg', 'pistol_heavy', 'dmr')
    k = .82 if heavy else 1.0
    out = np.zeros(n_of(.7))
    if part == 'magout':
        place(out, modal(rng, [(3300 * k, .004, 1), (5100 * k, .003, .6)], .03), .004, .45)  # release button
        place(out, friction(rng, .17, 1700 * k, 3400 * k), .03, .5)
        place(out, lp(noise(rng, .06), 950) * env(n_of(.06), .001, .012), .2, .7)
        place(out, modal(rng, [(415 * k, .03, 1), (1140 * k, .018, .6), (2290 * k, .01, .4)], .12), .2, .4)
    elif part == 'magin':
        place(out, friction(rng, .14, 1250 * k, 2900 * k), 0, .55)
        place(out, sweep(190 * k, 88, .07, .02) * env(n_of(.07), .001, .022), .15, .8)
        place(out, lp(noise(rng, .05), 1600) * env(n_of(.05), .0008, .008), .15, .8)
        place(out, modal(rng, [(655 * k, .024, 1), (1710 * k, .014, .6), (3120 * k, .008, .4)], .1), .15, .4)
        place(out, modal(rng, [(3650 * k, .005, 1), (5600 * k, .004, .6), (2100 * k, .007, .5)], .04), .187, .42)
    elif part == 'charge':
        place(out, friction(rng, .07, 1500 * k, 5000), 0, .35)
        place(out, modal(rng, [(2000 * k, .01, 1), (3300 * k, .007, .6)], .06), .06, .5)
        place(out, modal(rng, [(1600 * k, .012, 1), (2700 * k, .008, .7), (700 * k, .02, .5)], .08), .14, .6)
        place(out, tick(rng, .005, 2500), .14, .4)
    elif part == 'pump':
        place(out, friction(rng, .09, 600, 3000), 0, .45)
        place(out, modal(rng, [(1300, .02, 1), (2200, .012, .6), (500, .03, .5)], .1), .07, .6)
        place(out, friction(rng, .08, 700, 3200), .17, .45)
        place(out, modal(rng, [(1600, .018, 1), (2600, .012, .6), (620, .03, .5)], .1), .24, .7)
    elif part == 'shell':
        place(out, friction(rng, .06, 900, 3000), 0, .35)
        place(out, modal(rng, [(1250, .015, 1), (2150, .01, .5), (3800, .006, .3)], .06), .05, .5)
        place(out, lp(noise(rng, .03), 1500) * env(n_of(.03), .0005, .006), .05, .5)
    elif part == 'bolt_open':
        place(out, modal(rng, [(1800, .012, 1), (2900, .008, .6)], .06), 0, .5)
        place(out, friction(rng, .12, 1400, 4500), .02, .45)
        place(out, modal(rng, [(1400, .016, 1), (2400, .01, .6), (650, .03, .5)], .1), .14, .55)
    elif part == 'bolt_close':
        place(out, friction(rng, .1, 1400, 4500), 0, .45)
        place(out, modal(rng, [(1600, .016, 1), (2700, .01, .6), (720, .03, .5)], .1), .1, .65)
        place(out, modal(rng, [(2200, .01, 1), (3500, .006, .5)], .05), .17, .5)
    elif part == 'draw':
        # The gun coming off the sling into the hands: cloth, the sling's buckle, and the gun settling.
        place(out, friction(rng, .16, 500 * k, 2400 * k), 0, .4)
        place(out, friction(rng, .07, 1600, 5200), .09, .22)
        place(out, modal(rng, [(1900 * k, .012, 1), (3100 * k, .008, .6), (820 * k, .02, .5)], .08), .19, .45)
    elif part == 'dry':
        place(out, modal(rng, [(2840 * k, .004, 1), (4130 * k, .004, .7), (6320, .003, .5), (1210 * k, .008, .4)], .05), 0, .7)
        place(out, tick(rng, .006, 2000), 0, .6)
        place(out, modal(rng, [(930 * k, .018, 1), (1870 * k, .012, .5)], .08), .016, .16)
    else:
        raise ValueError(part)
    return fade(peak(out, .8), .05)


def tinnitus():
    """Ears ringing after a flashbang (ThrowableService plays it to the flashed player only).

    One high tone with a slow beat against a near neighbour, holding for about a second and then
    fading over the next four, so it outlasts even the longest white-out. Stereo, so the client
    plays it unpositioned, inside the listener's head; the volume scales with how hard they were hit.
    """
    sec = 5.0
    t = tvec(n_of(sec))
    ring = (np.sin(2 * np.pi * 3720 * t) + .5 * np.sin(2 * np.pi * 3733 * t + 1) + .12 * np.sin(2 * np.pi * 7452 * t + 2))
    ring *= (1 - np.exp(-t / .05)) * np.exp(-np.maximum(0, t - 1.0) / 1.1)
    return fade(peak(stereo(ring, np.roll(ring, 37)), .3), .4)


# ---------------------------------------------------------------- catalogue for build.py

def catalogue():
    """[(event key, [(file path, data, sample rate)], attenuation)]: every sound this module provides.
    The gun reports themselves come from gunsamples.py."""
    out = []

    def files(prefix, makers, sr=SR):
        return [('%s_%d' % (prefix, i), m(), sr) for i, m in enumerate(makers)]

    out.append(('bullet.crack', files('bullet/crack', [lambda v=v: crack(v) for v in range(3)]), 16))
    out.append(('bullet.whiz', files('bullet/whiz', [lambda v=v: whiz(v) for v in range(3)]), 16))
    for material in ('concrete', 'metal', 'wood', 'glass', 'soft', 'flesh', 'armor', 'helmet', 'ricochet'):
        out.append(('impact.%s' % material, files('impact/' + material, [lambda v=v: impact(material, v) for v in range(3)], 32000), 20))
    for kind in ('brass', 'brass_soft', 'shell'):
        out.append(('casing.%s' % kind, files('casing/' + kind, [lambda v=v: casing(kind, v) for v in range(3)], 32000), 12))
    for family in ('ar', 'ak', 'dmr', 'lmg', 'smg', 'pdw', 'pistol', 'pistol_heavy'):
        for part in ('magout', 'magin', 'charge', 'dry', 'draw'):
            out.append(('gun.foley.%s.%s' % (family, part), files('gun/foley/%s_%s' % (family, part), [lambda: foley(family, part)], 32000), 12))
    for family, parts in (('pump', ('pump', 'shell', 'dry', 'draw')), ('bolt', ('bolt_open', 'bolt_close', 'magout', 'magin', 'dry', 'draw'))):
        for part in parts:
            out.append(('gun.foley.%s.%s' % (family, part), files('gun/foley/%s_%s' % (family, part), [lambda: foley(family, part)], 32000), 12))
    out.append(('status.tinnitus', [('status/tinnitus', tinnitus(), SR)], 16))
    return out
