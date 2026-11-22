"""Checks the gun reports in the built pack the way a listener would notice them going wrong.

Usage: python3 tools/art/audiocheck.py [sounds folder]   (build.py runs it last; exit code 1 on failure)

For every near file (what a player a few blocks from the shooter hears):
  decay      from its peak to -20 dB within 30 ms (90 ms for a bolt action in game, or a gun recorded
             from a .30-06): a shot is a crack and a blast, not a drum hit with a hiss behind it.
  low-mids   at least 30 % of the first 120 ms's energy in 250 Hz - 1 kHz, where a recorded blast
             has most of its body (the old synthesis had 8-17 %).
  spectrum   the strongest frequency of the first 120 ms above 150 Hz (the synthesis peaked at
             51-97 Hz: a kick drum).
And for every gun file, decoded as the client decodes it: a peak no higher than -0.5 dBFS.
Automatic fire's multi-round files (x<n>_near, x<n>_fp) must hold n rounds exactly the gun's interval
apart (75 ms for the M4C's two, 66.7 ms for the SV9's and V57's three), within 2 ms.
The envelope is the Hilbert magnitude smoothed over 2 ms, so a low sine is not mistaken for a decay.
"""
import functools
import glob
import io
import os
import sys

import numpy as np
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
SOUNDS = os.path.join(HERE, '..', '..', 'src', 'main', 'resources', 'pack', 'craftov', 'resourcepack', 'assets', 'craftov',
                      'sounds')
ITEMS = os.path.join(HERE, '..', '..', 'src', 'main', 'resources', 'items.yml')
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


@functools.lru_cache(maxsize=None)
def _actions():
    import yaml
    with open(ITEMS, encoding='utf-8') as f:
        items = yaml.safe_load(f)['items']
    return {gid: (item.get('gun') or {}).get('action') for gid, item in items.items() if isinstance(item, dict)}


def decay_limit(gid):
    """The longer decay only where the game gives the gun time for it: a bolt action (the AWR), or a
    .30-06 recording that the game fires semi-automatic at most (the DM14). The SCR's Mosin recording
    is a bolt action too, but the SCR fires 600 rpm automatic, so it keeps the 30 ms."""
    import gunsamples
    slow = _actions().get(gid) == 'bolt' or '.30-06' in gunsamples.GUNS.get(gid, {}).get('model', '')
    return 90 if slow else 30


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


def round_starts_ms(x, sr):
    """Where the rounds of a multi-round file start: envelope peaks above a third of the loudest,
    at least 30 ms apart, each moved back to where it first reached half its height."""
    e = envelope(x, sr)
    peaks, _ = signal.find_peaks(e, height=e.max() / 3, distance=int(sr * .03))
    starts = []
    for p in peaks:
        lo = max(0, p - int(sr * .01))
        starts.append((lo + int(np.nonzero(e[lo:p + 1] >= e[p] / 2)[0][0])) / sr * 1000)
    return starts


def dealable(rows):
    """Whether the variants (each a list of recorded shots, one per round) have a cyclic order in
    which no variant starts on the shot the one before it ended on: then they can be dealt round
    after round without a recorded shot following itself."""
    import itertools
    n = len(rows)
    if n < 2:
        return True
    for rest in itertools.permutations(range(1, n)):
        order = (0,) + rest
        if all(rows[order[(i + 1) % n]][0] != rows[order[i]][-1] for i in range(n)):
            return True
    return False


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
    import gunsamples
    for gid, (n, ticks) in gunsamples.GROUPS.items():
        interval = ticks * 50 / n
        for path in sorted(glob.glob(os.path.join(folder, 'gun', gid, 'x%d_near_[0-9]*.ogg' % n))
                           + glob.glob(os.path.join(folder, 'gun', gid, 'x%d_fp_[0-9]*.ogg' % n))):
            x, sr = read(path)
            starts = round_starts_ms(x.mean(axis=1), sr)[:n]
            gaps = np.diff(starts)
            name = os.path.relpath(path, folder)
            if verbose:
                print('%-22s rounds at %s ms' % (name, ', '.join('%.1f' % t for t in starts)))
            if len(starts) < n or np.any(np.abs(gaps - interval) > 2):
                failures.append('%s: rounds at %s ms, not %d every %.1f ms' % (name, ', '.join('%.1f' % t for t in starts), n, interval))
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
