#!/usr/bin/env bash
# Craftov one-shot server setup: downloads Paper 1.21.11, CraftEngine and BetterHud, installs Craftov
# and writes a ready-to-play configuration. Players only need to type the server IP; the resource
# pack is hosted on the Minecraft port by CraftEngine and downloaded automatically on join.
#
#   ./server/setup.sh            # production: online mode, public IP auto-detected for the pack URL
#   ./server/setup.sh --local    # local testing: offline mode, pack served from 127.0.0.1
#   ./server/setup.sh --ip 1.2.3.4   # force the address advertised for the resource pack
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
RUN="${RUN_DIR:-$HERE/run}"
CACHE="$HERE/cache"
MC_VERSION="1.21.11"
CRAFTENGINE_VERSION="26.9.2"
BETTERHUD_VERSION="2.2.0-SNAPSHOT-449"
LOCAL=0
PACK_IP="auto"
PORT=25565

while [[ $# -gt 0 ]]; do
  case "$1" in
    --local) LOCAL=1; PACK_IP="127.0.0.1"; shift ;;
    --ip) PACK_IP="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    *) echo "unknown option $1"; exit 1 ;;
  esac
done

mkdir -p "$RUN/plugins" "$CACHE"

fetch() { # url dest
  if [[ ! -s "$2" ]]; then
    echo "  downloading $(basename "$2")"
    curl -fsSL --retry 3 -o "$2.part" "$1"
    mv "$2.part" "$2"
  fi
}

modrinth_url() { # project version loader
  curl -fsSL "https://api.modrinth.com/v2/project/$1/version?game_versions=%5B%22$MC_VERSION%22%5D" | python3 -c "
import json,sys
want, loader = sys.argv[1], sys.argv[2]
for v in json.load(sys.stdin):
    if v['version_number'] == want and loader in v['loaders']:
        for f in v['files']:
            if f['primary'] or 'javadoc' not in f['filename']:
                print(f['url']); sys.exit(0)
sys.exit(1)" "$2" "$3"
}

echo "[1/4] Paper $MC_VERSION"
PAPER_URL=$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/$MC_VERSION/builds/latest" | python3 -c "import json,sys; print(json.load(sys.stdin)['downloads']['server:default']['url'])")
fetch "$PAPER_URL" "$CACHE/paper-$MC_VERSION.jar"
cp "$CACHE/paper-$MC_VERSION.jar" "$RUN/paper.jar"

echo "[2/4] CraftEngine $CRAFTENGINE_VERSION + BetterHud $BETTERHUD_VERSION"
fetch "$(modrinth_url craftengine "$CRAFTENGINE_VERSION" paper)" "$CACHE/craftengine-$CRAFTENGINE_VERSION.jar"
fetch "$(modrinth_url betterhud2 "$BETTERHUD_VERSION" paper)" "$CACHE/betterhud-$BETTERHUD_VERSION.jar"
cp "$CACHE/craftengine-$CRAFTENGINE_VERSION.jar" "$RUN/plugins/CraftEngine.jar"
cp "$CACHE/betterhud-$BETTERHUD_VERSION.jar" "$RUN/plugins/BetterHud.jar"

echo "[3/4] Craftov"
JAR=$(ls -t "$ROOT"/build/libs/Craftov-*.jar 2>/dev/null | head -1 || true)
if [[ -z "$JAR" ]]; then
  (cd "$ROOT" && ./gradlew jar -q)
  JAR=$(ls -t "$ROOT"/build/libs/Craftov-*.jar | head -1)
fi
cp "$JAR" "$RUN/plugins/Craftov.jar"

echo "[4/4] configuration"
echo "eula=true" > "$RUN/eula.txt"
if [[ ! -f "$RUN/server.properties" ]]; then
  cat > "$RUN/server.properties" <<PROPS
server-port=$PORT
online-mode=$([[ $LOCAL == 1 ]] && echo false || echo true)
motd=§6Craftov §7· §f搜打撤射击
difficulty=hard
pvp=true
spawn-protection=0
allow-flight=true
view-distance=10
simulation-distance=8
max-players=60
enforce-secure-profile=false
spawn-monsters=false
generate-structures=false
level-type=minecraft\:flat
generator-settings={"layers"\:[{"block"\:"minecraft\:air","height"\:1}],"biome"\:"minecraft\:plains"}
PROPS
fi
# CraftEngine: host the pack on the Minecraft port and push it on join.
CE_DIR="$RUN/plugins/CraftEngine"
mkdir -p "$CE_DIR"
if [[ ! -f "$CE_DIR/config.yml" ]]; then
  unzip -p "$RUN/plugins/CraftEngine.jar" config.yml > "$CE_DIR/config.yml"
fi
python3 - "$CE_DIR/config.yml" "$PACK_IP" "$LOCAL" <<'PY'
import re, sys
path, ip, local = sys.argv[1], sys.argv[2], sys.argv[3] == '1'
s = open(path, encoding='utf-8').read()
s = re.sub(r'(\n  self-host:\n    ip: )"[^"]*"', r'\g<1>"%s"' % ip, s)
s = re.sub(r'(\n    prompt: )"[^"]*"', r'\1"<gold>Craftov 需要加载专用资源包（武器、物资、界面与音效）</gold>"', s)
s = s.replace('forced-locale: \'\'', "forced-locale: 'zh_cn'")
if local:
    s = s.replace('strict-player-uuid-validation: true', 'strict-player-uuid-validation: false')
open(path, 'w', encoding='utf-8').write(s)
PY

cat > "$RUN/start.sh" <<'START'
#!/usr/bin/env bash
cd "$(dirname "$0")"
JAVA="${JAVA:-java}"
exec "$JAVA" -Xms2G -Xmx${MEMORY:-4G} -XX:+UseG1GC -jar paper.jar --nogui
START
chmod +x "$RUN/start.sh"
cat > "$RUN/start.bat" <<'START'
@echo off
cd /d %~dp0
java -Xms2G -Xmx4G -XX:+UseG1GC -jar paper.jar --nogui
pause
START

echo
echo "Done. Start the server with: $RUN/start.sh   (needs Java 25 because of BetterHud)"
echo "Players connect to <your-ip>:$PORT and accept the resource pack prompt."
