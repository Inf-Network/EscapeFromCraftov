"""Downloads the CC0 firearm recordings the gunfire is built from, into tools/art/.cache/audio/.

Usage: python3 tools/art/fetch_audio.py   (build.py calls it; a second run downloads nothing)

Every source is pinned by size and SHA-256: a file that does not match is refused, never used, so a
changed upload cannot slip into the pack. Archives are unpacked with 7-Zip (`7z`, from p7zip) next
to the download. The cache is gitignored; only the processed sounds are committed.

The library's raw master tracks (Raw Master Tracks.zip, 3.3 GB on MediaFire behind a Cloudflare
check) and Freesound's original uploads need a browser or a login, so they are not fetched here.
Freesound's anonymous HQ previews are used instead: lossy, but the pack re-encodes everything as
Ogg Vorbis anyway.
"""
import hashlib
import os
import shutil
import subprocess
import sys
import urllib.request

CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '.cache', 'audio')

# id -> where it comes from. 'files' maps a local name to the extracted path an archive must contain.
SOURCES = {
    'ffsl': dict(
        title='The Free Firearm Sound Library, Prepared SFX Library',
        author='Ben Jaszczak, Brian Nelson, Kevin Heras, Matthew Nanney',
        page='https://opengameart.org/content/the-free-firearm-sound-library',
        url='https://opengameart.org/sites/default/files/Prepared%20SFX%20Library.7z',
        size=193954738,
        sha256='cc1ab5a99a0a365105c7c5dd783f4b0b1fe90938114d3ceec53856bfe005f7d6',
        file='prepared.7z',
        unpack='Prepared SFX Library',
        license='CC0-1.0',
    ),
    'fnx45': dict(
        title='FN FNX .45 Tactical (SilencerCo Osprey)',
        author='areniporgen',
        page='https://freesound.org/people/areniporgen/sounds/693855/',
        url='https://cdn.freesound.org/previews/693/693855_15072041-hq.ogg',
        size=8245,
        sha256='6731f5094dd6811e68158a4f5b9554d1ca3ecf0f324044ec84955377212ba7ca',
        file='fnx45_suppressed.ogg',
        license='CC0-1.0',
    ),
    'mgsil': dict(
        title='Gunshots machine gun with silencer in a shooting range',
        author='YannSauvin',
        page='https://freesound.org/people/YannSauvin/sounds/779237/',
        url='https://cdn.freesound.org/previews/779/779237_6921828-hq.ogg',
        size=141281,
        sha256='f189e3a266aa54ad70c188d1943fd145c058da1a729e3076032937bce25d9eaf',
        file='mg_suppressed.ogg',
        license='CC0-1.0',
    ),
}


class FetchError(RuntimeError):
    pass


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def _verify(sid, path):
    src = SOURCES[sid]
    size = os.path.getsize(path)
    if size != src['size']:
        raise FetchError('%s: %s is %d bytes, expected %d' % (sid, path, size, src['size']))
    digest = sha256_of(path)
    if digest != src['sha256']:
        raise FetchError('%s: %s has SHA-256 %s, expected %s. The upload changed: check its license and contents '
                         'before pinning the new hash in tools/art/fetch_audio.py' % (sid, path, digest, src['sha256']))


def _download(sid, dest):
    src = SOURCES[sid]
    part = dest + '.part'
    print('fetch_audio: downloading %s (%.1f MB) from %s' % (sid, src['size'] / 1e6, src['url']), flush=True)
    request = urllib.request.Request(src['url'], headers={'User-Agent': 'craftov-art-build/1.0'})
    with urllib.request.urlopen(request, timeout=60) as response, open(part, 'wb') as out:
        shutil.copyfileobj(response, out, 1 << 20)
    try:
        _verify(sid, part)
    except FetchError:
        os.remove(part)
        raise
    os.replace(part, dest)


def _unpack(sid, archive):
    src = SOURCES[sid]
    target = os.path.join(CACHE, sid)
    done = os.path.join(target, '.unpacked-' + src['sha256'][:16])
    if os.path.exists(done):
        return os.path.join(target, src['unpack'])
    if shutil.which('7z') is None:
        raise FetchError('%s: 7z is needed to unpack %s (apt install p7zip-full / brew install p7zip)' % (sid, archive))
    if os.path.exists(target):
        shutil.rmtree(target)
    os.makedirs(target)
    subprocess.run(['7z', 'x', '-y', '-o' + target, archive, '-x!*.DS_Store', '-x!*desktop.ini'],
                   check=True, stdout=subprocess.DEVNULL)
    open(done, 'w').close()
    return os.path.join(target, src['unpack'])


def fetch(sid):
    """Path of the verified source (the unpacked folder for an archive), downloading it if needed."""
    src = SOURCES[sid]
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, src['file'])
    stamp = path + '.verified'
    if os.path.exists(path):
        # Hashing 190 MB on every build is slow; the stamp records the size and mtime that were verified.
        key = '%d %d %s' % (os.path.getsize(path), int(os.path.getmtime(path)), src['sha256'])
        if not os.path.exists(stamp) or open(stamp).read() != key:
            try:
                _verify(sid, path)
            except FetchError as e:
                # A damaged or stale copy: fetch it again, and refuse if the download does not match either.
                print('fetch_audio: ' + str(e), file=sys.stderr)
                os.remove(path)
    if not os.path.exists(path):
        _download(sid, path)
    key = '%d %d %s' % (os.path.getsize(path), int(os.path.getmtime(path)), src['sha256'])
    if not os.path.exists(stamp) or open(stamp).read() != key:
        with open(stamp, 'w') as f:
            f.write(key)
    return _unpack(sid, path) if src.get('unpack') else path


def fetch_all():
    return {sid: fetch(sid) for sid in SOURCES}


if __name__ == '__main__':
    try:
        for sid, where in fetch_all().items():
            print('%-6s %s' % (sid, where))
    except FetchError as e:
        print('fetch_audio: ' + str(e), file=sys.stderr)
        sys.exit(1)
