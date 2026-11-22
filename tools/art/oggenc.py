"""Ogg Vorbis encoding for the pack: reproducible bytes, a cache keyed by content, safe peaks.

libsndfile gives every Ogg stream a random serial number, so encoding the same sound twice used to
produce a different file and every build rewrote all ~400 sounds in git. Vorbis itself is
deterministic, so the serial is set to a constant (and each page's checksum recomputed): the same
samples always give the same file. The encoded file is also kept in tools/art/.cache/ogg/ under the
hash of its samples, so a rebuild only encodes what changed.

Lossy encoding overshoots sharp transients: a gunshot limited to -1 dBFS can decode above 0 dBFS
and clip in the client. Every file is decoded once after encoding and, if its peak is above
-0.5 dBFS, scaled down and encoded again.
"""
import hashlib
import io
import os

import numpy as np

CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '.cache', 'ogg')
# Bump when the encoder settings change: the cache key includes it.
VERSION = 2
SERIAL = b'CRFT'
MAX_PEAK = 10 ** (-0.5 / 20)


def _crc_table():
    table = []
    for i in range(256):
        r = i << 24
        for _ in range(8):
            r = ((r << 1) ^ 0x04C11DB7) if r & 0x80000000 else (r << 1)
        table.append(r & 0xFFFFFFFF)
    return table


_CRC = _crc_table()


def _page_crc(page):
    crc = 0
    for b in page:
        crc = ((crc << 8) & 0xFFFFFFFF) ^ _CRC[((crc >> 24) & 0xFF) ^ b]
    return crc


def fixed_serial(data):
    """The same Ogg stream with the serial number set to SERIAL and every page checksum redone."""
    out = bytearray(data)
    at = 0
    while at < len(out):
        if out[at:at + 4] != b'OggS':
            raise ValueError('not an Ogg page at byte %d' % at)
        segments = out[at + 26]
        length = 27 + segments + sum(out[at + 27:at + 27 + segments])
        out[at + 14:at + 18] = SERIAL
        out[at + 22:at + 26] = b'\0\0\0\0'
        out[at + 22:at + 26] = _page_crc(out[at:at + length]).to_bytes(4, 'little')
        at += length
    return bytes(out)


def _encode(data, sr, quality):
    import soundfile
    buf = io.BytesIO()
    with soundfile.SoundFile(buf, 'w', sr, 1 if data.ndim == 1 else data.shape[1], format='OGG', subtype='VORBIS',
                             compression_level=quality) as f:
        f.write(data)
    return fixed_serial(buf.getvalue())


def decoded_peak(blob):
    import soundfile
    x, _ = soundfile.read(io.BytesIO(blob), dtype='float32')
    return float(np.max(np.abs(x))) if len(x) else 0.0


def encode(data, sr, quality=0.6):
    """Ogg Vorbis bytes for float samples (mono, or frames x channels); quality is libsndfile's
    compression level, 0 best and largest .. 1 smallest."""
    data = np.ascontiguousarray(np.clip(data, -1, 1), dtype=np.float32)
    key = hashlib.sha256(b'%d %d %r %r ' % (VERSION, sr, data.shape, quality) + data.tobytes()).hexdigest()
    cached = os.path.join(CACHE, key + '.ogg')
    if os.path.exists(cached):
        with open(cached, 'rb') as f:
            return f.read()
    blob = _encode(data, sr, quality)
    for _ in range(4):
        peak = decoded_peak(blob)
        if peak <= MAX_PEAK:
            break
        data = data * np.float32(MAX_PEAK * 0.98 / peak)
        blob = _encode(data, sr, quality)
    os.makedirs(CACHE, exist_ok=True)
    tmp = cached + '.part'
    with open(tmp, 'wb') as f:
        f.write(blob)
    os.replace(tmp, cached)
    return blob


def write(path, data, sr, quality=0.6):
    blob = encode(data, sr, quality)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as f:
        f.write(blob)
