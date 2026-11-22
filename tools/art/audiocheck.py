"""Checks the gun reports in the built pack the way a listener would notice them going wrong.

Usage: python3 tools/art/audiocheck.py [sounds folder]   (build.py runs it last; exit code 1 on failure)

For every near file (what a player a few blocks from the shooter hears):
  decay      from its peak to -20 dB within 30 ms (90 ms for bolt-action and .30-06 rifles): a shot is
             a crack and a blast, not a drum hit with a hiss behind it.
  low-mids   at least 30 % of the first 120 ms's energy in 250 Hz - 1 kHz, where a recorded blast
             has most of its body (the old synthesis had 8-17 %).
  spectrum   the strongest frequency of the first 120 ms above 150 Hz (the synthesis peaked at
             51-97 Hz: a kick drum).
And for every gun file, decoded as the client decodes it: a peak no higher than -0.5 dBFS.
The envelope is the Hilbert magnitude smoothed over 2 ms, so a low sine is not mistaken for a decay.
"""
import glob
import io
import os
import sys

import numpy as np
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
SOUNDS = os.path.join(HERE, '..', '..', 'src', 'main', 'resources', 'pack', 'craftov', 'resourcepack', 'assets', 'craftov',
                      'sounds')
# Guns whose report is allowed the longer decay: recorded from bolt-action rifles (AWR: Springfield
# 1917 .30-06, DM14: Tikka T3 .30-06, SCR: Mosin-Nagant 7.62x54R).
SLOW = {'awr', 'dm14', 'scr'}
MAX_PEAK_DB = -0.5


def read(path):
    import soundfile
    with open(path, 'rb') as f:
        x, sr = soundfile.read(io.BytesIO(f.read()), dtype='float64', always_2d=True)
    return x, sr


def envelope(x, sr):
    e = np.abs(signal.hilbert(x))
    w = max(1, int(sr * .002))
    return np.convolve(e, np.ones(w) / w, mode='same')


def decay_ms(x, sr):
    """Time from the envelope's peak until it first falls 20 dB below it."""
    e = envelope(x, sr)
    pk = int(np.argmax(e))
    below = np.nonzero(e[pk:] < e[pk] * .1)[0]
    return below[0] / sr * 1000 if len(below) else float('inf')


def decay_limit(gid):
    return 90 if gid in SLOW else 30


def first_120ms(x, sr):
    e = envelope(x, sr)
    start = max(0, int(np.nonzero(e > e.max() * .1)[0][0]) - int(sr * .002))
    return x[start:start + int(sr * .12)]


def spectrum(seg, sr):
    n = 1 << 15
    spec = np.abs(np.fft.rfft(seg * np.hanning(len(seg)), n=n)) ** 2
    return np.fft.rfftfreq(n, 1 / sr), spec


def low_mid_share(x, sr):
    f, spec = spectrum(first_120ms(x, sr), sr)
    total = spec[f >= 20].sum()
    return spec[(f >= 250) & (f < 1000)].sum() / total if total > 0 else 0.0


def main_peak_hz(x, sr):
    f, spec = spectrum(first_120ms(x, sr), sr)
    smooth = np.convolve(spec, np.ones(9) / 9, mode='same')
    keep = f >= 20
    return float(f[keep][np.argmax(smooth[keep])])


def peak_db(x):
    p = np.max(np.abs(x))
    return 20 * np.log10(p) if p > 0 else -120.0


def check(folder=SOUNDS, verbose=True):
    """Failure messages (empty when everything passes)."""
    failures = []
    near = sorted(glob.glob(os.path.join(folder, 'gun', '*', 'near_[0-9]*.ogg')))
    if not near:
        return ['no near files under ' + folder]
    for path in near:
        gid = os.path.basename(os.path.dirname(path))
        x, sr = read(path)
        m = x.mean(axis=1)
        d, share, hz = decay_ms(m, sr), low_mid_share(m, sr), main_peak_hz(m, sr)
        limit = decay_limit(gid)
        problems = []
        if d >= limit:
            problems.append('decay to -20 dB %.0f ms (limit %d)' % (d, limit))
        if share < .30:
            problems.append('250 Hz-1 kHz share %.0f %% (at least 30)' % (share * 100))
        if hz <= 150:
            problems.append('main peak at %.0f Hz (must be above 150)' % hz)
        name = os.path.relpath(path, folder)
        if verbose:
            print('%-22s decay %5.1f ms  low-mids %3.0f %%  peak %5.0f Hz  %s' % (name, d, share * 100, hz, 'FAIL' if problems else 'ok'))
        failures += ['%s: %s' % (name, p) for p in problems]
    for path in sorted(glob.glob(os.path.join(folder, 'gun', '**', '*.ogg'), recursive=True)):
        x, _ = read(path)
        p = peak_db(x)
        if p > MAX_PEAK_DB:
            failures.append('%s: decodes to %+.2f dBFS (limit %.1f)' % (os.path.relpath(path, folder), p, MAX_PEAK_DB))
    return failures


def main(argv):
    folder = argv[1] if len(argv) > 1 else SOUNDS
    failures = check(folder)
    if failures:
        print('audiocheck: %d problem(s)' % len(failures))
        for f in failures:
            print('  ' + f)
        return 1
    print('audiocheck: all gun reports pass')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
