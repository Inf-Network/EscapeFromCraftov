"""Synthesised sound effects (all original). Mono 44.1 kHz Ogg Vorbis, encoded by oggenc.py."""
import os
import zlib

import numpy as np
from scipy import signal

SR = 44100
rng = np.random.default_rng(1234)


def t(sec):
    return np.arange(int(SR * sec)) / SR


def noise(sec):
    return rng.uniform(-1, 1, int(SR * sec))


def env(x, attack=0.001, decay=0.1, curve=1.0):
    n = len(x)
    tt = np.arange(n) / SR
    a = np.clip(tt / max(attack, 1e-5), 0, 1)
    d = np.exp(-np.maximum(0, tt - attack) / max(decay, 1e-5)) ** curve
    return x * a * d


def bp(x, lo, hi, order=2):
    sos = signal.butter(order, [lo / (SR / 2), min(0.99, hi / (SR / 2))], btype='band', output='sos')
    return signal.sosfilt(sos, x)


def lp(x, f, order=2):
    sos = signal.butter(order, min(0.99, f / (SR / 2)), btype='low', output='sos')
    return signal.sosfilt(sos, x)


def hp(x, f, order=2):
    sos = signal.butter(order, f / (SR / 2), btype='high', output='sos')
    return signal.sosfilt(sos, x)


def sine(freq, sec, phase=0.0):
    tt = t(sec)
    if callable(freq):
        f = freq(tt)
        return np.sin(2 * np.pi * np.cumsum(f) / SR + phase)
    return np.sin(2 * np.pi * freq * tt + phase)


def pad(x, sec):
    out = np.zeros(int(SR * sec))
    out[:min(len(x), len(out))] = x[:len(out)]
    return out


def mixdown(*parts, length=None):
    n = length or max(len(p) for p, _ in parts)
    out = np.zeros(n)
    for p, offset in parts:
        o = int(offset * SR)
        seg = p[:max(0, n - o)]
        out[o:o + len(seg)] += seg
    return out


def norm(x, peak=0.9):
    m = np.max(np.abs(x)) or 1
    return x / m * peak


def reverb(x, decay=0.35, taps=((0.031, 0.5), (0.047, 0.4), (0.071, 0.3), (0.113, 0.22), (0.161, 0.15))):
    out = np.copy(x)
    for delay, gain in taps:
        d = int(delay * SR)
        y = np.zeros_like(x)
        y[d:] = x[:-d] * gain
        out += lp(y, 4000)
    tail = env(noise(len(x) / SR), 0.01, decay) * 0.02
    return out + lp(tail, 3000)


# ---------------------------------------------------------------- guns

def gunshot(crack_f=4500, body_lo=300, body_hi=3000, body_decay=0.06, thump_f=90, thump_decay=0.05, tail=0.35,
            crack_gain=0.8, body_gain=1.0, thump_gain=0.9, length=0.9):
    crack = env(hp(noise(0.03), crack_f), 0.0002, 0.006) * crack_gain
    body = env(bp(noise(0.4), body_lo, body_hi), 0.0005, body_decay) * body_gain
    thump = env(sine(lambda tt: thump_f * (1 + 2 * np.exp(-tt * 60)), 0.3), 0.0005, thump_decay) * thump_gain
    tailn = env(lp(noise(length), 1800), 0.01, tail) * 0.35
    mech = env(bp(noise(0.02), 2500, 6000), 0.0002, 0.004) * 0.25
    x = mixdown((crack, 0), (body, 0), (thump, 0), (tailn, 0.005), (mech, 0.035), length=int(SR * length))
    x = np.tanh(x * 2.2)
    return norm(reverb(x, tail))


def click(freq=3500, sec=0.03, gain=1.0):
    return env(bp(noise(sec), freq * 0.6, freq * 1.6), 0.0002, sec / 5) * gain


def metal(freqs=(1800, 2710, 4130), decay=0.12, sec=0.4):
    out = sum(sine(f, sec) * (0.6 ** i) for i, f in enumerate(freqs))
    return env(out, 0.0005, decay)


def swish(sec=0.25, lo=600, hi=5000):
    n = noise(sec)
    return env(bp(n, lo, hi), sec * 0.4, sec * 0.25) * 0.6


def tone(freq, sec, decay=None, harmonics=(1.0, 0.35, 0.12)):
    out = sum(sine(freq * (i + 1), sec) * h for i, h in enumerate(harmonics))
    return env(out, 0.004, decay or sec / 3)


def chord(freqs, sec, stagger=0.0, decay=None):
    parts = [(tone(f, sec, decay), i * stagger) for i, f in enumerate(freqs)]
    return mixdown(*parts)


SOUNDS = {
    'gun/empty': lambda: norm(mixdown((metal((3600, 5400), 0.05, 0.2), 0), (click(4000, 0.02), 0))) * 0.7,
    'gun/reload_done': lambda: norm(mixdown((click(2200, 0.04), 0), (swish(0.08, 1500, 6000), 0.02), (click(3200, 0.04), 0.12))),
    'gun/mode': lambda: norm(click(4200, 0.02)) * 0.8,
    'gun/ads': lambda: norm(swish(0.15, 400, 2500)) * 0.5,
    'hit/body': lambda: norm(mixdown((env(sine(lambda tt: 180 - tt * 300, 0.12), 0.001, 0.03), 0), (env(lp(noise(0.06), 1500), 0.001, 0.015) * 0.6, 0))) * 0.8,
    'hit/head': lambda: norm(mixdown((tone(2350, 0.35, 0.08, (1, 0.4, 0.15)), 0), (env(sine(160, 0.1), 0.001, 0.02) * 0.6, 0))),
    'hit/kill': lambda: norm(mixdown((tone(1760, 0.4, 0.1), 0), (tone(2637, 0.45, 0.12), 0.07))),
    # A knock-down: a thud and two falling notes, lower than the rising kill chime.
    'hit/knock': lambda: norm(mixdown((env(sine(lambda tt: 170 - tt * 300, 0.14), 0.001, 0.04) * 0.7, 0),
                                      (tone(1319, 0.3, 0.07), 0), (tone(988, 0.4, 0.1), 0.08))),
    'loot/open': lambda: norm(mixdown((swish(0.3, 300, 2500), 0), (click(1200, 0.05), 0.25))) * 0.8,
    'loot/search_tick': lambda: norm(click(5000, 0.015)) * 0.35,
    'loot/reveal': lambda: norm(tone(880, 0.25, 0.07)) * 0.6,
    'loot/reveal_purple': lambda: norm(chord((784, 1175), 0.5, 0.06, 0.15)) * 0.8,
    'loot/reveal_gold': lambda: norm(chord((988, 1319, 1568, 1976), 0.9, 0.07, 0.25)),
    'loot/reveal_red': lambda: norm(mixdown((env(sine(55, 1.6), 0.02, 0.6) * 0.8, 0), (chord((659, 880, 1109, 1319, 1760), 1.6, 0.09, 0.45), 0.05))),
    'loot/pickup': lambda: norm(mixdown((swish(0.1, 900, 4000), 0), (click(2500, 0.03, 0.7), 0.05))) * 0.6,
    'extract/start': lambda: norm(mixdown((env(bp(noise(0.25), 1000, 3500), 0.005, 0.08) * 0.5, 0), (tone(1046, 0.2, 0.06), 0.15))),
    'extract/beep': lambda: norm(tone(1200, 0.12, 0.05, (1, 0.2))) * 0.7,
    'extract/success': lambda: norm(chord((523, 659, 784, 1046), 1.2, 0.1, 0.4)),
    'ui/cinematic': lambda: norm(mixdown((env(sine(lambda tt: 45 + tt * 8, 3.5), 1.2, 1.5) * 0.9, 0), (swish(2.2, 200, 1800) * 0.8, 0.6),
                                         (env(sine(90, 2.0), 0.05, 0.6) * 0.6, 2.8))),
    'ui/deploy': lambda: norm(mixdown((swish(0.6, 300, 3000), 0), (env(sine(lambda tt: 70 - tt * 30, 0.6), 0.002, 0.15), 0.4))),
    'ui/match_found': lambda: norm(chord((660, 880, 1320), 0.9, 0.12, 0.3)),
    'ui/death': lambda: norm(mixdown((env(sine(lambda tt: 220 - tt * 120, 1.5), 0.01, 0.7), 0), (env(lp(noise(1.2), 400), 0.05, 0.4) * 0.5, 0))),
    'ui/queue': lambda: norm(tone(740, 0.25, 0.08)) * 0.6,
    'ui/invite': lambda: norm(chord((880, 1320), 0.4, 0.08, 0.12)) * 0.8,
    'ui/buy': lambda: norm(mixdown((metal((2600, 3900), 0.06, 0.25), 0), (metal((3100, 4600), 0.07, 0.3), 0.06))) * 0.7,
    'ui/sell': lambda: norm(mixdown((metal((3100, 4600), 0.06, 0.25), 0), (metal((2600, 3900), 0.07, 0.3), 0.06))) * 0.7,
    # Interface kit (gui/Menu.java, util/Notify.java): one timbre throughout, a short band-passed
    # tick with a soft sine body, so menus sound like one device; pitch and shape tell them apart.
    'ui/open': lambda: norm(mixdown((swish(0.12, 700, 3500) * 0.6, 0), (env(sine(lambda tt: 520 + tt * 900, 0.09), 0.002, 0.03), 0.02),
                                    (click(2600, 0.02, 0.6), 0.07))) * 0.55,
    'ui/close': lambda: norm(mixdown((click(2200, 0.02, 0.6), 0), (env(sine(lambda tt: 700 - tt * 1200, 0.09), 0.002, 0.03), 0.02),
                                     (swish(0.1, 600, 2800) * 0.5, 0.03))) * 0.5,
    'ui/click': lambda: norm(mixdown((click(2800, 0.018), 0), (env(sine(1400, 0.04), 0.001, 0.012) * 0.5, 0))) * 0.5,
    'ui/tab': lambda: norm(mixdown((click(3300, 0.016), 0), (click(2500, 0.016, 0.7), 0.045),
                                   (env(sine(1650, 0.05), 0.001, 0.012) * 0.4, 0))) * 0.5,
    'ui/confirm': lambda: norm(mixdown((click(2800, 0.018, 0.7), 0), (tone(880, 0.12, 0.04, (1, 0.25)), 0.0),
                                       (tone(1319, 0.18, 0.06, (1, 0.25)), 0.07))) * 0.6,
    'ui/error': lambda: norm(mixdown((env(np.tanh(2.5 * sine(311, 0.11)), 0.002, 0.05), 0),
                                     (env(np.tanh(2.5 * sine(233, 0.16)), 0.002, 0.07), 0.1))) * 0.55,
    'ui/reward': lambda: norm(mixdown((click(3000, 0.018, 0.6), 0), (chord((784, 988, 1175, 1568), 0.7, 0.05, 0.2), 0.02))) * 0.7,
    # Raid alerts (RaidManager): an extract opening, the raid clock running low, the last ten seconds
    # of an extraction.
    'extract/open': lambda: norm(mixdown((env(bp(noise(0.18), 1200, 4000), 0.003, 0.05) * 0.4, 0),
                                         (tone(784, 0.16, 0.05), 0.12), (tone(1175, 0.3, 0.09), 0.26))) * 0.8,
    'extract/final': lambda: norm(mixdown((tone(1568, 0.09, 0.03, (1, 0.3)), 0), (click(3600, 0.015, 0.6), 0))) * 0.75,
    'raid/warning': lambda: norm(mixdown(*[(tone(f, 0.22, 0.07, (1, 0.5, 0.2)), 0.26 * i) for i, f in enumerate((988, 740, 988, 740))])) * 0.8,
    'ui/levelup':lambda: norm(chord((523, 659, 784, 1046, 1319), 1.2, 0.08, 0.35)),
    'status/injury': lambda: norm(mixdown((env(sine(70, 0.25), 0.002, 0.06), 0), (env(sine(60, 0.25), 0.002, 0.06), 0.14))) * 0.8,
    'status/downed': lambda: norm(mixdown((env(sine(lambda tt: 90 - tt * 40, 0.8), 0.001, 0.25), 0), (env(sine(3800, 2.5), 0.05, 1.0) * 0.18, 0.1))),
    'status/mate_down': lambda: norm(mixdown((tone(440, 0.2, 0.06), 0), (tone(330, 0.3, 0.08), 0.18))) * 0.8,
    'status/heartbeat': lambda: norm(mixdown((env(sine(55, 0.2), 0.003, 0.05), 0), (env(sine(48, 0.2), 0.003, 0.05) * 0.8, 0.16))) * 0.8,
    'status/revived': lambda: norm(chord((392, 523, 659), 0.8, 0.09, 0.25)),
    'med/bandage': lambda: norm(mixdown((env(hp(noise(0.18), 2500), 0.002, 0.06), 0), (swish(0.35, 500, 3500) * 0.7, 0.2),
                                       (swish(0.35, 600, 3800) * 0.6, 0.6), (swish(0.3, 500, 3200) * 0.5, 1.0))) * 0.7,
    'med/pills': lambda: norm(mixdown(*[(click(2600 + 900 * (i % 3), 0.02, 0.8), 0.04 * i + 0.01 * (i % 2)) for i in range(9)],
                                     (click(1500, 0.05), 0.5), (env(lp(noise(0.25), 700), 0.02, 0.1) * 0.6, 0.65))) * 0.7,
    'med/inject': lambda: norm(mixdown((click(3000, 0.03), 0), (click(1900, 0.04, 0.8), 0.08),
                                      (env(bp(noise(0.5), 3000, 9000), 0.05, 0.25) * 0.5, 0.15))) * 0.7,
    'med/splint': lambda: norm(mixdown(*[(env(bp(noise(0.12), 900, 5000), 0.002, 0.04) * 0.8, 0.12 * i) for i in range(5)],
                                      (click(1200, 0.06), 0.7))) * 0.7,
    'med/kit': lambda: norm(mixdown(*[(click(1400 + 90 * i, 0.012, 0.6), 0.018 * i) for i in range(22)],
                                   (swish(0.4, 400, 3000) * 0.6, 0.45))) * 0.7,
    # Only a medical stack built before ItemFactory.VERSION 21 still names it (until it is rebuilt):
    # medical items use silently now and play their cues (see the end). Kept here, in its place, so
    # the shared noise generator gives every sound after it the same samples.
    'med/loop': lambda: norm(swish(0.3, 500, 3000)) * 0.35,
    'misc/silence': lambda: np.zeros(int(SR * 0.05), dtype=np.float32),
    'med/finish': lambda: norm(mixdown((swish(0.2, 500, 3000), 0), (tone(988, 0.3, 0.09), 0.15))) * 0.7,
    'world/switch': lambda: norm(mixdown((click(800, 0.08), 0), (metal((300, 520), 0.1, 0.4) * 0.6, 0), (env(sine(60, 1.4) + 0.4 * sine(120, 1.4), 0.2, 0.8) * 0.5, 0.1))),
    'skill/sprint': lambda: norm(swish(0.5, 300, 6000)),
    'skill/heal': lambda: norm(chord((523, 784, 1046), 1.0, 0.05, 0.35)),
    'skill/sonar': lambda: norm(mixdown((tone(1400, 0.9, 0.3, (1, 0.1)), 0), (tone(1400, 0.6, 0.2, (1, 0.1)) * 0.4, 0.35), (tone(1400, 0.5, 0.15, (1,)) * 0.2, 0.7))),
    'skill/shield': lambda: norm(mixdown((metal((500, 820, 1310), 0.15, 0.5), 0), (click(1000, 0.08), 0.0), (swish(0.2, 400, 2500), 0.05))),
    'ai/spotted': lambda: norm(mixdown((env(bp(noise(0.35), 800, 3200), 0.003, 0.12), 0), (tone(1000, 0.1, 0.03), 0.3))) * 0.8,
    'throw/pin': lambda: norm(mixdown((metal((3300, 5000), 0.05, 0.2), 0), (click(2400, 0.03), 0.1))) * 0.8,
    'throw/explode': lambda: norm(reverb(np.tanh(3 * mixdown((env(lp(noise(2.5), 900), 0.002, 0.5), 0),
                                                             (env(sine(lambda tt: 50 + 60 * np.exp(-tt * 20), 1.5), 0.001, 0.35), 0),
                                                             (env(hp(noise(0.1), 3000), 0.0005, 0.02) * 0.8, 0))), 0.9)),
    'throw/smoke': lambda: norm(env(bp(noise(2.0), 2000, 8000), 0.05, 1.2)) * 0.6,
    'event/siren': lambda: norm(mixdown(*[(env(sine(lambda tt, k=k: 620 + 260 * np.sin(2 * np.pi * 0.55 * tt + k), 4.0), 0.3, 1.6) * 0.6, 0) for k in (0, 0.4)],
                                       (env(lp(noise(4.0), 900), 0.4, 1.5) * 0.15, 0))),
    'event/drone': lambda: norm(mixdown((env(sum(sine(140 * h, 1.0) * (0.6 / h) for h in range(1, 7)) * (0.65 + 0.35 * np.sin(2 * np.pi * 46 * t(1.0))), 0.02, 9.0) * 0.6, 0),
                                       (env(bp(noise(1.0), 300, 2400), 0.02, 9.0) * 0.25, 0))) * 0.7,
    'event/whistle': lambda: norm(mixdown((env(sine(lambda tt: 2400 - 1500 * tt / 1.1, 1.1), 0.25, 2.0) * 0.5, 0),
                                         (env(bp(noise(1.1), 1500, 4000), 0.3, 1.2) * 0.35, 0))) * 0.9,
    'throw/flash': lambda: norm(mixdown((np.tanh(3 * env(noise(0.3), 0.0005, 0.05)), 0), (env(sine(4200, 3.0), 0.01, 1.5) * 0.25, 0.05))),
}


def kill_chime(n):
    """The n-th kill of a streak (KillFeedService): the kill chime a whole tone higher for each kill
    in it, with a third note from the third kill and a fourth from the fifth. Bell-like and short, so
    six in a row stays a reward rather than a slot machine."""
    base = 1568 * 2 ** ((n - 1) * 2 / 12)
    parts = [(tone(base, 0.35, 0.09), 0), (tone(base * 1.5, 0.42, 0.11), 0.06)]
    if n >= 3:
        parts.append((tone(base * 2, 0.45, 0.12) * 0.6, 0.12))
    if n >= 5:
        parts.append((tone(base * 2.5, 0.5, 0.14) * 0.45, 0.18))
    return norm(mixdown(*parts)) * (0.8 + 0.03 * n)


# Added after the rest so the shared noise generator gives every older sound the same samples.
for _n in range(1, 7):
    SOUNDS['kill/%d' % _n] = (lambda n=_n: kill_chime(n))


# ---------------------------------------------------------------- gear and the body (#41)

def add(name, make):
    """Adds a sound that draws from a noise generator of its own, seeded by its name, so adding or
    retuning one of these never changes the samples of another sound."""
    def build():
        global rng
        shared, rng = rng, np.random.default_rng(zlib.crc32(name.encode()))
        try:
            return make()
        finally:
            rng = shared
    SOUNDS[name] = build


def crackle(sec, rate, lo, hi, decay=0.0015, ramp=None):
    """Dense tiny clicks (fabric, velcro, paper): `rate` impulses a second, each a short band-passed
    burst; `ramp` (start, end) scales the rate across the sound."""
    n = int(SR * sec)
    out = np.zeros(n)
    tt = np.arange(n) / SR
    density = rate * (np.interp(tt, [0, sec], ramp) if ramp else 1.0) / SR
    hits = np.nonzero(rng.uniform(0, 1, n) < density)[0]
    out[hits] = rng.uniform(0.3, 1.0, len(hits)) * rng.choice([-1, 1], len(hits))
    kernel = np.exp(-np.arange(int(SR * decay * 6)) / (SR * decay))
    return bp(np.convolve(out, kernel)[:n], lo, hi)


# Putting on a helmet or a vest: fabric dragged over the shoulders, a strap pulled through and a
# buckle snapping shut.
add('gear/equip', lambda: norm(mixdown(
    (env(crackle(0.42, 900, 900, 7000, ramp=(0.6, 1.4)), 0.06, 0.16) * 0.7, 0),
    (env(bp(noise(0.42), 300, 2500), 0.08, 0.14) * 0.5, 0),
    (env(crackle(0.12, 2600, 1500, 8000), 0.01, 0.05) * 0.6, 0.3),
    (click(3800, 0.02, 0.9), 0.43), (metal((2900, 4300), 0.03, 0.12) * 0.5, 0.43),
    (click(2400, 0.02, 0.6), 0.47), length=int(SR * 0.6))) * 0.7)

# Gauze wound round a limb: two soft passes of cloth, crinkling as it tightens. A bandage, a
# dressing and a medkit play it while in use, and a squadmate reviving someone once a second.
add('med/wrap', lambda: norm(mixdown(
    (env(crackle(0.3, 500, 1200, 6000, ramp=(0.5, 1.5)), 0.08, 0.1) * 0.5, 0),
    (swish(0.3, 500, 3200) * 0.8, 0),
    (env(crackle(0.26, 650, 1200, 6000, ramp=(0.6, 1.6)), 0.06, 0.09) * 0.45, 0.28),
    (swish(0.28, 600, 3500) * 0.7, 0.28), length=int(SR * 0.6))) * 0.6)
# A soft drip and a dull throb, heard only by the one bleeding, every time a wound bleeds (1.5 s).
add('status/bleed', lambda: norm(mixdown(
    (env(sine(lambda tt: 1100 * np.exp(-tt * 18) + 380, 0.12), 0.002, 0.03), 0),
    (env(sine(lambda tt: 900 * np.exp(-tt * 18) + 330, 0.1), 0.002, 0.025) * 0.5, 0.21),
    (env(sine(52, 0.25), 0.01, 0.07) * 0.8, 0.02), length=int(SR * 0.35))) * 0.5)


# ---------------------------------------------------------------- voices (#41)
# Players, soldiers and stand-ins hurt and die with these (the vanilla player, generic and zombie
# hurt / death events point at them, build.py). A source-filter voice: glottal pulses at a pitch
# that jumps up with the pain and falls away, with a little jitter, shimmer and breath, through a
# cascade of five formant resonators that glide between vowels; a strained, pressed voice (short
# open phase) and creak as it dies down.

def curve(points, sec):
    """A per-sample curve through (time, value) points, straight between them."""
    xs, ys = zip(*points)
    return np.interp(t(sec), xs, ys)


def drift(sec, rate, depth):
    """1 +- depth, wandering at about `rate` Hz (jitter, shimmer)."""
    n = lp(rng.standard_normal(int(SR * sec) + 2000), rate)[2000:]
    return 1 + depth * n / (np.max(np.abs(n)) or 1)


def glottis(f0, sec, open_q=0.5, jitter=0.02):
    """Glottal flow derivative (Rosenberg pulses) along the pitch curve f0, and the flow itself."""
    f = f0 * drift(sec, 25, jitter)
    phase = np.cumsum(f / SR) % 1.0
    tp, tn = open_q * 0.7, open_q * 0.3
    flow = np.where(phase < tp, 0.5 * (1 - np.cos(np.pi * phase / tp)),
                    np.where(phase < tp + tn, np.cos(0.5 * np.pi * (phase - tp) / tn), 0.0))
    return np.diff(flow, prepend=0.0) * SR / 400.0, flow


def resonate(x, freq, bw):
    """Klatt's digital resonator, its frequency and bandwidth per sample (unity gain at 0 Hz)."""
    c = -np.exp(-2 * np.pi * bw / SR)
    b = 2 * np.exp(-np.pi * bw / SR) * np.cos(2 * np.pi * freq / SR)
    a = 1 - b - c
    y = np.zeros(len(x))
    y1 = y2 = 0.0
    for i in range(len(x)):
        y0 = a[i] * x[i] + b[i] * y1 + c[i] * y2
        y[i] = y0
        y2, y1 = y1, y0
    return y


# Formants (Hz) of the vowels the voices move through, an adult man's.
VOWELS = {'a': (730, 1090, 2440), 'uh': (640, 1190, 2390), 'eh': (530, 1840, 2480), 'er': (490, 1350, 1690),
          'o': (570, 840, 2410), 'u': (330, 900, 2240), 'm': (280, 1000, 2200)}


def voice(sec, pitch, vowels, loud, breath=0.12, open_q=0.45, jitter=0.03, shimmer=0.12, creak=None):
    """One utterance: `pitch`, `loud` (0..1) and `vowels` are (time, value) points; `creak` (start,
    depth) lets the pitch break into irregular low pulses (vocal fry) from that time on."""
    f0 = curve(pitch, sec)
    if creak:
        start, depth = creak
        fry = np.clip((t(sec) - start) / 0.08, 0, 1) * depth
        f0 = f0 * (1 - 0.45 * fry) * drift(sec, 60, 0.25 * depth)
    src, flow = glottis(f0, sec, open_q, jitter)
    src = src * drift(sec, 35, shimmer)
    # A strained voice is brighter than the smooth pulse alone: lift the highs (+6 dB an octave
    # from about 1 kHz), which puts the third formant some 25 dB under the first, as in speech.
    src = src - 0.85 * np.concatenate(([0.0], src[:-1]))
    # Breath: turbulence at the glottis, stronger while it is open, shaped by the same tract.
    asp = hp(noise(sec), 700) * (0.35 + 0.65 * flow) * breath
    x = src + asp
    bands = [[], [], []]
    for when, name in vowels:
        for k in range(3):
            bands[k].append((when, VOWELS[name][k]))
    widths = (90, 110, 130)
    for k in range(3):
        x = resonate(x, curve(bands[k], sec), np.full(len(x), widths[k]))
    x = resonate(x, np.full(len(x), 3300.0), np.full(len(x), 220.0))
    x = resonate(x, np.full(len(x), 3750.0), np.full(len(x), 280.0))
    return x * curve(loud, sec)


def breath_out(sec, vowel='uh', gain=0.4):
    """A short breath through the open mouth, with no voice."""
    x = hp(noise(sec), 400)
    for k, w in zip(range(3), (200, 250, 300)):
        f = VOWELS[vowel][k]
        x = resonate(x, np.full(len(x), float(f)), np.full(len(x), float(w)))
    return env(x, 0.03, sec / 2.5) * gain


def cry(*parts, length):
    """Parts placed in time, a touch of room, peak at 0.9."""
    x = mixdown(*parts, length=int(SR * length))
    return norm(lp(x + reverb(x, 0.12) * 0.15, 7000))


# Hurt: four short cries a hit draws out, each a different vowel and pitch, so a burst of hits does
# not repeat one sample.
add('voice/hurt1', lambda: cry((voice(0.26, [(0, 175), (0.04, 215), (0.26, 140)], [(0, 'uh'), (0.26, 'uh')],
                                      [(0, 0), (0.015, 1), (0.12, 0.7), (0.26, 0)], creak=(0.17, 0.6)), 0.01),
                               (breath_out(0.12) * 0.5, 0), length=0.32))
add('voice/hurt2', lambda: cry((voice(0.3, [(0, 190), (0.05, 235), (0.3, 150)], [(0, 'a'), (0.18, 'a'), (0.3, 'uh')],
                                      [(0, 0), (0.02, 1), (0.16, 0.75), (0.3, 0)], breath=0.2, jitter=0.05,
                                      creak=(0.2, 0.7)), 0.0), length=0.36))
add('voice/hurt3', lambda: cry((voice(0.24, [(0, 160), (0.04, 190), (0.24, 125)], [(0, 'uh'), (0.12, 'er'), (0.24, 'm')],
                                      [(0, 0), (0.012, 1), (0.1, 0.8), (0.24, 0)], open_q=0.4), 0.02),
                               (breath_out(0.1, 'uh') * 0.6, 0), length=0.3))
add('voice/hurt4', lambda: cry((breath_out(0.08, 'a') * 0.7, 0),
                               (voice(0.22, [(0, 205), (0.03, 245), (0.22, 165)], [(0, 'eh'), (0.1, 'uh'), (0.22, 'uh')],
                                      [(0, 0), (0.01, 1), (0.1, 0.6), (0.22, 0)], breath=0.18, creak=(0.15, 0.5)), 0.05),
                               length=0.3))
# Death: a long cry that falls and breaks, then the last breath going out.
add('voice/death1', lambda: cry((voice(0.75, [(0, 200), (0.08, 240), (0.4, 170), (0.75, 95)],
                                       [(0, 'a'), (0.35, 'a'), (0.75, 'uh')],
                                       [(0, 0), (0.03, 1), (0.3, 0.85), (0.6, 0.4), (0.75, 0)], breath=0.16,
                                       jitter=0.04, creak=(0.45, 0.9)), 0.0),
                                (breath_out(0.5, 'uh') * 0.5, 0.72), length=1.3))
add('voice/death2', lambda: cry((voice(0.9, [(0, 150), (0.1, 175), (0.5, 120), (0.9, 80)],
                                       [(0, 'uh'), (0.4, 'o'), (0.9, 'u')],
                                       [(0, 0), (0.06, 0.9), (0.4, 0.8), (0.75, 0.3), (0.9, 0)], breath=0.2,
                                       open_q=0.55, creak=(0.35, 1.0)), 0.0),
                                (breath_out(0.6, 'o') * 0.4, 0.85), length=1.5))
add('voice/death3', lambda: cry((voice(0.3, [(0, 210), (0.04, 250), (0.3, 170)], [(0, 'a'), (0.3, 'uh')],
                                       [(0, 0), (0.015, 1), (0.2, 0.7), (0.3, 0)], breath=0.2, creak=(0.2, 0.6)), 0.0),
                                (voice(0.45, [(0, 130), (0.45, 85)], [(0, 'uh'), (0.45, 'm')],
                                       [(0, 0), (0.05, 0.45), (0.3, 0.25), (0.45, 0)], breath=0.3, open_q=0.6,
                                       creak=(0.05, 1.0)), 0.36),
                                (breath_out(0.45, 'uh') * 0.35, 0.8), length=1.3))


# ---------------------------------------------------------------- medical cues (#41)
# A medical item in use plays its cues at set points of its use time (items.yml med.cues,
# HealthService), instead of one 0.3 s swish every 4 ticks all through.

def inhale(sec, vowel='a', gain=0.6):
    """A sharp breath in through the open mouth, swelling to a catch at the end."""
    x = hp(noise(sec), 500)
    for k, w in zip(range(3), (180, 220, 280)):
        x = resonate(x, np.full(len(x), float(VOWELS[vowel][k])), np.full(len(x), float(w)))
    return x * curve([(0, 0), (sec * 0.8, 1), (sec, 0)], sec) * gain


# Tearing a dressing's wrapper or a strip of tape off the roll.
add('med/tear', lambda: norm(mixdown(
    (env(crackle(0.3, 2400, 1500, 9000, ramp=(0.4, 1.6)), 0.03, 0.12), 0),
    (env(bp(noise(0.3), 2500, 7000), 0.02, 0.08) * 0.3, 0), length=int(SR * 0.4))) * 0.6)
# A splint's hook-and-loop strap ripped open and pressed shut.
add('med/strap', lambda: norm(mixdown(
    (env(crackle(0.32, 3200, 900, 8000, decay=0.001, ramp=(1.5, 0.6)), 0.01, 0.14) * (0.8 + 0.2 * np.sin(2 * np.pi * 38 * t(0.32))), 0),
    (env(lp(noise(0.06), 900), 0.002, 0.02) * 0.6, 0.4), length=int(SR * 0.5))) * 0.65)
# The cap pulled off an injector.
add('med/cap', lambda: norm(mixdown((click(1800, 0.03), 0), (env(sine(lambda tt: 260 - tt * 900, 0.06), 0.001, 0.015) * 0.6, 0),
                                    (click(3600, 0.015, 0.5), 0.04), length=int(SR * 0.15))) * 0.6)
# Rummaging through a medical kit: packets and tools knocking about.
add('med/rummage', lambda: norm(mixdown(
    (env(crackle(0.55, 400, 800, 5000), 0.1, 0.25) * 0.6, 0),
    *[(click(1300 + 700 * (i % 3), 0.03, 0.7), 0.07 + 0.11 * i + 0.02 * (i % 2)) for i in range(5)],
    length=int(SR * 0.65))) * 0.55)
# Surgical scissors cutting twice.
add('med/snip', lambda: norm(mixdown(*[part for at in (0, 0.17) for part in (
    (env(bp(noise(0.05), 3000, 9000), 0.004, 0.012), at), (metal((4200, 6100), 0.03, 0.08) * 0.25, at + 0.01))],
    length=int(SR * 0.32))) * 0.55)
# Swallowing the pills.
add('med/swallow', lambda: norm(mixdown(
    (env(sine(lambda tt: 190 - tt * 900, 0.08), 0.004, 0.025), 0),
    (env(resonate(bp(noise(0.12), 200, 1500), np.full(int(SR * 0.12), 330.0), np.full(int(SR * 0.12), 120.0)), 0.01, 0.03) * 0.8, 0.01),
    (click(900, 0.03, 0.5), 0.16), length=int(SR * 0.3))) * 0.6)
# Coming round from the self-revive shot: a sharp gasp for air.
add('med/gasp', lambda: norm(mixdown((inhale(0.38, 'a'), 0), (breath_out(0.35, 'uh') * 0.5, 0.45), length=int(SR * 0.85))) * 0.8)



# ---------------------------------------------------------------- moving (#9)

def slide_scrape():
    """A slide (SlideService): a body and its gear dropping onto the ground and dragged along it for
    about 0.8 s, loudest as it lands and dying away as the slide slows, with grit crackling under it."""
    sec = 0.85
    n = int(SR * sec)
    tt = t(sec)
    # The drag: cloth and gear over stone, a band of noise whose loudness wobbles with the steps of
    # the surface going by, fading as the speed does.
    drag = bp(noise(sec), 220, 2400) * (0.78 + 0.22 * np.sin(2 * np.pi * 11 * tt + 1.3))
    drag *= np.clip(tt / 0.03, 0, 1) * np.exp(-tt / 0.38)
    # Grit: sparse clicks of gravel and dust, thinning out with the drag.
    grit = (rng.uniform(0, 1, n) < 0.006) * rng.uniform(-1, 1, n) * np.exp(-tt / 0.5)
    grit = hp(grit, 1800) * 2.5
    # The drop onto the ground: a dull thud and a short swish of the clothes.
    thud = env(lp(noise(0.12), 170), 0.002, 0.035) * 1.1
    cloth = env(bp(noise(0.2), 900, 5200), 0.01, 0.05) * 0.45
    x = mixdown((drag, 0), (grit, 0.01), (thud, 0), (cloth, 0), length=n)
    # Squeezed, so the drag carries as far as a footstep rather than only the thud.
    return norm(np.tanh(2.5 * norm(x, 1.0)), 0.85)


add('move/slide', slide_scrape)


def write_ogg(data, path):
    import oggenc
    oggenc.write(path, data, SR)
