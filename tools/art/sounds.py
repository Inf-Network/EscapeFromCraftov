"""Synthesised sound effects (all original). Produces mono 44.1 kHz Ogg Vorbis files via ffmpeg."""
import os
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
    'ui/levelup': lambda: norm(chord((523, 659, 784, 1046, 1319), 1.2, 0.08, 0.35)),
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
    'throw/flash': lambda: norm(mixdown((np.tanh(3 * env(noise(0.3), 0.0005, 0.05)), 0), (env(sine(4200, 3.0), 0.01, 1.5) * 0.25, 0.05))),
}


def write_ogg(data, path):
    import soundfile
    os.makedirs(os.path.dirname(path), exist_ok=True)
    soundfile.write(path, np.clip(data, -1, 1).astype(np.float32), SR, format='OGG', subtype='VORBIS')
