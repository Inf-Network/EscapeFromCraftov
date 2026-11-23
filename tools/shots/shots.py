#!/usr/bin/env python3
"""Screenshots from a real Minecraft client, for what a headless bot cannot see.

Gun models, aiming, animations, menus, the HUD and effects only show up in a rendered client. This
runs vanilla 1.21.11 on a virtual X display (Xvfb) with Mesa's software renderer (llvmpipe), joins a
local test server in offline mode, loads the server resource pack without a prompt and drives it
with the scene scripts in tools/shots/scenes/: RCON to set things up, xdotool for keys and mouse,
ImageMagick `import` for the picture. Screenshots land in tools/shots/out/<shot>.png.

  tools/shots/shots.py run [scene ...]   start the server and client if they are not running, run
                                          the scenes (default: lobby gun inventory deploy raid) and
                                          stop what it started
  tools/shots/shots.py up                start the server and client and leave them running
  tools/shots/shots.py down              stop what `up` (or `run --keep`) started
  tools/shots/shots.py status            what is running
  tools/shots/shots.py scenes            list the scenes
  tools/shots/shots.py setup             (re)create the test server in --server-dir
  tools/shots/shots.py send <shot> ...   copy PNGs to the owner's Mac and open them there

Common options (before or after the subcommand): --port (env SHOTS_PORT, default 25565),
--rcon-port (SHOTS_RCON_PORT, default port + 10), --server-dir (default server/run), --name (the
client's offline user name, default ShotCam), --size (window, default 1600x900) and --gui-scale
(default 0, auto: 3 at 1600x900; `--size 2560x1440 --gui-scale 3` is a 1440p screen with the GUI
at 3). A server already on the ports is only used when it
runs from --server-dir, so a second checkout needs a --port of its own. `run --build` rebuilds the
plugin and restarts the server first (only one shots started), `run --fresh` resets the player to
a new profile with the starter kit, and `run --send` sends every shot of the run to the Mac. A
scene can take an argument: `run gun:k47`.

The first run creates a venv with minecraft-launcher-lib (BSD-2-Clause) in tools/shots/.cache/ and
downloads the client (about 550 MB) there. Needs Xvfb, xdotool, ImageMagick and Mesa.
"""
import argparse
import contextlib
import hashlib
import json
import math
import os
import re
import shutil
import signal
import socket
import struct
import subprocess
import sys
import time
import uuid
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
CACHE = HERE / '.cache'
OUT = HERE / 'out'
VENV = CACHE / 'venv'
MC_DIR = CACHE / 'minecraft'     # versions, libraries, assets
GAME_DIR = CACHE / 'game'        # options.txt, servers.dat, logs, downloaded server packs
STATE = CACHE / 'state.json'
SCENES = HERE / 'scenes'
VERSION = '1.21.11'
LAUNCHER_LIB = 'minecraft-launcher-lib==8.0'
DEFAULT_SCENES = ['lobby', 'gun', 'inventory', 'deploy', 'raid']
START_TIMEOUT = 240  # seconds for the test server to start and answer RCON

# Client options forced on every launch. The first-run screens (accessibility onboarding,
# multiplayer warning, tutorial toasts) would sit in front of the game, and a client that pauses on
# lost focus would open the pause menu whenever the X focus wanders.
OPTIONS = {
    'onboardAccessibility': 'false',
    'skipMultiplayerWarning': 'true',
    'joinedFirstServer': 'true',
    'tutorialStep': 'none',
    'pauseOnLostFocus': 'false',
    'realmsNotifications': 'false',
    'narrator': '0',
    'fullscreen': 'false',
    'enableVsync': 'false',
    'maxFps': '60',
    'renderDistance': '8',
    'simulationDistance': '5',
    'soundCategory_master': '0.0',
}

T0 = time.time()


def log(*parts):
    print(f'[{time.time() - T0:6.1f}s]', *parts, flush=True)


class Fail(Exception):
    """A step that did not work; the message says what to look at."""


# ---------------------------------------------------------------------------------------- venv

def in_venv():
    return Path(sys.prefix).resolve() == VENV.resolve()


def ensure_venv():
    """Re-runs this script inside tools/shots/.cache/venv, creating it on first use."""
    if in_venv():
        return
    py = VENV / 'bin' / 'python'
    marker = VENV / f'.installed-{LAUNCHER_LIB}'
    if not marker.exists():
        log('creating venv with', LAUNCHER_LIB)
        CACHE.mkdir(parents=True, exist_ok=True)
        subprocess.run([sys.executable, '-m', 'venv', str(VENV)], check=True)
        subprocess.run([str(py), '-m', 'pip', 'install', '-q', LAUNCHER_LIB], check=True)
        marker.touch()
    os.execv(str(py), [str(py), str(Path(__file__).resolve()), *sys.argv[1:]])


# ---------------------------------------------------------------------------------------- rcon

def rcon(cfg, command, timeout=10, port=None):
    """Runs a console command (on cfg's RCON port unless another is given); returns the reply
    without colour codes."""
    def send(sock, rid, kind, body):
        data = body.encode('utf-8')
        sock.sendall(struct.pack('<iii', len(data) + 10, rid, kind) + data + b'\0\0')

    def recv(sock):
        def exact(n):
            buf = b''
            while len(buf) < n:
                chunk = sock.recv(n - len(buf))
                if not chunk:
                    raise ConnectionError('rcon closed')
                buf += chunk
            return buf
        length, rid, _ = struct.unpack('<iii', exact(12))
        return rid, exact(length - 8)[:-2].decode('utf-8', 'replace')

    with socket.create_connection(('127.0.0.1', port or cfg.rcon_port), timeout=timeout) as sock:
        send(sock, 1, 3, cfg.rcon_password)
        rid, _ = recv(sock)
        if rid == -1:
            raise Fail('rcon password rejected')
        send(sock, 2, 2, command)
        # Long replies come in several packets; the server answers the empty type-0 packet sent
        # after the command only once the command's reply is complete.
        send(sock, 3, 0, '')
        out = []
        while True:
            rid, body = recv(sock)
            if rid != 2:
                break
            out.append(body)
    return re.sub(r'§.', '', ''.join(out))


def rcon_alive(cfg):
    try:
        rcon(cfg, 'list', timeout=3)
        return True
    except (OSError, Fail):
        return False


# ---------------------------------------------------------------------------------------- state

def load_state():
    try:
        return json.loads(STATE.read_text())
    except (OSError, ValueError):
        return {}


def save_state(state):
    CACHE.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(state, indent=2))


def pid_alive(pid, needle=None):
    if not pid:
        return False
    try:
        cmdline = Path(f'/proc/{pid}/cmdline').read_bytes().replace(b'\0', b' ').decode('utf-8', 'replace')
    except OSError:
        return False
    return needle is None or needle in cmdline


def stop_pid(pid, needle, grace=15):
    """SIGTERM, then SIGKILL after `grace` seconds; only if the process still is what we started."""
    if not pid_alive(pid, needle):
        return
    with contextlib.suppress(ProcessLookupError):
        os.killpg(pid, signal.SIGTERM)
    end = time.time() + grace
    while time.time() < end and pid_alive(pid, needle):
        time.sleep(0.3)
    if pid_alive(pid, needle):
        with contextlib.suppress(ProcessLookupError):
            os.killpg(pid, signal.SIGKILL)


# ---------------------------------------------------------------------------------------- server

def listener_pid(port):
    """The process listening on this TCP port: None when nothing listens, 0 when the socket belongs
    to a process this user cannot inspect."""
    sockets = set()
    for table in ('/proc/net/tcp', '/proc/net/tcp6'):
        with contextlib.suppress(OSError):
            for row in Path(table).read_text().splitlines()[1:]:
                fields = row.split()
                if len(fields) > 9 and fields[3] == '0A' and int(fields[1].rsplit(':', 1)[1], 16) == port:
                    sockets.add(f'socket:[{fields[9]}]')
    if not sockets:
        return None
    for proc in Path('/proc').iterdir():
        if not proc.name.isdigit():
            continue
        with contextlib.suppress(OSError):
            for fd in (proc / 'fd').iterdir():
                with contextlib.suppress(OSError):  # descriptors come and go while we look
                    if os.readlink(fd) in sockets:
                        return int(proc.name)
    return 0


def check_server(cfg):
    """The pid of the server on cfg's ports, or None when neither port is in use. shots reads and
    writes --server-dir (plugin jar, profiles, map files), so a server on those ports that does not
    run from there (another checkout's, someone else's) is refused rather than used."""
    pids = {listener_pid(port) for port in (cfg.port, cfg.rcon_port)} - {None}
    if not pids:
        return None
    if 0 in pids:
        raise Fail(f'port {cfg.port} or {cfg.rcon_port} belongs to another user\'s process; '
                   'pick free ports with --port and --rcon-port')
    if len(pids) > 1:
        raise Fail(f'ports {cfg.port} and {cfg.rcon_port} belong to different processes ({", ".join(map(str, sorted(pids)))}); '
                   'pick free ports with --port and --rcon-port')
    pid = pids.pop()
    try:
        cwd = Path(os.readlink(f'/proc/{pid}/cwd'))
    except OSError:
        cwd = None
    if cwd != cfg.server_dir:
        raise Fail(f'the server on port {cfg.port} (pid {pid}) runs from {cwd or "an unknown directory"}, not from '
                   f'{cfg.server_dir}; give this checkout a server of its own with --port (and --rcon-port)')
    return pid


def set_properties(path, values):
    lines = path.read_text(encoding='utf-8').splitlines() if path.exists() else []
    seen = set()
    for i, line in enumerate(lines):
        key = line.split('=', 1)[0].strip()
        if key in values:
            lines[i] = f'{key}={values[key]}'
            seen.add(key)
    lines += [f'{k}={v}' for k, v in values.items() if k not in seen]
    path.write_text('\n'.join(lines) + '\n', encoding='utf-8')


def setup_server(cfg):
    """A local offline-mode test server for screenshots: RCON on, test mode on, short queue."""
    run_dir = cfg.server_dir
    if check_server(cfg):
        raise Fail(f'the server in {run_dir} is running; stop it before setting it up again')
    log('setting up the test server in', run_dir)
    env = dict(os.environ, RUN_DIR=str(run_dir))
    subprocess.run([str(ROOT / 'server' / 'setup.sh'), '--local', '--port', str(cfg.port)], env=env, check=True)
    # Loopback only: the client, the puppet, RCON and the pack download all come from this machine,
    # and an offline-mode server with a known RCON password must not be reachable from outside.
    set_properties(run_dir / 'server.properties', {
        'server-ip': '127.0.0.1', 'server-port': cfg.port, 'enable-rcon': 'true', 'rcon.port': cfg.rcon_port,
        'rcon.password': cfg.rcon_password, 'white-list': 'false', 'online-mode': 'false',
    })
    # A fresh server starts from the plugin's full default config. Writing only the two keys that
    # differ would not do: a section that exists only in the jar's defaults reads back as an empty
    # section (Bukkit's getConfigurationSection), so the difficulty tiers and the starter loadout
    # would be missing. An existing config is edited in place.
    conf = run_dir / 'plugins' / 'Craftov' / 'config.yml'
    conf.parent.mkdir(parents=True, exist_ok=True)
    if not conf.exists():
        with zipfile.ZipFile(run_dir / 'plugins' / 'Craftov.jar') as jar:
            conf.write_bytes(jar.read('config.yml'))
    text = conf.read_text(encoding='utf-8')
    text = re.sub(r'(?m)^(test:\n(?:[ \t]*#.*\n)*[ \t]+enabled:) *false', r'\1 true', text)
    text = re.sub(r'(?m)^(match:\n(?:[ \t]*#.*\n|[ \t]+\S.*\n)*?[ \t]+wait-seconds:) *\d+', r'\1 5', text)
    conf.write_text(text, encoding='utf-8')
    if not re.search(r'(?m)^test:\n(?:[ \t]*#.*\n)*[ \t]+enabled: true', text):
        log('warning: could not turn on test.enabled in', conf)
    log('test server ready: port', cfg.port, 'rcon', cfg.rcon_port)


def read_properties(path):
    props = {}
    with contextlib.suppress(OSError):
        for line in path.read_text(encoding='utf-8').splitlines():
            if '=' in line and not line.lstrip().startswith('#'):
                key, value = line.split('=', 1)
                props[key.strip()] = value.strip()
    return props


def property_problems(cfg):
    """What in --server-dir's server.properties would keep shots from reaching that server."""
    props = read_properties(cfg.server_dir / 'server.properties')
    problems = []
    for key, want, default in (('server-port', str(cfg.port), '25565'), ('enable-rcon', 'true', 'false'),
                               ('rcon.port', str(cfg.rcon_port), '25575')):
        have = props.get(key) or default
        if have != want:
            problems.append(f'{key}={have} (shots uses {want})')
    if props.get('rcon.password', '') != cfg.rcon_password:
        problems.append('rcon.password is not the one shots uses (--rcon-password)')
    return problems


def start_server(cfg):
    """Starts the server in --server-dir and returns its pid once RCON answers. A server that does
    not get that far is stopped again, so nothing is left running that `down` would not know."""
    run_dir = cfg.server_dir
    problems = property_problems(cfg)
    if problems:
        raise Fail(f'{run_dir / "server.properties"} does not fit: {"; ".join(problems)}. Pass the ports it uses, '
                   'or let `shots.py setup` (same --port) write the screenshot settings')
    console = run_dir / 'console.log'
    offset = console.stat().st_size if console.exists() else 0
    log('starting the test server in', run_dir)
    with open(console, 'ab') as out:
        proc = subprocess.Popen(['./start.sh'], cwd=run_dir, stdout=out, stderr=subprocess.STDOUT,
                                stdin=subprocess.DEVNULL, start_new_session=True)
    done = False
    try:
        end = time.time() + START_TIMEOUT
        while time.time() < end:
            if proc.poll() is not None:
                raise Fail(f'the server exited ({proc.returncode}); see {console}')
            if not done:
                with open(console, 'rb') as f:
                    f.seek(offset)
                    done = b'Done (' in f.read()
            if done and rcon_alive(cfg):
                log('server up')
                return proc.pid
            time.sleep(1)
        if done:
            raise Fail(f'the server started but RCON on port {cfg.rcon_port} does not answer; see {console}')
        raise Fail(f'the server did not finish starting in {START_TIMEOUT} s; see {console}')
    except BaseException:
        # Still our unreaped child, so the pid cannot belong to anything else yet.
        if proc.poll() is None:
            log('stopping the server that did not come up')
            with contextlib.suppress(ProcessLookupError):
                os.killpg(proc.pid, signal.SIGTERM)
            try:
                proc.wait(60)
            except subprocess.TimeoutExpired:
                with contextlib.suppress(ProcessLookupError):
                    os.killpg(proc.pid, signal.SIGKILL)
                proc.wait()
        raise


def stop_server(cfg, pid, rcon_port):
    log('stopping the test server')
    with contextlib.suppress(OSError, Fail):
        rcon(cfg, 'stop', port=rcon_port)
    end = time.time() + 90
    while time.time() < end and pid_alive(pid, 'paper.jar'):
        time.sleep(0.5)
    stop_pid(pid, 'paper.jar', grace=10)


def build_plugin(cfg):
    log('building the plugin')
    subprocess.run([str(ROOT / 'gradlew'), '-p', str(ROOT), 'jar', '--quiet'], check=True)
    jar = max((ROOT / 'build' / 'libs').glob('Craftov-*.jar'), key=lambda p: p.stat().st_mtime)
    dest = cfg.server_dir / 'plugins' / 'Craftov.jar'
    if not dest.parent.is_dir():
        raise Fail(f'{dest.parent} does not exist; set the server up with `shots.py setup` first')
    # Rename into place, so a running server keeps reading the old file until it restarts.
    tmp = dest.with_suffix('.jar.new')
    shutil.copyfile(jar, tmp)
    tmp.replace(dest)
    log('installed', jar.name, 'in', dest.parent)


# ---------------------------------------------------------------------------------------- client

def offline_uuid(name):
    """The UUID an offline-mode server gives this name (Java's UUID.nameUUIDFromBytes)."""
    digest = bytearray(hashlib.md5(('OfflinePlayer:' + name).encode('utf-8')).digest())
    digest[6] = digest[6] & 0x0F | 0x30
    digest[8] = digest[8] & 0x3F | 0x80
    return str(uuid.UUID(bytes=bytes(digest)))


def nbt_string(name, value):
    raw, val = name.encode('utf-8'), value.encode('utf-8')
    return b'\x08' + struct.pack('>H', len(raw)) + raw + struct.pack('>H', len(val)) + val


def nbt_byte(name, value):
    raw = name.encode('utf-8')
    return b'\x01' + struct.pack('>H', len(raw)) + raw + struct.pack('>b', value)


def write_servers_dat(address):
    """One saved server whose resource packs are always accepted. Quick Play looks its address up
    in this list, so the pack prompt never appears."""
    entry = nbt_string('name', 'Craftov') + nbt_string('ip', address) + nbt_byte('acceptTextures', 1) \
        + nbt_byte('hidden', 0) + b'\x00'
    servers = b'\x09' + struct.pack('>H', 7) + b'servers' + b'\x0a' + struct.pack('>i', 1) + entry
    (GAME_DIR / 'servers.dat').write_bytes(b'\x0a\x00\x00' + servers + b'\x00')


def write_options(lang, gui_scale=0):
    path = GAME_DIR / 'options.txt'
    lines = path.read_text(encoding='utf-8').splitlines() if path.exists() else []
    # guiScale 0 is the client's "auto": the largest scale that still fits the window.
    forced = dict(OPTIONS, lang=lang, guiScale=str(gui_scale))
    kept = [line for line in lines if line.split(':', 1)[0] not in forced]
    path.write_text('\n'.join(kept + [f'{k}:{v}' for k, v in forced.items()]) + '\n', encoding='utf-8')


def install_client():
    import minecraft_launcher_lib as mll
    import minecraft_launcher_lib.install as mll_install
    if (MC_DIR / 'versions' / VERSION / f'{VERSION}.jar').exists():
        return mll
    log('downloading Minecraft', VERSION, 'into', MC_DIR)
    # The system JDK runs the client, so skip the Java runtime the library would fetch from Mojang.
    mll_install.install_jvm_runtime = lambda *args, **kwargs: None
    mll.install.install_minecraft_version(VERSION, str(MC_DIR), callback={'setStatus': lambda s: None})
    return mll


def start_display(size):
    """Xvfb on the first free display number, which it reports through -displayfd."""
    read_fd, write_fd = os.pipe()
    proc = subprocess.Popen(['Xvfb', '-displayfd', str(write_fd), '-screen', '0', f'{size}x24', '-nolisten', 'tcp'],
                            pass_fds=(write_fd,), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            start_new_session=True)
    os.close(write_fd)
    number = b''
    with os.fdopen(read_fd, 'rb') as pipe:
        while not number.endswith(b'\n'):
            chunk = pipe.read(1)
            if not chunk:
                raise Fail('Xvfb did not start')
            number += chunk
    display = ':' + number.decode().strip()
    log('virtual display', display, size)
    return display, proc.pid


def client_log():
    path = GAME_DIR / 'logs' / 'latest.log'
    return path.read_text(encoding='utf-8', errors='replace') if path.exists() else ''


def start_client(cfg, display):
    mll = install_client()
    GAME_DIR.mkdir(parents=True, exist_ok=True)
    address = f'127.0.0.1:{cfg.port}'
    write_servers_dat(address)
    write_options(cfg.lang, cfg.gui_scale)
    width, height = cfg.size.split('x')
    command = mll.command.get_minecraft_command(VERSION, str(MC_DIR), {
        'username': cfg.name, 'uuid': offline_uuid(cfg.name), 'token': '0',
        'executablePath': cfg.java, 'jvmArguments': ['-Xms1G', '-Xmx3G'],
        'gameDirectory': str(GAME_DIR), 'launcherName': 'craftov-shots', 'launcherVersion': '1',
        'customResolution': True, 'resolutionWidth': width, 'resolutionHeight': height,
        'quickPlayMultiplayer': address,
    })
    env = dict(os.environ, DISPLAY=display, LIBGL_ALWAYS_SOFTWARE='1', GALLIUM_DRIVER='llvmpipe',
               ALSOFT_DRIVERS='null')  # OpenAL's null backend: no sound card here
    env.pop('WAYLAND_DISPLAY', None)
    # The join checks read logs/latest.log, which still is the previous client's log (with its
    # disconnect and its pack) until the new client starts writing; keep that one as previous.log.
    old_log = GAME_DIR / 'logs' / 'latest.log'
    if old_log.exists():
        old_log.replace(old_log.with_name('previous.log'))
    log('starting the client as', cfg.name, '->', address)
    with open(GAME_DIR / 'client-console.log', 'wb') as out:
        proc = subprocess.Popen(command, cwd=GAME_DIR, env=env, stdout=out, stderr=subprocess.STDOUT,
                                stdin=subprocess.DEVNULL, start_new_session=True)
    return proc.pid


def server_log_size(cfg):
    path = cfg.server_dir / 'logs' / 'latest.log'
    return path.stat().st_size if path.exists() else 0


def kick_reason(cfg, name, offset):
    """Why the server dropped `name` since `offset` in its log (a kick, a ban, the whitelist, a
    declined pack), or None."""
    try:
        with open(cfg.server_dir / 'logs' / 'latest.log', 'rb') as f:
            f.seek(offset)
            text = f.read().decode('utf-8', 'replace')
    except OSError:
        return None
    n = re.escape(name)
    found = re.search(rf'(?:Disconnecting {n}\b.*?|\b{n}\b[^\n]*? lost connection): (.*)', text)
    return found.group(1).strip() if found else None


def wait_joined(cfg, client_pid, log_offset):
    """Waits until the player is online with the server resource pack applied. `log_offset` is the
    size of the server log when the client started, so earlier disconnects do not count."""
    end = time.time() + 300
    joined = False
    while time.time() < end:
        if not pid_alive(client_pid):
            raise Fail(f'the client exited; see {GAME_DIR / "client-console.log"}')
        reason = kick_reason(cfg, cfg.name, log_offset)
        if reason:
            raise Fail(f'the server dropped {cfg.name}: {reason}')
        text = client_log()
        refused = re.search(r"Couldn't connect to server.*|Client disconnected with reason: .*", text)
        if refused:
            raise Fail(f'the client could not stay on the server ({refused.group(0).strip()}); '
                       f'see {GAME_DIR / "logs" / "latest.log"}')
        if not joined:
            joined = player_online(cfg)
            if joined:
                log('joined the server')
        state, detail = pack_state(text)
        if state == 'failed':
            raise Fail(f'the resource pack did not load ({detail}); see {GAME_DIR / "logs" / "latest.log"}')
        if joined and state == 'applied':
            log('server resource pack applied')
            return
        time.sleep(1)
    raise Fail(f'not in game with the resource pack after 5 minutes; see {GAME_DIR / "logs" / "latest.log"}')


# Client log lines (1.21.11) meaning the server pack is not on screen: the download failed, or the
# resource reload with the pack failed, after which the client drops every selected pack and reloads
# vanilla only.
PACK_FAILED = re.compile(r'Caught error loading resourcepacks|Pack \S+ failed to download|Failed to apply pack')


def pack_state(text):
    """('applied' | 'failed' | None, detail) for the client log so far. The client logs a reload when
    it starts, so a pack only counts as applied when the last reload has a server pack in its stack,
    has stitched its atlases (`Created: ...`) and nothing failed."""
    failed = PACK_FAILED.search(text)
    if failed:
        start = text.rfind('\n', 0, failed.start()) + 1
        return 'failed', text[start:].split('\n', 1)[0].strip()
    reloads = list(re.finditer(r'Reloading ResourceManager: (.*)', text))
    if reloads and 'server/' in reloads[-1].group(1) and 'Created: ' in text[reloads[-1].end():]:
        return 'applied', reloads[-1].group(1)
    return None, ''


# ---------------------------------------------------------------------------------------- session

class Session:
    """What a scene script gets: RCON, keyboard and mouse on the client, and screenshots."""

    def __init__(self, cfg, display):
        self.cfg = cfg
        self.name = cfg.name
        self.display = display
        self.arg = None
        self.shots = []
        self.raw = []  # (raw frame, PNG) of quick shots not written yet
        self.env = dict(os.environ, DISPLAY=display)
        self.window = self._window()

    # -- low level

    def _window(self):
        end = time.time() + 60
        while time.time() < end:
            out = subprocess.run(['xdotool', 'search', '--onlyvisible', '--name', '^Minecraft'], env=self.env,
                                 capture_output=True, text=True).stdout.split()
            if out:
                wid = out[-1]
                width, height = self.cfg.size.split('x')
                self.xdo('windowmove', wid, '0', '0')
                self.xdo('windowsize', wid, width, height)
                self.xdo('windowfocus', wid)
                return wid
            time.sleep(1)
        raise Fail('no Minecraft window on the virtual display')

    def xdo(self, *args):
        subprocess.run(['xdotool', *args], env=self.env, check=True)

    def rcon(self, command):
        return rcon(self.cfg, command)

    def wait(self, seconds):
        time.sleep(seconds)

    def wait_until(self, check, timeout, what):
        end = time.time() + timeout
        while time.time() < end:
            if check():
                return
            time.sleep(0.5)
        raise Fail(f'timed out waiting for {what}')

    # -- input

    def focus(self):
        self.xdo('windowfocus', self.window)

    def key(self, *keys, gap=0.25):
        """Taps keys in order (xdotool key names: e, Escape, F5, 1, Return, ctrl+a ...)."""
        self.focus()
        for k in keys:
            self.xdo('key', k)
            time.sleep(gap)

    def down(self, key):
        self.focus()
        self.xdo('keydown', key)

    def up(self, key):
        self.xdo('keyup', key)

    @contextlib.contextmanager
    def mouse(self, button=3):
        """Holds a mouse button (1 left, 2 middle, 3 right) for the duration of the block."""
        self.focus()
        self.xdo('mousedown', str(button))
        try:
            yield
        finally:
            self.xdo('mouseup', str(button))

    def mouse_to(self, x, y):
        """Moves the pointer to a screen pixel. Only while a menu is open: in game the pointer is
        captured and moving it turns the camera."""
        self.xdo('mousemove', str(x), str(y))

    def park_mouse(self):
        """Moves the pointer into the corner so no slot under it shows a tooltip."""
        self.mouse_to(1, 1)
        time.sleep(0.3)

    def gui_scale(self):
        """The GUI scale the client uses: --gui-scale, or what "auto" picks for this window."""
        width, height = (int(v) for v in self.cfg.size.split('x'))
        if self.cfg.gui_scale:
            return self.cfg.gui_scale
        scale = 1
        while width // (scale + 1) >= 320 and height // (scale + 1) >= 240:
            scale += 1
        return scale

    def slot_center(self, index, rows):
        """Screen pixel at the middle of slot `index` of an open chest menu with `rows` rows."""
        width, height = (int(v) for v in self.cfg.size.split('x'))
        scale = self.gui_scale()
        gw, gh = -(-width // scale), -(-height // scale)
        left, top = (gw - 176) // 2, (gh - (114 + rows * 18)) // 2
        x = left + 8 + (index % 9) * 18 + 8
        own = index - rows * 9   # past the menu's rows: the player's inventory, then the hotbar
        if own < 0:
            y = top + 18 + (index // 9) * 18 + 8
        elif own < 27:
            y = top + rows * 18 + 31 + (own // 9) * 18 + 8
        else:
            y = top + rows * 18 + 89 + 8
        return x * scale, y * scale

    def click_slot(self, index, rows, button=1):
        """Clicks a slot of the open chest menu (1 left, 3 right)."""
        self.mouse_to(*self.slot_center(index, rows))
        time.sleep(0.2)
        self.xdo('click', str(button))
        time.sleep(0.6)

    def type(self, text):
        self.focus()
        self.xdo('type', '--delay', '30', text)

    def chat(self, text):
        """Opens chat, types the line and sends it. A leading slash makes it a command."""
        self.key('t')
        time.sleep(0.4)
        self.type(text)
        time.sleep(0.2)
        self.key('Return')

    def cmd(self, command):
        self.chat('/' + command.lstrip('/'))

    def clear_chat(self):
        """F3+D: empties the chat box so old messages stay out of the picture."""
        self.focus()
        self.xdo('keydown', 'F3')
        self.xdo('key', 'd')
        self.xdo('keyup', 'F3')
        time.sleep(0.3)

    def select(self, slot):
        """Selects hotbar slot 0-8."""
        self.key(str(slot + 1))

    # -- game state through RCON

    def data(self, path, who=None):
        """`data get entity <player> <path>`, the value only (None if absent). `who` defaults to the
        camera player; scenes pass a puppet's name to ask about it."""
        out = self.rcon(f'data get entity {who or self.name} {path}')
        marker = 'has the following entity data: '
        return out.split(marker, 1)[1].strip() if marker in out else None

    def dimension(self):
        return (self.data('Dimension') or '').strip('"')

    def gamemode(self):
        value = self.data('playerGameType')
        return int(value) if value is not None and value.isdigit() else -1

    def in_lobby(self):
        return self.dimension().endswith('craftov_lobby')

    def in_raid(self):
        return 'craftov_raid' in self.dimension() and self.gamemode() == 2

    def item_in(self, slot, who=None):
        path = f'Inventory[{{Slot:{slot}b}}].components."minecraft:custom_data".PublicBukkitValues."craftov:id"'
        value = self.data(path, who)
        return value.strip('"') if value else None

    def slot_of(self, item, who=None):
        """The hotbar slot (0-8) holding this Craftov item, or None."""
        return next((slot for slot in range(9) if self.item_in(slot, who) == item), None)

    def count(self, item):
        """How many of this Craftov item the player carries (inventory and equipment)."""
        predicate = f'*[minecraft:custom_data~{{PublicBukkitValues:{{"craftov:id":"{item}"}}}}]'
        out = self.rcon(f'execute if items entity {self.name} container.* {predicate}')
        match = re.search(r'count: (\d+)', out, re.IGNORECASE)
        return int(match.group(1)) if match else 0

    def ammo_for(self, gun):
        """The first ammo id matching the gun's caliber in the server's items.yml (or the repo's)."""
        for path in (self.cfg.server_dir / 'plugins' / 'Craftov' / 'items.yml', ROOT / 'src' / 'main' / 'resources' / 'items.yml'):
            try:
                text = path.read_text(encoding='utf-8')
            except OSError:
                continue
            gun_def = re.search(rf'(?m)^  {re.escape(gun)}:\s*\n((?:    .*\n)+)', text)
            caliber = gun_def and re.search(r'caliber: "([^"]+)"', gun_def.group(1))
            if caliber:
                ammo = re.search(rf'(?m)^  (\w+):\s*{{.*category: AMMO.*caliber: "{re.escape(caliber.group(1))}"', text)
                return ammo.group(1) if ammo else None
        return None

    def give(self, item, amount=1, who=None):
        self.rcon(f'cv give {who or self.name} {item} {amount}')

    def category_of(self, item):
        """The item's category in the server's items.yml (or the repo's), e.g. WEAPON, or None."""
        for path in (self.cfg.server_dir / 'plugins' / 'Craftov' / 'items.yml', ROOT / 'src' / 'main' / 'resources' / 'items.yml'):
            try:
                text = path.read_text(encoding='utf-8')
            except OSError:
                continue
            found = re.search(rf'(?m)^  {re.escape(item)}:\s*{{?.*?category: (\w+)', text)
            if found is None:
                block = re.search(rf'(?m)^  {re.escape(item)}:\s*\n((?:    .*\n)+)', text)
                found = block and re.search(r'category: (\w+)', block.group(1))
            if found:
                return found.group(1)
        return None

    def hotbar_slot(self, item, who=None):
        """The hotbar slot holding the item, giving it first if it is not there yet. Guns only go on
        keys 1-2 (CarryService zone rules), so key 1 is emptied first when both hold something."""
        slot = self.slot_of(item, who)
        if slot is None:
            if self.category_of(item) == 'WEAPON' and all(self.item_in(k, who) for k in (0, 1)):
                self.rcon(f'item replace entity {who or self.name} hotbar.0 with minecraft:air')
                time.sleep(0.3)
            self.give(item, who=who)
            time.sleep(0.5)
            slot = self.slot_of(item, who)
        if slot is None:
            raise Fail(f'{item} did not land in the hotbar of {who or self.name}')
        return slot

    def hold(self, item):
        """Puts the item in the hotbar if it is not there yet and selects it."""
        slot = self.hotbar_slot(item)
        self.select(slot)
        return slot

    def look(self, yaw, pitch):
        self.rcon(f'execute as {self.name} at @s run tp @s ~ ~ ~ {yaw} {pitch}')

    def lobby(self):
        """Back to the lobby spawn (the player is op; this is /cv lobby)."""
        self.cmd('cv lobby')
        self.wait_until(self.in_lobby, 30, 'the lobby')
        time.sleep(1.5)

    def raid_players(self):
        """{raid id: players in it} from `cv raids` (one row per raid: `R3 试验场 常规 RUNNING · ... · 玩家 1 · AI 4`)."""
        return {rid: int(n) for rid, n in re.findall(r'(R\d+) [^\n]*?玩家 (\d+)', self.rcon('cv raids'))}

    def raids(self):
        return set(self.raid_players())

    def always_extract(self, map_id):
        """(x, y, z) of the map's first always-open extract, read from the server's map file."""
        path = self.cfg.server_dir / 'plugins' / 'Craftov' / 'maps' / f'{map_id}.yml'
        try:
            text = path.read_text(encoding='utf-8')
        except OSError:
            return None
        if '\nextracts:' not in text:
            return None
        section = re.split(r'\n(?=[A-Za-z_])', text.split('\nextracts:', 1)[1])[0]
        for block in section.split('\n- '):
            if re.search(r'(?m)^\s*type: ALWAYS\s*$', block):
                coords = [re.search(rf'(?m)^\s*{axis}: (-?[\d.]+)\s*$', block) for axis in 'xyz']
                if all(coords):
                    return tuple(float(c.group(1)) for c in coords)
        return None

    def extract(self, map_id, raids=()):
        """Leaves the raid through the map's always-open extract, keeping the gear for the next run.
        `raids` are the raids that appeared since the player queued. If the extract does not work
        and exactly one of them is left with the player alone in it, that raid is ended instead (the
        player is then missing in action and loses what it carried). Ending a raid makes everyone
        still in it missing in action, so with anyone else around the player stays and this fails."""
        spot = self.always_extract(map_id)
        if spot:
            self.rcon(f'execute as {self.name} at @s run tp @s {spot[0]} {spot[1]} {spot[2]}')
            with contextlib.suppress(Fail):
                self.wait_until(self.in_lobby, 40, 'the extract')
                # The settlement screen opens 30 ticks after arriving; close it so the next scene
                # can type commands again.
                time.sleep(2.5)
                self.rcon(f'cv close {self.name}')
                time.sleep(0.5)
                return
        candidates = {rid: n for rid, n in self.raid_players().items() if rid in raids}
        if len(candidates) != 1 or next(iter(candidates.values())) != 1:
            rows = ', '.join(f'{rid} with {n} in it' for rid, n in sorted(candidates.items())) or 'none'
            raise Fail(f'{self.name} could not reach the extract and is still in the raid. Ending a raid makes '
                       'everyone in it missing in action, so only a single new raid with one player is ended, '
                       f'and the new raids are: {rows}. Nothing was ended; end its raid by hand (`cv end <id>`)')
        raid = next(iter(candidates))
        log(f'no extract worked; ending {raid}, where {self.name} is alone')
        self.rcon(f'cv end {raid}')
        self.wait_until(self.in_lobby, 90, 'the way back to the lobby')
        time.sleep(1.5)

    @contextlib.contextmanager
    def puppet(self, name='ShotMate'):
        """A second player to put in front of the camera, for what other players see: a mineflayer
        bot (tools/bot/puppet.js; needs `npm install` in tools/bot). It leaves when the block ends."""
        bot_dir = ROOT / 'tools' / 'bot'
        if not (bot_dir / 'node_modules' / 'mineflayer').exists():
            raise Fail('tools/bot/node_modules is missing; run npm install in tools/bot')
        log_path = CACHE / 'puppet.log'
        granted = grant(self.cfg, name, op=False)
        with open(log_path, 'wb') as out:
            proc = subprocess.Popen(['node', 'puppet.js', name], cwd=bot_dir, env=dict(os.environ, PORT=str(self.cfg.port)),
                                    stdin=subprocess.PIPE, stdout=out, stderr=subprocess.STDOUT)
        try:
            self.wait_until(lambda: b'ready' in log_path.read_bytes() or proc.poll() is not None, 60, f'{name} to join')
            if proc.poll() is not None:
                kicked = re.search(r'(?m)^(kicked|error) (.*)$', log_path.read_text(encoding='utf-8', errors='replace'))
                raise Fail(f'{name} could not join' + (f' ({kicked.group(1)}: {kicked.group(2)})' if kicked else '')
                           + f'; see {log_path}')
            self.wait_until(lambda: (self.data('Dimension', name) or '').strip('"').endswith('craftov_lobby'), 30, f'{name} in the lobby')
            time.sleep(2)
            yield Puppet(self, name, proc)
        finally:
            with contextlib.suppress(OSError):
                proc.stdin.close()
            try:
                proc.wait(10)
            except subprocess.TimeoutExpired:
                proc.kill()
            revoke(self.cfg, name, granted)

    # -- output

    def shot(self, name, quick=False):
        """Saves the whole virtual screen as tools/shots/out/<name>.png. Writing the PNG takes about
        0.7 s; `quick` grabs the raw frame (about 20 ms) and writes the PNG when the scene is done
        (develop), for shots a fraction of a second apart such as the frames of an animation."""
        OUT.mkdir(parents=True, exist_ok=True)
        path = OUT / f'{name}.png'
        target = path.with_suffix('.ppm') if quick else path
        subprocess.run(['import', '-window', 'root', '-display', self.display, str(target)], check=True)
        if quick:
            self.raw.append((target, path))
        self.shots.append(name)
        log('shot', path.relative_to(ROOT))
        return path

    def develop(self):
        """Writes the PNGs of the quick shots taken so far."""
        for raw, path in self.raw:
            subprocess.run(['convert', str(raw), str(path)], check=True)
            raw.unlink()
        self.raw.clear()


class Puppet:
    """A second player driven through tools/bot/puppet.js; see Session.puppet."""

    def __init__(self, session, name, proc):
        self.session = session
        self.name = name
        self.proc = proc

    def send(self, line):
        """One puppet.js command: slot N, sneak on|off, use on|off, chat <text>."""
        self.proc.stdin.write((line + '\n').encode('utf-8'))
        self.proc.stdin.flush()
        time.sleep(0.3)

    def hold(self, item):
        self.send(f'slot {self.session.hotbar_slot(item, self.name)}')

    def tp(self, x, y, z, yaw=0):
        """Moves the puppet within its world and turns it to `yaw` (0 south, 90 west, 180 north)."""
        self.session.rcon(f'execute as {self.name} at @s run tp @s {x} {y} {z} {yaw} 0')
        time.sleep(0.5)

    def orbit(self, degrees, distance=3):
        """Moves the camera player around the puppet and turns it to look at it: 0 is in front of
        the puppet, 90 on its right, 180 behind it. The puppet itself never turns, because other
        clients swing a player's body after its head only slowly."""
        s = self.session
        side = -distance * math.sin(math.radians(degrees))
        ahead = distance * math.cos(math.radians(degrees))
        # Feet to feet: on level ground that is a level look, with the puppet's body mid-screen.
        s.rcon(f'execute as {s.name} at {self.name} run tp @s ^{side:.2f} ^ ^{ahead:.2f} facing entity {self.name} feet')
        time.sleep(1)


def load_scene(name):
    path = SCENES / f'{name}.py'
    if not path.exists():
        raise Fail(f'no scene {name} (see `shots.py scenes`)')
    namespace = {'__file__': str(path), '__name__': f'scene_{name}'}
    exec(compile(path.read_text(encoding='utf-8'), str(path), 'exec'), namespace)
    return namespace


def scene_list():
    out = []
    for path in sorted(SCENES.glob('*.py')):
        doc = (load_scene(path.stem).get('__doc__') or '').strip().splitlines()
        out.append((path.stem, doc[0] if doc else ''))
    return out


# ---------------------------------------------------------------------------------------- commands

def player_online(cfg):
    return cfg.name in rcon(cfg, 'list').split(':', 1)[-1].replace(',', ' ').split()


def restart_server(cfg, state):
    """Restarts the server shots started, so it loads a new plugin jar."""
    log('restarting the server for the new plugin')
    pid = state.pop('server_pid')
    state.pop('server_rcon_port', None)
    save_state(state)
    stop_server(cfg, pid, cfg.rcon_port)
    end = time.time() + 90
    while time.time() < end and (listener_pid(cfg.port) is not None or listener_pid(cfg.rcon_port) is not None):
        time.sleep(1)
    state.update(server_pid=start_server(cfg), server_rcon_port=cfg.rcon_port)
    save_state(state)


def wait_rcon(cfg, pid):
    """Waits for RCON on a server from --server-dir that is up or still starting."""
    end = time.time() + START_TIMEOUT
    while not rcon_alive(cfg):
        if not pid_alive(pid):
            raise Fail(f'the server in {cfg.server_dir} stopped; see {cfg.server_dir / "logs" / "latest.log"}')
        problems = property_problems(cfg)
        if problems:
            raise Fail(f'the server in {cfg.server_dir} (pid {pid}) does not answer RCON: {"; ".join(problems)}')
        if time.time() > end:
            raise Fail(f'the server in {cfg.server_dir} (pid {pid}) does not answer RCON on port {cfg.rcon_port}; '
                       'check enable-rcon, rcon.port and rcon.password in its server.properties')
        time.sleep(1)


def wait_offline(cfg, timeout=15):
    end = time.time() + timeout
    while time.time() < end and player_online(cfg):
        time.sleep(0.5)


def grant(cfg, name, op=True):
    """Lets `name` in on a whitelisted server and makes it op (chat commands such as /cv lobby).
    Returns what it changed, so `revoke` takes back exactly that and keeps what was set up by hand."""
    uid = offline_uuid(name)

    def listed(file):
        try:
            return any(entry.get('uuid') == uid for entry in json.loads((cfg.server_dir / file).read_text(encoding='utf-8')))
        except (OSError, ValueError, AttributeError):
            return False
    changed = []
    whitelist_on = read_properties(cfg.server_dir / 'server.properties').get('white-list') == 'true'
    if whitelist_on and not listed('whitelist.json'):
        rcon(cfg, f'whitelist add {name}')  # the exact case: the offline UUID is made from it
        changed.append('whitelist')
    if op and not listed('ops.json'):
        rcon(cfg, f'op {name}')
        changed.append('op')
    return changed


def revoke(cfg, name, changed, rcon_port=None):
    for what in changed:
        with contextlib.suppress(OSError, Fail):
            rcon(cfg, f'deop {name}' if what == 'op' else f'whitelist remove {name}', port=rcon_port)


def reset_profile(cfg):
    """Deletes the player's Craftov profile, so it joins as a new player with the starter kit."""
    path = cfg.server_dir / 'plugins' / 'Craftov' / 'data' / 'players' / f'{offline_uuid(cfg.name)}.json'
    wait_offline(cfg)
    time.sleep(2)  # the profile is saved asynchronously after a quit
    if path.exists():
        path.unlink()
        log('reset the profile of', cfg.name)


def up(cfg, state, started=None):
    """Starts whatever is not running yet. The parts it starts are added to `started` as it goes,
    so a caller can stop them even when a later step fails."""
    started = [] if started is None else started
    # Only a server this tool started (and that still runs) is ever stopped by it, and the state
    # file tracks one at a time.
    if not pid_alive(state.get('server_pid'), 'paper.jar'):
        state.pop('server_pid', None)
        state.pop('server_rcon_port', None)
    elif state.get('server_rcon_port') != cfg.rcon_port:
        raise Fail(f'shots already runs a server with RCON port {state.get("server_rcon_port")}; '
                   'stop it with `shots.py down` first')
    pid = check_server(cfg)
    fresh_server = False
    if pid is None:
        if not (cfg.server_dir / 'start.sh').exists():
            setup_server(cfg)
        if cfg.build:
            build_plugin(cfg)
        state.update(server_pid=start_server(cfg), server_rcon_port=cfg.rcon_port)
        save_state(state)
        started.append('server')
        fresh_server = True
    elif cfg.build:
        # Never stop a server someone else started: they may be using it.
        if state.get('server_pid') != pid:
            raise Fail(f'--build restarts the server, and the one in {cfg.server_dir} (pid {pid}) was not started by '
                       'shots.py; stop it yourself first, or run without --build')
        build_plugin(cfg)
        restart_server(cfg, state)
        fresh_server = True
    else:
        wait_rcon(cfg, pid)
    client_up = pid_alive(state.get('client_pid'), 'net.minecraft.client') and pid_alive(state.get('xvfb_pid'), 'Xvfb')
    # The client reads the window size and GUI scale only when it starts.
    same_view = state.get('size', cfg.size) == cfg.size and state.get('gui_scale', 0) == cfg.gui_scale
    if client_up and state.get('port') == cfg.port and same_view and not fresh_server and not cfg.fresh and player_online(cfg):
        log('reusing the client on', state['display'])
    else:
        down(cfg, state, ('client', 'display'))
        wait_offline(cfg)  # the old client's disconnect must land in the log before the offset below
        if cfg.fresh:
            reset_profile(cfg)
        state['display'], state['xvfb_pid'] = start_display(cfg.size)
        save_state(state)
        if not client_up:
            started.append('display')  # a client that was up before stays up afterwards
        state['client_grants'] = {'name': cfg.name, 'rcon_port': cfg.rcon_port, 'changed': grant(cfg, cfg.name)}
        save_state(state)
        log_offset = server_log_size(cfg)
        state['client_pid'] = start_client(cfg, state['display'])
        if not client_up:
            started.append('client')
        state.update(port=cfg.port, rcon_port=cfg.rcon_port, server_dir=str(cfg.server_dir), size=cfg.size,
                     gui_scale=cfg.gui_scale)
        save_state(state)
        wait_joined(cfg, state['client_pid'], log_offset)
        # Terrain, the HUD, and the "chat messages can't be verified" toast an offline server
        # shows for ten seconds after joining.
        time.sleep(12)
        state_now, detail = pack_state(client_log())
        if state_now != 'applied':
            raise Fail(f'the resource pack was dropped after loading ({detail or "vanilla reload"}); '
                       f'see {GAME_DIR / "logs" / "latest.log"}')
        # A profile that never answered the first-join briefing (a dialog window) gets it a few seconds
        # after every join: a new one, and any this harness joined with before, since it closes the
        # briefing unanswered. It would cover every shot and swallow the chat key, so it is closed
        # again; a scene that wants it opens it with /cv guide.
        rcon(cfg, f'cv close {cfg.name}')
    save_state(state)
    return started


def down(cfg, state, parts=('client', 'display', 'server')):
    if 'client' in parts and pid_alive(state.get('client_pid'), 'net.minecraft.client'):
        log('stopping the client')
        stop_pid(state.pop('client_pid'), 'net.minecraft.client')
    if 'client' in parts and state.get('client_grants'):
        # Op and whitelist only last as long as the client: the name is public and the server is
        # in offline mode, so anyone could log in as it.
        grants = state.pop('client_grants')
        revoke(cfg, grants['name'], grants['changed'], grants['rcon_port'])
    if 'display' in parts and pid_alive(state.get('xvfb_pid'), 'Xvfb'):
        stop_pid(state.pop('xvfb_pid'), 'Xvfb')
        state.pop('display', None)
    if 'server' in parts:
        pid = state.pop('server_pid', None)
        rcon_port = state.pop('server_rcon_port', None)
        if pid_alive(pid, 'paper.jar'):
            stop_server(cfg, pid, rcon_port or cfg.rcon_port)
    save_state(state)


def run_scenes(cfg, names):
    scenes = [(spec, load_scene(spec.partition(':')[0]), spec.partition(':')[2] or None) for spec in names]
    state = load_state()
    started = []
    session = None
    try:
        up(cfg, state, started)
        session = Session(cfg, state['display'])
        for spec, scene, arg in scenes:
            session.arg = arg
            log('scene', spec)
            try:
                scene['run'](session)
            finally:
                session.develop()
    finally:
        if cfg.keep:
            log('left running (stop with `shots.py down`):', ', '.join(started) or 'nothing new')
        else:
            down(cfg, state, started)
    if cfg.send and session and session.shots:
        send(session.shots)
    return session.shots if session else []


def send(names):
    """Copies the shots to ~/RemoteShare/shots on the owner's Mac and opens them there."""
    paths = [OUT / f'{Path(name).stem}.png' for name in names]
    missing = [str(p) for p in paths if not p.exists()]
    if missing:
        raise Fail('no screenshot ' + ', '.join(missing))
    subprocess.run(['ssh', 'mac', 'mkdir', 'shots'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for path in paths:
        name = path.stem
        with open(path, 'rb') as f:
            subprocess.run(['ssh', 'mac', 'put', f'shots/{name}.png'], stdin=f, check=True)
        subprocess.run(['ssh', 'mac', 'open', f'shots/{name}.png'], check=True)
        log('sent', name)


def status(cfg, state):
    try:
        pid = check_server(cfg)
        server = 'down' if pid is None else 'up' if rcon_alive(cfg) else f'running (pid {pid}), RCON not answering'
    except Fail as e:
        pid, server = None, f'not usable: {e}'
    ours = pid is not None and state.get('server_pid') == pid
    print('server   ', server, f'(port {cfg.port}, rcon {cfg.rcon_port}, {cfg.server_dir})',
          '[started by shots]' if ours else '')
    alive = pid_alive(state.get('client_pid'), 'net.minecraft.client')
    print('client   ', f'up on {state.get("display")} as {cfg.name}' if alive else 'down')
    shots = sorted(OUT.glob('*.png')) if OUT.exists() else []
    print('shots    ', ', '.join(p.stem for p in shots) or 'none', f'({OUT})' if shots else '')


def common_options(suppress):
    """Options accepted before and after the subcommand. The subcommand's copy has no defaults, so
    it only overrides what was actually given after it."""
    def default(value):
        return argparse.SUPPRESS if suppress else value
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument('--port', type=int, default=default(int(os.environ.get('SHOTS_PORT', 25565))))
    common.add_argument('--rcon-port', type=int, default=default(int(os.environ.get('SHOTS_RCON_PORT', 0))),
                        help='default: --port + 10')
    common.add_argument('--rcon-password', default=default(os.environ.get('SHOTS_RCON_PASSWORD', 'craftov-local')))
    common.add_argument('--server-dir', type=Path,
                        default=default(Path(os.environ.get('SHOTS_SERVER_DIR', ROOT / 'server' / 'run'))))
    common.add_argument('--name', default=default(os.environ.get('SHOTS_NAME', 'ShotCam')), help='offline user name of the client')
    common.add_argument('--size', default=default('1600x900'), help='screen and window size')
    common.add_argument('--gui-scale', type=int, default=default(0),
                        help='client GUI scale (0 = auto, the largest that fits; 1600x900 gives 3)')
    common.add_argument('--lang', default=default('zh_cn'), help='client language')
    common.add_argument('--java', default=default(shutil.which('java') or '/usr/bin/java'))
    return common


def parse(argv):
    common = common_options(suppress=True)
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0], parents=[common_options(suppress=False)],
                                     formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__.split('\n\n', 1)[1])
    sub = parser.add_subparsers(dest='command', required=True)
    run = sub.add_parser('run', parents=[common], help='run scenes and take screenshots')
    run.add_argument('scenes', nargs='*', help=f'scene names, optionally scene:arg (default: {" ".join(DEFAULT_SCENES)})')
    run.add_argument('--keep', action='store_true', help='leave the server and client running afterwards')
    run.add_argument('--build', action='store_true', help='rebuild the plugin and (re)start the server first')
    run.add_argument('--send', action='store_true', help="copy this run's shots to the owner's Mac and open them")
    run.add_argument('--fresh', action='store_true', help='reset the player to a new profile with the starter kit')
    upp = sub.add_parser('up', parents=[common], help='start the server and client and leave them running')
    upp.add_argument('--build', action='store_true', help='rebuild the plugin and (re)start the server first')
    upp.add_argument('--fresh', action='store_true', help='reset the player to a new profile with the starter kit')
    sub.add_parser('down', parents=[common], help='stop what up / run --keep started')
    sub.add_parser('status', parents=[common], help='show what is running')
    sub.add_parser('scenes', parents=[common], help='list scenes')
    sub.add_parser('setup', parents=[common], help='(re)create the test server in --server-dir')
    snd = sub.add_parser('send', parents=[common], help="copy shots to the owner's Mac and open them")
    snd.add_argument('shots', nargs='+')
    cfg = parser.parse_args(argv)
    cfg.server_dir = cfg.server_dir.resolve()
    cfg.rcon_port = cfg.rcon_port or cfg.port + 10
    for flag in ('keep', 'build', 'send', 'fresh'):
        setattr(cfg, flag, getattr(cfg, flag, False))
    return cfg


def main(argv):
    cfg = parse(argv)
    try:
        if cfg.command == 'scenes':
            for name, doc in scene_list():
                print(f'{name:12} {doc}')
        elif cfg.command == 'status':
            status(cfg, load_state())
        elif cfg.command == 'send':
            send(cfg.shots)
        elif cfg.command == 'setup':
            setup_server(cfg)
        elif cfg.command == 'down':
            down(cfg, load_state())
        else:
            for tool in ('Xvfb', 'xdotool', 'import'):
                if not shutil.which(tool):
                    raise Fail(f'{tool} is not installed')
            ensure_venv()
            if cfg.command == 'up':
                state, started = load_state(), []
                try:
                    up(cfg, state, started)
                except BaseException:
                    down(cfg, state, started)  # a half-started setup is no use to keep
                    raise
            else:
                shots = run_scenes(cfg, cfg.scenes or DEFAULT_SCENES)
                log('done:', ', '.join(shots) or 'no shots', f'in {OUT}')
    except (Fail, OSError, subprocess.CalledProcessError) as e:
        log('FAILED:', e)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
