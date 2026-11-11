"""Layered firearm audio, all original synthesis.

A shot is built the way game audio teams layer recorded guns, but from generated parts:
  crack      supersonic snap, a few milliseconds of broadband noise
  transient  the first ~20 ms of muzzle blast, bright and punchy
  thump      a falling sine for the low-end hit in the chest
  body       the main blast: filtered noise with a fast and a slow decay
  resonance  the barrel's ring, a resonant band that tells guns apart
  hiss       gas jetting out of the muzzle
  mechanics  bolt or slide travelling back and slamming home (two modal clicks plus springs)
Every gun has its own parameters (calibre, barrel, action) and four round-robin variants.

From one core the build derives what each listener needs: a wide stereo first-person shot for the
shooter, near / mid / far versions for everyone else (distance strips the highs and smears the
report), a muffled version heard through walls, and suppressed versions. Tails (the environment's
answer to the shot) are separate files per gun family, outdoor and indoor, made by convolving a
core with a synthesised impulse response; the server plays the right one after the last shot of a
burst so automatic fire stays crisp instead of turning to mush.
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


def env2(n, attack, fast, slow, mix, sr=SR):
    """Two-stage decay: most energy dies in `fast`, a `1 - mix` share lingers for `slow`."""
    t = tvec(n, sr)
    a = np.clip(t / max(attack, 1e-6), 0, 1)
    tt = np.maximum(0, t - attack)
    return a * (mix * np.exp(-tt / fast) + (1 - mix) * np.exp(-tt / slow))


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


def softclip(x, drive):
    return np.tanh(drive * x) / np.tanh(drive)


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


# ---------------------------------------------------------------- gun profiles

# Mechanical action families: two struck-metal events (bolt back, bolt home) plus a spring or link.
ACTIONS = {
    'ar': dict(a=[(2150, .007, 1), (3420, .006, .7), (5230, .004, .5), (880, .012, .5)],
               b=[(1780, .008, 1), (2890, .006, .6), (640, .02, .5)],
               spring=[(712, .05, 1), (1466, .03, .4)], spring_gain=.12, t=.034, gap=.027, gain=1.0),
    'dmr': dict(a=[(1980, .008, 1), (3150, .006, .7), (4800, .004, .45), (820, .014, .5)],
                b=[(1620, .009, 1), (2650, .007, .6), (600, .022, .5)],
                spring=[(650, .06, 1), (1300, .03, .35)], spring_gain=.1, t=.036, gap=.03, gain=.95),
    'ak': dict(a=[(1550, .01, 1), (2480, .008, .8), (3900, .005, .5), (720, .016, .6)],
               b=[(1300, .012, 1), (2100, .009, .7), (520, .025, .6)],
               spring=[(430, .04, 1), (980, .025, .5)], spring_gain=.2, t=.038, gap=.03, gain=1.2),
    'lmg': dict(a=[(980, .018, 1), (1650, .012, .7), (2700, .008, .4)],
                b=[(820, .02, 1), (1400, .014, .6), (2300, .01, .3)],
                spring=[(3600, .008, 1), (5100, .006, .7), (2400, .01, .5)], spring_gain=.3, t=.04, gap=.032, gain=1.25),
    'smg': dict(a=[(1250, .012, 1), (2050, .009, .7), (3300, .006, .4)],
                b=[(1600, .008, .8), (2600, .006, .5), (900, .015, .4)],
                spring=[(560, .03, 1)], spring_gain=.12, t=.026, gap=.02, gain=1.1),
    'pdw': dict(a=[(2600, .006, 1), (4100, .005, .6), (6100, .003, .4)],
                b=[(2200, .006, .9), (3500, .005, .5), (1100, .01, .3)],
                spring=[(820, .03, 1)], spring_gain=.08, t=.024, gap=.019, gain=.9),
    'pistol': dict(a=[(2840, .004, 1), (4130, .004, .7), (6320, .003, .5), (1210, .008, .4)],
                   b=[(2300, .006, 1), (3600, .005, .6), (1100, .01, .4)],
                   spring=[(930, .018, 1), (1870, .012, .5)], spring_gain=.15, t=.014, gap=.024, gain=.9),
    'pistol_heavy': dict(a=[(2100, .006, 1), (3300, .005, .7), (5200, .003, .4), (900, .012, .5)],
                         b=[(1750, .008, 1), (2800, .006, .6), (800, .014, .4)],
                         spring=[(700, .022, 1), (1500, .014, .5)], spring_gain=.18, t=.018, gap=.03, gain=1.1),
    'pump': None,
    'bolt': None,
}

BASE = dict(crack=.8, snap_hp=2600, thump=(150, 55, .03), thump_gain=.75, body_hp=280, body_lp=3000,
            body=(.0012, .022, .11, .55), body_gain=1.5, res=(780, 2.2, .02), res_gain=.9, hiss=.32,
            transient=1.1, drive=1.7, length=.55, action='ar', report=1.0)


def profile(**kw):
    p = dict(BASE)
    p.update(kw)
    return p


# gun id -> parameters. Calibre sets crack and thump, barrel sets resonance and body colour,
# the action sets the mechanics. 'report' scales how loud the gun is relative to the others.
GUNS = {
    'p18': profile(crack=.35, snap_hp=3000, thump=(190, 78, .022), thump_gain=.6, body_hp=380, body_lp=4300,
                   body=(.001, .015, .07, .6), body_gain=1.3, res=(1150, 2.6, .014), res_gain=.85, hiss=.22,
                   transient=1.25, drive=1.8, length=.42, action='pistol', report=.7),
    'd50': profile(crack=.55, snap_hp=2400, thump=(135, 48, .04), thump_gain=1.0, body_hp=200, body_lp=3000,
                   body=(.0014, .026, .14, .5), body_gain=1.7, res=(640, 2.0, .022), res_gain=1.0, hiss=.3,
                   transient=1.3, drive=2.0, length=.6, action='pistol_heavy', report=.95),
    'sv9': profile(crack=.3, snap_hp=3000, thump=(175, 72, .022), thump_gain=.6, body_hp=340, body_lp=3900,
                   body=(.001, .016, .075, .62), body_gain=1.3, res=(980, 2.4, .016), res_gain=.85, hiss=.22,
                   transient=1.15, drive=1.7, length=.42, action='smg', report=.7),
    'v57': profile(crack=.75, snap_hp=3400, thump=(170, 70, .02), thump_gain=.55, body_hp=420, body_lp=5200,
                   body=(.001, .014, .07, .62), body_gain=1.25, res=(1240, 2.8, .014), res_gain=.8, hiss=.35,
                   transient=1.2, drive=1.7, length=.42, action='pdw', report=.72),
    'm4c': profile(crack=.95, snap_hp=2800, thump=(150, 56, .028), thump_gain=.72, body_hp=300, body_lp=3300,
                   body=(.0012, .02, .1, .56), body_gain=1.45, res=(820, 2.3, .02), res_gain=.9, hiss=.34,
                   transient=1.15, drive=1.75, length=.52, action='ar', report=.85),
    'k47': profile(crack=.9, snap_hp=2300, thump=(128, 46, .034), thump_gain=.9, body_hp=230, body_lp=2700,
                   body=(.0014, .026, .13, .52), body_gain=1.6, res=(700, 2.0, .024), res_gain=1.0, hiss=.3,
                   transient=1.1, drive=1.85, length=.58, action='ak', report=.9),
    'scr': profile(crack=1.05, snap_hp=2300, thump=(118, 42, .038), thump_gain=.95, body_hp=210, body_lp=2500,
                   body=(.0014, .028, .15, .5), body_gain=1.65, res=(640, 2.0, .026), res_gain=1.0, hiss=.32,
                   transient=1.15, drive=1.9, length=.62, action='dmr', report=.95),
    'kl7': profile(crack=.9, snap_hp=2200, thump=(124, 44, .036), thump_gain=.95, body_hp=220, body_lp=2600,
                   body=(.0014, .028, .14, .52), body_gain=1.6, res=(620, 2.0, .028), res_gain=1.05, hiss=.3,
                   transient=1.1, drive=1.85, length=.6, action='lmg', report=.95),
    'r12': profile(crack=.35, snap_hp=2000, thump=(100, 36, .05), thump_gain=1.25, body_hp=140, body_lp=1900,
                   body=(.0018, .04, .2, .45), body_gain=1.8, res=(430, 1.6, .03), res_gain=1.0, hiss=.2,
                   transient=1.2, drive=2.1, length=.75, action='pump', report=1.0),
    'dm14': profile(crack=1.15, snap_hp=2400, thump=(112, 40, .04), thump_gain=1.0, body_hp=200, body_lp=2500,
                    body=(.0014, .03, .16, .5), body_gain=1.65, res=(600, 2.0, .028), res_gain=1.0, hiss=.34,
                    transient=1.2, drive=1.9, length=.66, action='dmr', report=1.0),
    'awr': profile(crack=1.45, snap_hp=2100, thump=(96, 32, .05), thump_gain=1.15, body_hp=160, body_lp=2300,
                   body=(.0016, .036, .22, .45), body_gain=1.8, res=(520, 1.8, .034), res_gain=1.05, hiss=.4,
                   transient=1.35, drive=2.0, length=.8, action='bolt', report=1.0),
}

# Which tail family answers each gun (tails are shared to keep the pack small).
TAIL_FAMILY = {'p18': 'light', 'sv9': 'light', 'v57': 'light', 'd50': 'rifle', 'm4c': 'rifle', 'k47': 'heavy',
               'scr': 'heavy', 'kl7': 'heavy', 'dm14': 'heavy', 'r12': 'heavy', 'awr': 'sniper'}
TAIL_SOURCE = {'light': 'sv9', 'rifle': 'm4c', 'heavy': 'scr', 'sniper': 'awr'}

VARIANT_FREQ = [1.0, .955, 1.045, .985]
VARIANT_TONE = [1.0, 1.12, .9, 1.05]


# ---------------------------------------------------------------- the shot

def mechanics(rng, p, scale):
    a = ACTIONS.get(p['action'])
    out = np.zeros(n_of(.2))
    if not a:
        return out
    t0 = a['t'] + rng.uniform(-.002, .002)
    place(out, modal(rng, [(f * scale, d, g) for f, d, g in a['a']], .06), t0, .26)
    place(out, tick(rng, .006, 3000), t0, .3)
    t1 = t0 + a['gap'] + rng.uniform(-.002, .002)
    place(out, modal(rng, [(f * scale, d, g) for f, d, g in a['b']], .08), t1, .2)
    place(out, tick(rng, .005, 2400), t1, .22)
    place(out, modal(rng, [(f * scale, d, g) for f, d, g in a['spring']], .12), t1 + .003, a['spring_gain'])
    return out * a['gain']


def core(p, v, seed, *, mech=1.0, crack=1.0, body=1.0, thump=1.0, hiss=1.0, suppressed=False):
    """One mono shot without its environment."""
    rng = np.random.default_rng(seed)
    nf, nt = VARIANT_FREQ[v % 4], VARIANT_TONE[v % 4]
    out = np.zeros(n_of(p['length']))
    # crack: supersonic snap
    c = hp(noise(rng, .012), p['snap_hp'] * nt) * env(n_of(.012), .00008, .0011)
    place(out, c, 0, 2.4 * p['crack'] * crack)
    # transient: first punch of the blast
    tr = lp(hp(noise(rng, .025), 1100), 8500) * env(n_of(.025), .0002, .0032)
    place(out, tr, .0002, p['transient'] * body)
    # thump: low-end hit
    f0, f1, tau = p['thump']
    th = sweep(f0 * nf, f1 * nf, .14, tau * .9) * env(n_of(.14), .0006, tau)
    place(out, th, 0, p['thump_gain'] * thump)
    # body: the main blast
    atk, fast, slow, mix = p['body']
    bl = p['body_lp'] * nf * nt * (.55 if suppressed else 1.0)
    b = lp(hp(noise(rng, p['length']), p['body_hp'] * nf), bl, order=2) * env2(n_of(p['length']), atk, fast, slow * (.5 if suppressed else 1), mix)
    place(out, b, 0, p['body_gain'] * body)
    # barrel resonance
    rf, q, rd = p['res']
    r = reson(noise(rng, .2), rf * nf, q) * env(n_of(.2), .001, rd)
    place(out, r, 0, p['res_gain'] * body * 1.6)
    # gas hiss
    h = hp(noise(rng, .18), 4800) * env(n_of(.18), .001, .04)
    place(out, h, .001, p['hiss'] * hiss)
    if suppressed:
        # Gas through the baffles: a short, dark "pfft".
        pf = bp(noise(rng, .08), 350, 2400) * env(n_of(.08), .001, .018)
        place(out, pf, 0, .9)
    if mech:
        place(out, mechanics(rng, p, nf), 0, mech)
    out = softclip(peak(out, 1.0), p['drive'])
    return fade(out, .05)


def stereo(left, right):
    n = max(len(left), len(right))
    out = np.zeros((n, 2))
    out[:len(left), 0] = left
    out[:len(right), 1] = right
    return out


def first_person(gid, v, suppressed=False):
    """What the shooter hears: wide, close, mechanics loud, extra low end."""
    p = GUNS[gid]
    seed = seed_of(gid, v, suppressed, 'fp')
    kw = dict(mech=1.6 if suppressed else .55, thump=1.25, crack=.45 if suppressed else 1.0, body=.13 if suppressed else 1.0,
              hiss=.25 if suppressed else 1.0, suppressed=suppressed)
    left = core(p, v, seed, **kw)
    right = core(p, v, seed + 7, **kw)
    # Same crack/thump/mechanics in both ears comes from the shared seed; the noise beds differ a bit
    # through the second seed, which keeps the blast wide. Ground bounce, a few ms late per side.
    for ch, delay in ((left, .0042), (right, .0051)):
        refl = lp(np.concatenate([np.zeros(n_of(delay)), ch])[:len(ch)], 3500) * .32
        ch += refl
    mix = .5 * (left + right)
    st = stereo(.75 * left + .25 * mix, .75 * right + .25 * mix)
    return peak(st, .7 if suppressed else .97)


def third_person(gid, v, band, suppressed=False):
    p = GUNS[gid]
    seed = seed_of(gid, v, band, suppressed)
    if suppressed:
        x = core(p, v, seed, mech=.9, crack=.55, body=.12, thump=.4, hiss=.2, suppressed=True)
        x = lp(x, 7000)
        return peak(x, .6)
    if band == 'near':
        x = core(p, v, seed, mech=.22)
        x = x + lp(np.concatenate([np.zeros(n_of(.006)), x])[:len(x)], 4000) * .3
        return peak(lp(x, 12000), .95)
    if band == 'mid':
        x = core(p, v, seed, mech=0, crack=.5)
        x = np.concatenate([x, np.zeros(n_of(.45))])
        rng = np.random.default_rng(seed + 1)
        # Distance smears the report: a short diffuse spread plus a couple of ground/building returns.
        ir = np.zeros(n_of(.35))
        ir[0] = 1
        place(ir, lp(noise(rng, .3), 2500) * env(n_of(.3), .004, .07), .002, .35)
        for at, g in ((.045, .25), (.11, .14), (.19, .08)):
            place(ir, lp(noise(rng, .01), 2000) * env(n_of(.01), .0005, .003), at + rng.uniform(-.006, .006), g * 3)
        y = signal.fftconvolve(x, ir)[:len(x)]
        y = hp(lp(y, 5200, order=2), 70)
        return fade(peak(y, .9), .12)
    if band == 'far':
        x = core(p, v, seed, mech=0, crack=0, thump=1.3)
        x = np.concatenate([x, np.zeros(n_of(1.2))])
        rng = np.random.default_rng(seed + 2)
        ir = np.zeros(n_of(1.4))
        ir[0] = 1
        place(ir, lp(noise(rng, 1.3), 900) * env(n_of(1.3), .02, .32), .01, .5)
        at = .08
        g = .55
        while at < 1.1:
            place(ir, lp(noise(rng, .03), 1100) * env(n_of(.03), .002, .01), at, g * 2)
            at += rng.uniform(.09, .22)
            g *= .72
        y = signal.fftconvolve(x, ir)[:len(x)]
        y = hp(lp(y, 1300, order=4), 45)
        return fade(peak(y, .85), .3)
    if band == 'muff':
        x = core(p, v, seed, mech=0, crack=0, thump=1.4)
        x = np.concatenate([x, np.zeros(n_of(.25))])
        y = lp(x, 620, order=4)
        y = y + reson(y, 120, 3) * .4
        return fade(peak(y, .85), .15)
    raise ValueError(band)


# ---------------------------------------------------------------- environment tails

def ir_outdoor(rng, length):
    ir = np.zeros(n_of(length))
    taps = [(.058, .5, 4200), (.097, .4, 3300), (.139, .3, 2600), (.176, .22, 2000), (.262, .14, 1500),
            (.41, .09, 1100), (.63, .05, 800)]
    for at, g, f in taps:
        blip = lp(hp(noise(rng, .09), 300), f) * env(n_of(.09), .002, .016)
        place(ir, blip, at + rng.uniform(-.006, .006), g * rng.uniform(.8, 1.1))
    # Diffuse rumble from the ground and distant surfaces, darkening as it decays.
    late = noise(rng, length)
    pieces = [lp(late, f) for f in (2600, 1400, 700, 380)]
    t = tvec(len(late))
    w = np.clip(t / (length * .45), 0, 1)
    rumble = np.where(w < 1 / 3, pieces[0] * (1 - 3 * w) + pieces[1] * 3 * w,
                      np.where(w < 2 / 3, pieces[1] * (2 - 3 * w) + pieces[2] * (3 * w - 1),
                               pieces[2] * (3 - 3 * w) + pieces[3] * (3 * w - 2)))
    rumble = hp(rumble, 55) * env(len(late), .03, length * .28)
    place(ir, rumble, .02, .5)
    return ir


def ir_indoor(rng, length):
    ir = np.zeros(n_of(length))
    # Dense early reflections off nearby walls.
    for _ in range(40):
        at = rng.uniform(.002, .035)
        place(ir, lp(noise(rng, .004), 6000) * env(n_of(.004), .0002, .001), at, rng.uniform(.1, .35) * (1 - at * 15))
    late = lp(noise(rng, length), 3200) * env(n_of(length), .008, length * .2)
    # Room modes give the boom of a concrete room.
    modes = sum(reson(late, f, 9) * g for f, g in ((54, 1.0), (81, .8), (117, .6), (163, .45), (228, .3)))
    place(ir, late + modes * .9, .004, .55)
    # Flutter echo between parallel walls.
    for k in range(1, 9):
        place(ir, lp(noise(rng, .05), 3800 / (1 + k * .35)) * env(n_of(.05), .001, .009), .047 * k + rng.uniform(-.003, .003), .42 * .74 ** k)
    return ir


def tail(family, environment, v):
    gid = TAIL_SOURCE[family]
    p = GUNS[gid]
    seed = seed_of(family, environment, v, 'tail')
    rng = np.random.default_rng(seed)
    x = core(p, v, seed, mech=0, crack=.3)
    length = {'light': 1.4, 'rifle': 1.9, 'heavy': 2.3, 'sniper': 3.0}[family]
    if environment == 'in':
        length = {'light': .9, 'rifle': 1.1, 'heavy': 1.3, 'sniper': 1.5}[family]
        ir = ir_indoor(rng, length)
        level = .62
    else:
        ir = ir_outdoor(rng, length)
        level = .5
    y = signal.fftconvolve(x, ir)[:n_of(length + .1)]
    # The direct sound is played by the shot itself; the tail only carries what comes back.
    y[:n_of(.025)] *= np.linspace(0, 1, n_of(.025)) ** 2
    y = hp(y, 45)
    return fade(peak(y, level), .25)


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
    elif part == 'dry':
        place(out, modal(rng, [(2840 * k, .004, 1), (4130 * k, .004, .7), (6320, .003, .5), (1210 * k, .008, .4)], .05), 0, .7)
        place(out, tick(rng, .006, 2000), 0, .6)
        place(out, modal(rng, [(930 * k, .018, 1), (1870 * k, .012, .5)], .08), .016, .16)
    else:
        raise ValueError(part)
    return fade(peak(out, .8), .05)


def tinnitus():
    sec = 2.6
    t = tvec(n_of(sec))
    ring = (np.sin(2 * np.pi * 3150 * t) + .7 * np.sin(2 * np.pi * 4013 * t + 1) + .35 * np.sin(2 * np.pi * 5310 * t + 2))
    ring *= (1 - np.exp(-t / .12)) * np.exp(-t / .7)
    return peak(stereo(ring, np.roll(ring, 37)), .3)


# ---------------------------------------------------------------- catalogue for build.py

def catalogue():
    """[(event key, [(file path, data, sample rate)], attenuation)]: every sound this module provides."""
    out = []

    def files(prefix, makers, sr=SR):
        return [('%s_%d' % (prefix, i), m(), sr) for i, m in enumerate(makers)]

    for gid in GUNS:
        base = 'gun/%s/' % gid
        out.append(('gun.%s.fp' % gid, files(base + 'fp', [lambda v=v: first_person(gid, v) for v in range(4)]), 16))
        out.append(('gun.%s.near' % gid, files(base + 'near', [lambda v=v: third_person(gid, v, 'near') for v in range(4)]), 16))
        out.append(('gun.%s.mid' % gid, files(base + 'mid', [lambda v=v: third_person(gid, v, 'mid') for v in range(3)], 32000), 16))
        out.append(('gun.%s.far' % gid, files(base + 'far', [lambda v=v: third_person(gid, v, 'far') for v in range(2)], 22050), 16))
        out.append(('gun.%s.muff' % gid, files(base + 'muff', [lambda v=v: third_person(gid, v, 'muff') for v in range(2)], 22050), 16))
        out.append(('gun.%s.fp_s' % gid, files(base + 'fp_s', [lambda v=v: first_person(gid, v, True) for v in range(3)]), 16))
        out.append(('gun.%s.near_s' % gid, files(base + 'near_s', [lambda v=v: third_person(gid, v, 'near', True) for v in range(3)], 32000), 16))
    for family in TAIL_SOURCE:
        for environment in ('out', 'in'):
            out.append(('gun.tail.%s.%s' % (family, environment),
                        files('gun/tail/%s_%s' % (family, environment), [lambda v=v: tail(family, environment, v) for v in range(2)], 32000), 16))
    out.append(('bullet.crack', files('bullet/crack', [lambda v=v: crack(v) for v in range(3)]), 16))
    out.append(('bullet.whiz', files('bullet/whiz', [lambda v=v: whiz(v) for v in range(3)]), 16))
    for material in ('concrete', 'metal', 'wood', 'glass', 'soft', 'flesh', 'armor', 'helmet', 'ricochet'):
        out.append(('impact.%s' % material, files('impact/' + material, [lambda v=v: impact(material, v) for v in range(3)], 32000), 20))
    for kind in ('brass', 'brass_soft', 'shell'):
        out.append(('casing.%s' % kind, files('casing/' + kind, [lambda v=v: casing(kind, v) for v in range(3)], 32000), 12))
    for family in ('ar', 'ak', 'dmr', 'lmg', 'smg', 'pdw', 'pistol', 'pistol_heavy'):
        for part in ('magout', 'magin', 'charge', 'dry'):
            out.append(('gun.foley.%s.%s' % (family, part), files('gun/foley/%s_%s' % (family, part), [lambda: foley(family, part)], 32000), 12))
    for family, parts in (('pump', ('pump', 'shell', 'dry')), ('bolt', ('bolt_open', 'bolt_close', 'magout', 'magin', 'dry'))):
        for part in parts:
            out.append(('gun.foley.%s.%s' % (family, part), files('gun/foley/%s_%s' % (family, part), [lambda: foley(family, part)], 32000), 12))
    out.append(('status.tinnitus', [('status/tinnitus', tinnitus(), SR)], 16))
    return out
