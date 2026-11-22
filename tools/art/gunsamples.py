"""Gunfire from recordings: every report a player hears is a real CC0 gunshot.

The recordings are The Free Firearm Sound Library's prepared set (23 guns, 96 kHz / 24-bit stereo,
each shot taken near the shooter and again some way downrange) and two Freesound previews of
suppressed guns, all fetched and checked by fetch_audio.py. tools/art/audio_sources.json lists which
recording, shot and channel every output file was cut from.

How a recording becomes the files GunAudio.java plays:
  shots     onsets are found on the loudest channel; each channel of each shot is its own take. The
            library's stereo comes from two mics metres apart (left / right of the shooter, or in
            front and behind downrange): their channels barely correlate (-0.2..0.1), so summing them
            would comb-filter the blast, while each alone is a clean mono take.
  near      a near take from the shot's onset, high-passed (the mics also caught the blast's
            infrasound), resampled 96 -> 44.1 kHz, keeping 1.2-2.4 s of the range's natural decay.
  fp        the shooter's own shot: both channels of a near shot, narrowed in mid/side so the blast is
            wide but not split in two.
  mid       the downrange recording (crack, then blast, then the range answering).
  far       mid through a 2nd-order 3.5 kHz low-pass plus a few sparse, darker echoes.
  muff      near through a 2nd-order 1.2 kHz low-pass with 15 % of the dry take mixed back in, so a
            shot behind a wall is dull but still a gunshot.
  _s        suppressed: a suppressed .45 pistol for pistols and SMGs, a suppressed machine gun for
            rifles (Freesound), mid_s low-passed and echoed like far.
  tail      indoor room sound only: a near take convolved with a synthetic room (no direct sound);
            GunAudio plays it with each shot under a roof, alternating two keys (.in, .in2).
  x<n>      automatic fire that does not fit the 50 ms server tick: n rounds at the gun's real
            interval in one file (GROUPS).
Loudness is set per gun class from the first 120 ms (where a gunshot's loudness is), then a
look-ahead limiter holds every file to -1 dBTP. A pistol is a little quieter than an SMG, which is
quieter than a rifle; GunAudio scales the rest by the gun's items.yml volume.

There is no CC0 recording of a .338, a belt-fed machine gun, a suppressed rifle recorded up close or
any shot indoors: the AWR plays a .30-06 Springfield lowered 5 %, the KL7 the last rounds of AK-47
bursts lowered 3 %, and rooms are simulated.
"""
import json
import os
from fractions import Fraction
from functools import lru_cache

import numpy as np
from scipy import ndimage, signal

import fetch_audio
from gunaudio import env, lp, n_of, noise, place, reson

SR = 44100
CEILING = 10 ** (-1 / 20)

# Automatic fire whose interval does not fit the 50 ms server tick plays one sample every few ticks
# holding several rounds at the gun's real interval: gun id -> (rounds, ticks). GunAudio.java keeps
# the same table and only uses a sample while the gun's rpm still matches it (rounds * 1200 / ticks).
GROUPS = {'m4c': (2, 3), 'sv9': (3, 4), 'v57': (3, 4)}

# Gun classes: how long a take keeps its decay, its loudness (dB, relative) and which suppressed
# recording stands in for it.
CLASSES = {
    'pistol': dict(length=1.3, level=-1.5, supp='fnx45'),
    'heavy_pistol': dict(length=1.5, level=0.0, supp='fnx45'),
    'smg': dict(length=1.4, level=-0.5, supp='fnx45'),
    'rifle': dict(length=1.7, level=0.0, supp='mgsil'),
    'marksman': dict(length=2.0, level=0.5, supp='mgsil'),
    'sniper': dict(length=2.3, level=1.0, supp='mgsil'),
    'shotgun': dict(length=1.8, level=0.5, supp='mgsil'),
}

# gun id -> recordings in the library (folder/file), what was recorded and how it is played back.
# 'rate' below 1 plays the recording slower: lower and heavier. 'pick' last takes the last round of
# each burst instead of the first shot. 'eq' is a list of peaking filters (Hz, dB, Q) for the gun's
# takes. 'supp_rate' tunes the shared suppressed recording to the gun.
GUNS = {
    # The PPQ's report is mostly crack; a little more body keeps it from sounding like a cap gun.
    'p18': dict(cls='pistol', model='Walther PPQ, 9x19', eq=[(500, 3.0, .8)],
                near=['Walther PPQ/X_39P.wav'], mid=['Walther PPQ/X_31P.wav'], supp_rate=1.06),
    'd50': dict(cls='heavy_pistol', model='Colt 1911, .45 ACP, played 6 % lower', rate=0.94,
                near=['1911/A_42P.wav'], mid=['1911/A_34P.wav'], supp_rate=0.94),
    'sv9': dict(cls='smg', model='Carl Gustav M45, 9x19',
                near=['Carl Gustav M45/G_31P.wav'], mid=['Carl Gustav M45/G_20P.wav'], supp_rate=1.0),
    'v57': dict(cls='smg', model='PPSh-41, 7.62x25 Tokarev',
                near=['PPSh/P_30P.wav'], mid=['PPSh/P_16P.wav'], supp_rate=1.1),
    'k47': dict(cls='rifle', model='AK-47, 7.62x39',
                near=['AK-47/C_28P.wav'], mid=['AK-47/C_31P.wav'], supp_rate=1.0),
    'kl7': dict(cls='rifle', model='AK-47 bursts (last round), 7.62x39, played 3 % lower', rate=0.97, pick='last',
                near=['AK-47/C_29P.wav', 'AK-47/C_27P.wav'], mid=['AK-47/C_34P.wav'], supp_rate=0.95),
    'm4c': dict(cls='rifle', model='AR-15, 5.56x45',
                near=['AR-15/D_32P.wav'], mid=['AR-15/D_24P.wav'], supp_rate=1.06),
    'scr': dict(cls='rifle', model='Mosin-Nagant, 7.62x54R',
                near=['Mosin Nagant/M_21P.wav'], mid=['Mosin Nagant/M_26P.wav'], supp_rate=0.97),
    'dm14': dict(cls='marksman', model='Tikka T3, .30-06',
                 near=['Tikka/W_29P.wav'], mid=['Tikka/W_24P.wav'], supp_rate=0.95),
    'awr': dict(cls='sniper', model='Springfield M1917, .30-06, played 5 % lower', rate=0.95,
                near=['1917/B_24P.wav'], mid=['1917/B_16P.wav'], supp_rate=0.9),
    'r12': dict(cls='shotgun', model='Benelli Nova and Winchester Model 12, 12 gauge',
                near=['Nova/O_21P.wav', 'Model 12/K_22P.wav'], mid=['Nova/O_17P.wav', 'Model 12/K_17P.wav'], supp_rate=0.85),
}

# Variants per band and the rate each band is stored at. GunAudio.java reads the counts back from
# sounds.json to pick variants itself.
BANDS = {
    'fp': (4, SR), 'near': (4, SR), 'mid': (3, 32000), 'far': (2, 22050), 'muff': (2, 22050),
    'fp_s': (3, SR), 'near_s': (3, 32000), 'mid_s': (2, 22050),
}
GROUP_BANDS = {'fp': 2, 'near': 2, 'mid': 2, 'far': 2, 'muff': 2, 'fp_s': 2, 'near_s': 2, 'mid_s': 2}

# Indoor tails: which gun's recording each family's room sound is made from.
TAIL_FAMILY = {'p18': 'light', 'sv9': 'light', 'v57': 'light', 'd50': 'rifle', 'm4c': 'rifle', 'k47': 'heavy',
               'scr': 'heavy', 'kl7': 'heavy', 'dm14': 'heavy', 'r12': 'heavy', 'awr': 'sniper'}
TAIL_SOURCE = {'light': 'sv9', 'rifle': 'm4c', 'heavy': 'scr', 'sniper': 'awr'}

# Reference loudness: RMS of the first 120 ms after a 120 Hz high-pass, dBFS, before the class level.
# A recorded shot has most of that energy in its first few milliseconds, so a limiter buys little
# loudness: this is what every take reaches with at most MAX_PUSH dB going into the limiter.
LOUDNESS = -21.5
MAX_PUSH = 6.0
SUPPRESSED = -8.0  # suppressed reports sit this far below the gun's own
FP = 1.5  # the shooter's own shot, over what a player next to them hears


# ---------------------------------------------------------------- loading and cutting

@lru_cache(maxsize=None)
def _paths():
    return {sid: fetch_audio.fetch(sid) for sid in fetch_audio.SOURCES}


@lru_cache(maxsize=None)
def load(sid, rel=None):
    """(frames x channels float64, rate) of a source file."""
    import soundfile
    base = _paths()[sid]
    x, sr = soundfile.read(os.path.join(base, rel) if rel else base, dtype='float64', always_2d=True)
    return x, sr


def _blocks(x, sr, ms=1.0):
    b = max(1, int(sr * ms / 1000))
    n = len(x) // b
    return np.abs(x[:n * b]).reshape(n, b).max(axis=1), b


def shots(x, sr, gap=1.0, threshold=-18.0):
    """Shots in a recording: for each, the sample where its first and its last round start."""
    e, b = _blocks(np.max(np.abs(x), axis=1), sr)
    thr = e.max() * 10 ** (threshold / 20)
    loud = np.nonzero(e > thr)[0]
    found = []
    for i in loud:
        if found and (i - found[-1][-1]) * b < gap * sr:
            found[-1].append(i)
        else:
            found.append([i])
    out = []
    for blocks in found:
        rounds = [blocks[0]] + [j for k, j in enumerate(blocks[1:], 1) if j - blocks[k - 1] > 30]
        out.append((rounds[0] * b, rounds[-1] * b))
    return out


def onset(ch, sr, near):
    """First sample of the shot starting near `near` on one channel: where it reaches 10 % of its
    local peak, a few hundred microseconds early."""
    lo = max(0, near - int(sr * .005))
    hi = min(len(ch), near + int(sr * .03))
    seg = np.abs(ch[lo:hi])
    peak = seg.max()
    first = int(np.nonzero(seg >= peak * .1)[0][0])
    return max(0, lo + first - int(sr * .0004))


def hp(x, f, order=2, sr=SR):
    sos = signal.butter(order, f / (sr / 2), btype='high', output='sos')
    return signal.sosfilt(sos, x, axis=0)


def resample(x, sr_from, sr_to, rate=1.0):
    """x at sr_from played at `rate` speed, as samples at sr_to."""
    frac = Fraction(sr_to, int(round(sr_from * rate))).limit_denominator(4000)
    if frac == 1:
        return x
    return signal.resample_poly(x, frac.numerator, frac.denominator, axis=0)


def fade_in(x, sec=.0006):
    n = min(len(x), n_of(sec))
    w = np.sin(np.linspace(0, np.pi / 2, n)) ** 2
    x[:n] *= w if x.ndim == 1 else w[:, None]
    return x


def fade_out(x, share=.35):
    """Fades the last `share` of x to silence along a quarter-cosine: the recording's own decay is
    already deep by then, this only takes away the noise floor."""
    n = min(len(x), int(len(x) * share))
    w = np.cos(np.linspace(0, np.pi / 2, n)) ** 2
    x[-n:] *= w if x.ndim == 1 else w[:, None]
    return x


class Take:
    """One channel (or a stereo pair) of one shot in one recording, cut and at 44.1 kHz."""

    def __init__(self, data, origin):
        self.data = data
        self.origin = origin


def takes(sid, rel, length, rate=1.0, pick='first', stereo=False, hp_hz=70.0):
    """Takes of every shot in a recording, each length seconds long (shorter when the next shot
    comes sooner)."""
    x, sr = load(sid, rel)
    x = hp(x, hp_hz, order=4, sr=sr)
    found = shots(x, sr)
    out = []
    for k, (first, last) in enumerate(found):
        near = last if pick == 'last' else first
        nxt = found[k + 1][0] if k + 1 < len(found) else len(x)
        chans = [onset(x[:, c], sr, near) for c in range(x.shape[1])]
        if stereo:
            start = min(chans)
            end = min(len(x), start + int(length * rate * sr), nxt - int(.05 * sr))
            pair = np.zeros((end - start, 2))
            for c in range(min(2, x.shape[1])):
                lag = chans[c] - start
                pair[:end - start - lag, c] = x[chans[c]:end, c]
            if x.shape[1] == 1:
                pair[:, 1] = pair[:, 0]
            y = resample(pair, sr, SR, rate)
            out.append(Take(fade_out(fade_in(y)), dict(source=sid, file=rel, shot=k, channel='both', at=round(start / sr, 4))))
            continue
        for c in range(x.shape[1]):
            start = chans[c]
            end = min(len(x), start + int(length * rate * sr), nxt - int(.05 * sr))
            y = resample(x[start:end, c].copy(), sr, SR, rate)
            out.append(Take(fade_out(fade_in(y)), dict(source=sid, file=rel, shot=k, channel=c, at=round(start / sr, 4))))
    return out


# ---------------------------------------------------------------- loudness and peaks

def loudness(x):
    """dB: RMS of the first 120 ms after a 120 Hz high-pass (for stereo, the channels' power
    averaged, as a listener hears both ears: their mono mix of two distant mics would read low)."""
    m = hp(x, 120)
    e = np.abs(m) if m.ndim == 1 else np.max(np.abs(m), axis=1)
    start = int(np.nonzero(e >= e.max() * .1)[0][0])
    seg = m[start:start + n_of(.12)]
    return 10 * np.log10(np.mean(seg ** 2) + 1e-24)


def true_peak(x):
    up = signal.resample_poly(x, 4, 1, axis=0)
    return float(np.max(np.abs(up)))


def limit(x, ceiling=CEILING, hold=.004):
    """Look-ahead peak limiter: the gain needed to keep the 4x oversampled peak under the ceiling,
    held over a few ms around each peak and smoothed, so the blast's first millisecond is turned down
    instead of clipped."""
    up = signal.resample_poly(x, 4, 1, axis=0)
    env_up = np.abs(up) if up.ndim == 1 else np.max(np.abs(up), axis=1)
    n = len(x)
    env_x = env_up[:n * 4].reshape(-1, 4).max(axis=1) if len(env_up) >= n * 4 else np.pad(env_up, (0, n * 4 - len(env_up))).reshape(-1, 4).max(axis=1)
    need = np.minimum(1.0, ceiling * .985 / np.maximum(env_x, 1e-9))
    if need.min() >= 1.0:
        return x
    w = max(3, n_of(hold))
    g = ndimage.minimum_filter1d(need, size=2 * w + 1, mode='nearest')
    g = ndimage.uniform_filter1d(g, size=w, mode='nearest')
    y = x * (g if x.ndim == 1 else g[:, None])
    p = true_peak(y)
    return y * (ceiling / p) if p > ceiling else y


SHORT = []  # (what, target, reached) for takes that could not reach their loudness


def level(x, target, what='', push=MAX_PUSH):
    """x at `target` loudness, held under the ceiling. The limiter takes a little loudness off, so
    the gain is set again until it settles, but never drives the limiter by more than `push` dB."""
    most = CEILING * 10 ** (push / 20) / max(true_peak(x), 1e-9)
    gain = min(most, 10 ** ((target - loudness(x)) / 20))
    y = limit(x * gain)
    for _ in range(4):
        miss = target - loudness(y)
        if abs(miss) < .1 or (miss > 0 and gain >= most):
            break
        gain = min(most, gain * 10 ** (miss / 20))
        y = limit(x * gain)
    reached = loudness(y)
    if reached < target - .5:
        SHORT.append((what, round(target, 1), round(reached, 1)))
    return y


def target(gid, suppressed=False):
    return LOUDNESS + CLASSES[GUNS[gid]['cls']]['level'] + (SUPPRESSED if suppressed else 0.0)


# ---------------------------------------------------------------- bands

def lp2(x, f, sr=SR):
    sos = signal.butter(2, f / (sr / 2), btype='low', output='sos')
    return signal.sosfilt(sos, x, axis=0)


def echoes(x, seed, taps, tone, length):
    """Sparse late echoes off distant terrain: darker, smeared copies of x."""
    rng = np.random.default_rng(seed)
    out = np.zeros(max(len(x), n_of(length)))
    out[:len(x)] += x[:len(out)]
    for at, g in taps:
        # A short diffuse smear of unit energy: the echo carries the shot's energy, spread out.
        smear = lp(noise(rng, .03), 2500) * env(n_of(.03), .002, .008)
        smear[0] += np.sqrt(np.sum(smear ** 2))
        smear /= np.sqrt(np.sum(smear ** 2))
        e = lp2(signal.fftconvolve(x, smear)[:len(x)], tone)
        place(out, e, at * rng.uniform(.9, 1.1), g)
    return out


def mono(t):
    return t.data if t.data.ndim == 1 else t.data.mean(axis=1)


def near(gid, t):
    return level(mono(t).copy(), target(gid), gid + ' near')


def first_person(gid, t):
    """Stereo near shot narrowed in mid/side (side at 60 %)."""
    x = t.data
    m = (x[:, 0] + x[:, 1]) / 2
    s = (x[:, 0] - x[:, 1]) / 2 * .6
    # Two mics metres apart peak at different moments, so the pair takes a little more limiting.
    return level(np.stack([m + s, m - s], axis=1), target(gid) + FP, gid + ' fp', push=MAX_PUSH + 3)


def mid(gid, t):
    return level(mono(t).copy(), target(gid), gid + ' mid')


def far(gid, t, v):
    x = lp2(mono(t), 3500)
    y = echoes(x, hash32(gid, 'far', v), ((.21, .32), (.47, .2), (.83, .12), (1.3, .06)), 1600, len(x) / SR + .4)
    return level(fade_out(hp(y, 60), .3), target(gid), gid + ' far')


def muff(gid, t):
    x = mono(t)
    y = lp2(x, 1200) + .15 * x
    return level(fade_out(y.copy(), .3), target(gid), gid + ' muff')


def hash32(*parts):
    import zlib
    return zlib.crc32(repr(parts).encode())


# ---------------------------------------------------------------- suppressed

@lru_cache(maxsize=None)
def suppressed_takes(sid):
    """The suppressed recordings as mono takes."""
    if sid == 'fnx45':
        x, sr = load(sid)
        x = hp(x.mean(axis=1), 90, sr=sr)
        start = onset(x, sr, int(np.argmax(np.abs(x))))
        y = fade_out(fade_in(resample(x[start:].copy(), sr, SR)), .45)
        # The clip stops 0.18 s after the shot; a few faint returns off the range give it the space
        # the other recordings have.
        y = echoes(y, hash32(sid), ((.07, .16), (.16, .09), (.3, .05)), 2500, .55)
        return [Take(fade_out(y, .4), dict(source=sid, shot=0, channel='mix', at=round(start / sr, 4)))]
    x, sr = load(sid)
    x = hp(x.mean(axis=1), 70, sr=sr)
    out = []
    for k, (first, last) in enumerate(shots(x[:, None], sr, gap=.8, threshold=-12)):
        start = onset(x, sr, last)
        y = resample(x[start:start + int(.7 * sr)].copy(), sr, SR)
        out.append(Take(fade_out(fade_in(y), .45), dict(source=sid, shot=k, channel=0, at=round(start / sr, 4))))
    return out


def suppressed_near(gid, v):
    pool = suppressed_takes(CLASSES[GUNS[gid]['cls']]['supp'])
    t = pool[v % len(pool)]
    # One or two source shots per class: variants differ in speed (and in shot where there are two).
    rate = GUNS[gid]['supp_rate'] * (1.0, .965, 1.035, .985, 1.02, .95)[v % 6]
    x = resample(mono(t), SR, SR, rate)
    return level(fade_out(x.copy(), .3), target(gid, True), gid + ' near_s'), dict(t.origin, rate=round(rate, 4))


def suppressed_fp(gid, v):
    x, origin = suppressed_near(gid, v)
    # Close to the shooter: the same shot in both ears with a different ground bounce in each.
    left = x + np.concatenate([np.zeros(n_of(.0042)), lp2(x, 3500)])[:len(x)] * .25
    right = x + np.concatenate([np.zeros(n_of(.0051)), lp2(x, 3500)])[:len(x)] * .25
    return level(np.stack([left, right], axis=1), target(gid, True) + FP, gid + ' fp_s'), origin


def suppressed_mid(gid, v):
    x, origin = suppressed_near(gid, v)
    y = echoes(lp2(x, 3000), hash32(gid, 'mid_s', v), ((.18, .25), (.41, .14)), 1800, len(x) / SR + .5)
    return level(fade_out(y, .4), target(gid, True), gid + ' mid_s'), origin


# ---------------------------------------------------------------- indoor tails

def ir_room(seed, length):
    """A concrete room without its direct sound: early reflections, a boomy late field and flutter."""
    rng = np.random.default_rng(seed)
    ir = np.zeros(n_of(length))
    for _ in range(40):
        at = rng.uniform(.002, .035)
        place(ir, lp(noise(rng, .004), 6000) * env(n_of(.004), .0002, .001), at, rng.uniform(.1, .35) * (1 - at * 15))
    late = lp(noise(rng, length), 3200) * env(n_of(length), .008, length * .2)
    modes = sum(reson(late, f, 9) * g for f, g in ((54, 1.0), (81, .8), (117, .6), (163, .45), (228, .3)))
    place(ir, late + modes * .9, .004, .55)
    for k in range(1, 9):
        place(ir, lp(noise(rng, .05), 3800 / (1 + k * .35)) * env(n_of(.05), .001, .009),
              .047 * k + rng.uniform(-.003, .003), .42 * .74 ** k)
    return ir


def tail(family, v):
    gid = TAIL_SOURCE[family]
    t = pick(near_takes(gid), v)
    x = near(gid, t)
    length = {'light': .9, 'rifle': 1.1, 'heavy': 1.3, 'sniper': 1.5}[family]
    y = signal.fftconvolve(x, ir_room(hash32(family, v, 'room'), length))[:n_of(length + .1)]
    y = hp(y, 45)
    return level(fade_out(y, .4), target(gid) - 3.0, family + ' tail'), t.origin


# ---------------------------------------------------------------- takes per gun

def gun_takes(gid, files, length, **kw):
    g = GUNS[gid]
    out = []
    for rel in files:
        out += takes('ffsl', rel, length, g.get('rate', 1.0), g.get('pick', 'first'), **kw)
    for t in out:
        for f, db, q in g.get('eq', ()):
            t.data = peaking(t.data, f, db, q)
    return interleave(out)


def peaking(x, f0, db, q, sr=SR):
    """RBJ peaking equaliser."""
    a = 10 ** (db / 40)
    w0 = 2 * np.pi * f0 / sr
    alpha = np.sin(w0) / (2 * q)
    b = [1 + alpha * a, -2 * np.cos(w0), 1 - alpha * a]
    den = [1 + alpha / a, -2 * np.cos(w0), 1 - alpha / a]
    return signal.lfilter(b, den, x, axis=0)


def clean(gid, t):
    """Whether a near take sounds like a shot and not a thump (audiocheck.py's test, with a margin
    for what Vorbis encoding moves): one mic of a pair sometimes sat in the blast's sub-bass."""
    import audiocheck
    x = near(gid, t)
    return (audiocheck.decay_ms(x, SR) < audiocheck.decay_limit(gid) * .9
            and audiocheck.low_mid_share(x, SR) > .33 and audiocheck.main_peak_hz(x, SR) > 170)


@lru_cache(maxsize=None)
def near_takes(gid):
    """Near takes, the clean ones first (in interleaved order), so the variants are clean while
    there are enough of them."""
    g = GUNS[gid]
    pool = gun_takes(gid, g['near'], CLASSES[g['cls']]['length'])
    mark = len(SHORT)
    good = [t for t in pool if clean(gid, t)]
    del SHORT[mark:]
    if not good:
        raise ValueError(gid + ': no near take passes audiocheck')
    # Too few clean takes for the variants: the clean ones again, played 3 % higher and lower.
    count = BANDS['near'][0]
    for k in range(count - len(good)):
        t = good[k % len(good)]
        rate = (1.03, .97)[(k // len(good)) % 2]
        good.append(Take(resample(t.data, SR, SR, rate), dict(t.origin, rate=rate)))
    return good


@lru_cache(maxsize=None)
def stereo_takes(gid):
    g = GUNS[gid]
    length = CLASSES[g['cls']]['length']
    out = []
    for t in gun_takes(gid, g['near'], length, stereo=True):
        out.append(t)
        # The same shot with the ears swapped is another variant.
        out.append(Take(t.data[:, ::-1].copy(), dict(t.origin, channel='both, swapped')))
    return interleave(out)


@lru_cache(maxsize=None)
def mid_takes(gid):
    g = GUNS[gid]
    return gun_takes(gid, g['mid'], min(2.5, CLASSES[g['cls']]['length'] + .6), hp_hz=90.0)


def interleave(takes_):
    """Takes ordered so consecutive variants come from different shots (and files) first."""
    groups = {}
    for t in takes_:
        groups.setdefault((t.origin['file'] if 'file' in t.origin else '', t.origin['shot']), []).append(t)
    rows = list(groups.values())
    out = []
    for i in range(max(len(r) for r in rows)):
        out += [r[i] for r in rows if i < len(r)]
    return out


def pick(pool, v):
    return pool[v % len(pool)]


# ---------------------------------------------------------------- catalogue

def band(gid, name, v):
    """(samples, origin) of variant v of a band."""
    if name == 'fp':
        t = pick(stereo_takes(gid), v)
        return first_person(gid, t), t.origin
    if name == 'near':
        t = pick(near_takes(gid), v)
        return near(gid, t), t.origin
    if name == 'mid':
        t = pick(mid_takes(gid), v)
        return mid(gid, t), t.origin
    if name == 'far':
        t = pick(mid_takes(gid), v + 1)
        return far(gid, t, v), t.origin
    if name == 'muff':
        t = pick(near_takes(gid), v + 1)
        return muff(gid, t), t.origin
    if name == 'fp_s':
        return suppressed_fp(gid, v)
    if name == 'near_s':
        return suppressed_near(gid, v)
    if name == 'mid_s':
        return suppressed_mid(gid, v)
    raise ValueError(name)


def rounds(gid, name, v, n, ticks):
    """n rounds of a band at the interval n rounds take in ticks server ticks, as one sample. Each
    round is another variant; overlapping rounds are held to the level of one."""
    interval = ticks * .05 / n
    parts = [band(gid, name, v * n + i) for i in range(n)]
    shots_ = [p[0] for p in parts]
    starts = [int(round(i * interval * SR)) for i in range(n)]
    out = np.zeros((max(a + len(x) for a, x in zip(starts, shots_)),) + shots_[0].shape[1:])
    for a, x in zip(starts, shots_):
        out[a:a + len(x)] += x
    return limit(out), [p[1] for p in parts]


def catalogue():
    """[(event key, [(file path, samples, rate)], attenuation)] for every gun report, plus
    sources: {file path: (what it was cut from, its loudness in dB)}."""
    out = []
    sources = {}

    def add(key, path, made, sr):
        data, origin = made
        sources[path] = (origin, round(float(loudness(data)), 2))
        return path, data, sr

    for gid in GUNS:
        base = 'gun/%s/' % gid
        for name, (count, sr) in BANDS.items():
            files = [add('', '%s%s_%d' % (base, name, v), band(gid, name, v), sr) for v in range(count)]
            out.append(('gun.%s.%s' % (gid, name), files, 16))
        if gid in GROUPS:
            n, ticks = GROUPS[gid]
            for name, count in GROUP_BANDS.items():
                sr = BANDS[name][1]
                files = [add('', '%sx%d_%s_%d' % (base, n, name, v), rounds(gid, name, v, n, ticks), sr) for v in range(count)]
                out.append(('gun.%s.x%d.%s' % (gid, n, name), files, 16))
    for family in TAIL_SOURCE:
        files = [add('', 'gun/tail/%s_in_%d' % (family, v), tail(family, v), 32000) for v in range(2)]
        out.append(('gun.tail.%s.in' % family, files, 16))
        # The same sounds under a second key: GunAudio alternates the two so a new tail can stop the
        # one two shots back without cutting the one still ringing under it.
        out.append(('gun.tail.%s.in2' % family, files, 16))
    return out, sources


def source_manifest(sources):
    """audio_sources.json: every gun file, the recording(s) it was cut from with their license, and
    its loudness (dB, RMS of the first 120 ms, see loudness()) for tools/bot/gunaudio.js."""
    def cut(origin):
        src = fetch_audio.SOURCES[origin['source']]
        e = dict(source=origin['source'], title=src['title'], author=src['author'], page=src['page'],
                 license=src['license'])
        for k in ('file', 'shot', 'channel', 'at', 'rate'):
            if k in origin:
                e[k] = float(origin[k]) if isinstance(origin[k], np.floating) else origin[k]
        return e

    def entry(made):
        origin, loud = made
        return dict(loudness=loud, cut_from=[cut(o) for o in (origin if isinstance(origin, list) else [origin])])
    return {
        'sources': {sid: {k: s[k] for k in ('title', 'author', 'page', 'url', 'sha256', 'license')}
                    for sid, s in fetch_audio.SOURCES.items()},
        'guns': {gid: dict(model=g['model'], cls=g['cls'], rate=g.get('rate', 1.0)) for gid, g in GUNS.items()},
        'files': {path: entry(o) for path, o in sorted(sources.items())},
    }


if __name__ == '__main__':
    import sys
    for gid in (sys.argv[1:] or GUNS):
        print(gid, 'near', len(near_takes(gid)), 'stereo', len(stereo_takes(gid)), 'mid', len(mid_takes(gid)))
        print(json.dumps([t.origin for t in near_takes(gid)])[:300])
