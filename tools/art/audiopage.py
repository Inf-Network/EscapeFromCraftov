"""A listening page: every gun's reports before and after a change to the sound build, side by side.

Usage: python3 tools/art/audiopage.py <sounds folder before> <sounds folder after> <page.html>
  The folders are craftov/sounds of two versions of the pack (one extracted from git, say:
  git archive -o before.tar <commit> -- src/main/resources/pack/craftov/resourcepack/assets/craftov/sounds).

The page holds the sounds as MP3 data (any browser plays MP3; Safari does not play Ogg Vorbis), so
it works as a single file; the MP3s are also written to a folder named after the page, next to it.
Besides the files themselves it mixes three things a player hears in game, from GunAudio.java's
rules: the shooter's own P18, M4C and AWR at their volumes; an M4C heard from 8 to 130 blocks
away; and the M4C behind a wall.
"""
import base64
import html
import io
import os
import sys

import numpy as np

SR = 44100
GUNS = [
    ('p18', 'P18 手枪'), ('d50', 'D50 大口径手枪'), ('sv9', 'SV9 冲锋枪'), ('v57', 'V57 冲锋枪'),
    ('k47', 'K47 突击步枪'), ('m4c', 'M4C 卡宾枪'), ('scr', 'SCR 战斗步枪'), ('kl7', 'KL7 轻机枪'),
    ('r12', 'R12 霰弹枪'), ('dm14', 'DM14 精确射手步枪'), ('awr', 'AWR 栓动狙击步枪'),
]
BANDS = [
    ('fp', '开枪的人', '自己开枪听到的立体声'),
    ('near', '近处', '26 格以内的其他人'),
    ('mid', '中距离', '约 30–90 格'),
    ('far', '远处', '90 格以外'),
    ('muff', '隔墙', '中间隔着方块'),
    ('near_s', '消音 · 近', '装了消音器'),
    ('mid_s', '消音 · 中', '装了消音器，25 格以外（新增）'),
]
RECORDED = {
    'p18': 'Walther PPQ 9mm', 'd50': 'Colt 1911 .45（降调 6%）', 'sv9': 'Carl Gustav M45 9mm', 'v57': 'PPSh-41 7.62×25',
    'k47': 'AK-47 7.62×39', 'm4c': 'AR-15 5.56', 'scr': 'Mosin-Nagant 7.62×54R', 'kl7': 'AK-47 连射的最后一发（降调 3%）',
    'r12': 'Benelli Nova 与 Winchester Model 12 霰弹枪', 'dm14': 'Tikka T3 .30-06', 'awr': 'Springfield M1917 .30-06（降调 5%）',
}


def read(path):
    import soundfile
    from scipy import signal
    x, sr = soundfile.read(path, dtype='float64', always_2d=True)
    if sr != SR:
        x = signal.resample_poly(x, SR, sr, axis=0)
    return x


def mono(x):
    return x.mean(axis=1)


def mp3(x):
    import soundfile
    buf = io.BytesIO()
    peak = np.max(np.abs(x)) if len(x) else 0
    if peak > .98:
        x = x / peak * .98
    soundfile.write(buf, x.astype(np.float32), SR, format='MP3', subtype='MPEG_LAYER_III')
    return buf.getvalue()


def place(out, x, at, gain):
    a = int(at * SR)
    n = min(len(x), len(out) - a)
    if n > 0:
        out[a:a + n] += x[:n] * gain


# ---------------------------------------------------------------- GunAudio.java's rules, for the mixes

def shot_gain(d, volume):
    """GunAudio.gain for an open report, as the client finally plays it (placed 4 blocks away)."""
    loudness = volume / 5.0
    g = min(1.2, loudness) / (1 + (d / max(4, 30 * loudness)) ** 1.5)
    return min(1.0, g / .75)


def weights(d):
    ramp = lambda a, b: min(1.0, max(0.0, (d - a) / (b - a)))
    to_mid, to_far = ramp(20, 32), ramp(70, 110)
    return {'near': 1 - to_mid, 'mid': to_mid * (1 - to_far), 'far': to_mid * to_far}


def walk(folder, new):
    """An M4C heard from 8 to 130 blocks: one shot per distance, 0.9 s apart."""
    distances = [8, 16, 22, 26, 30, 40, 60, 75, 90, 100, 110, 130]
    out = np.zeros(int(SR * (0.9 * len(distances) + 2.5)))
    for k, d in enumerate(distances):
        g = shot_gain(d, 5.0)
        if new:
            for band, w in weights(d).items():
                if w > 0:
                    place(out, mono(read(os.path.join(folder, 'gun', 'm4c', '%s_%d.ogg' % (band, k % 2)))), .9 * k, g * np.sqrt(w))
        else:
            band = 'near' if d < 26 else 'mid' if d < 90 else 'far'
            place(out, mono(read(os.path.join(folder, 'gun', 'm4c', '%s_%d.ogg' % (band, k % 2)))), .9 * k, g)
    return out, distances


def own_guns(folder, new):
    """The shooter's own P18, M4C and AWR, one after another."""
    out = np.zeros((int(SR * 6.5), 2))
    for k, (gid, volume) in enumerate((('p18', 3.5), ('m4c', 5.0), ('awr', 7.0))):
        level = min(1.0, .55 + (volume - 3.5) / 3.5 * .45) if new else 1.0
        x = read(os.path.join(folder, 'gun', gid, 'fp_0.ogg'))
        place(out, x, 2.0 * k, level)
    return out


def wall(folder, new):
    """The M4C 12 blocks away behind a wall."""
    g = shot_gain(12, 5.0)
    out = np.zeros(int(SR * 3.5))
    for k in range(2):
        place(out, mono(read(os.path.join(folder, 'gun', 'm4c', 'muff_%d.ogg' % k))), 1.6 * k, g * (.75 if new else .8))
        if new:
            place(out, mono(read(os.path.join(folder, 'gun', 'm4c', 'near_%d.ogg' % k))), 1.6 * k, g * .2)
    return out


# ---------------------------------------------------------------- page

CSS = """
:root{--bg:#f6f5f2;--card:#fff;--ink:#1d1f22;--muted:#6b7077;--line:#e3e1dc;--accent:#b8860b;--new:#2f7d4f;--old:#8a8f96}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#15171a;--card:#1e2125;--ink:#e9e7e2;--muted:#9aa0a6;--line:#2e3237;--accent:#f2c94c;--new:#6fcf97;--old:#8a8f96}}
:root[data-theme="dark"]{--bg:#15171a;--card:#1e2125;--ink:#e9e7e2;--muted:#9aa0a6;--line:#2e3237;--accent:#f2c94c;--new:#6fcf97;--old:#8a8f96}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.6 -apple-system,BlinkMacSystemFont,"PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif}
main{max-width:1040px;margin:0 auto;padding:28px 16px 64px}
h1{font-size:26px;margin:0 0 6px}
h2{font-size:19px;margin:36px 0 4px}
p,li{color:var(--muted)}
.lead{color:var(--ink)}
section.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:16px 18px;margin:14px 0}
.gunhead{display:flex;flex-wrap:wrap;align-items:baseline;gap:4px 12px;margin-bottom:8px}
.gunhead h3{margin:0;font-size:17px}
.gunhead span{color:var(--muted);font-size:13px}
table{width:100%;border-collapse:collapse}
th,td{padding:7px 6px;border-top:1px solid var(--line);vertical-align:middle;text-align:left}
th{font-weight:600;font-size:13px;color:var(--muted)}
td.band{width:24%}
td.band b{display:block;font-weight:600}
td.band small{color:var(--muted);font-size:12px}
.tag{font-size:12px;font-weight:600;letter-spacing:.02em}
.tag.old{color:var(--old)}.tag.new{color:var(--new)}
audio{width:100%;height:36px}
.none{color:var(--muted);font-size:13px}
.checks li{margin:4px 0}
nav{display:flex;flex-wrap:wrap;gap:6px 10px;margin:12px 0 0}
nav a{color:var(--accent);text-decoration:none;font-size:14px}
@media (max-width:640px){td.band{width:auto}table,tbody,tr,td{display:block}thead{display:none}td{border-top:none;padding:4px 0}tr{border-top:1px solid var(--line);padding:8px 0}}
"""


def audio_tag(blob):
    if blob is None:
        return '<span class="none">（没有这一档）</span>'
    return '<audio controls preload="none" src="data:audio/mpeg;base64,%s"></audio>' % base64.b64encode(blob).decode()


def row(title, note, before, after):
    return ('<tr><td class="band"><b>%s</b><small>%s</small></td><td><span class="tag old">之前</span>%s</td>'
            '<td><span class="tag new">之后</span>%s</td></tr>' % (html.escape(title), html.escape(note), audio_tag(before), audio_tag(after)))


def build(before_dir, after_dir, page):
    side = os.path.splitext(page)[0]
    os.makedirs(side, exist_ok=True)

    def keep(name, blob):
        if blob is not None:
            with open(os.path.join(side, name + '.mp3'), 'wb') as f:
                f.write(blob)
        return blob

    def clip(folder, gid, band, tag):
        path = os.path.join(folder, 'gun', gid, band + '_0.ogg')
        return keep('%s_%s_%s' % (gid, band, tag), mp3(read(path))) if os.path.exists(path) else None

    parts = []
    nav = ''.join('<a href="#%s">%s</a>' % (gid, html.escape(name.split()[0])) for gid, name in GUNS)
    parts.append('<h2>在游戏里会听到的样子</h2><p>按 GunAudio.java 的规则把文件混在一起，模拟玩家实际听到的。</p>')
    mixes = [
        ('开枪的人：P18 → M4C → AWR', '同一个人依次开三把枪。之前三把一样响；之后手枪明显最小、狙击枪最大',
         own_guns(before_dir, False), own_guns(after_dir, True), 'own'),
        ('M4C 从 8 格走到 130 格', '每 0.9 秒远一档：8、16、22、26、30、40、60、75、90、100、110、130 格。之前在 26 格和 90 格突然换音色；之后在 20–32、70–110 格之间两档同时播、此消彼长',
         walk(before_dir, False)[0], walk(after_dir, True)[0], 'walk'),
        ('M4C 在 12 格外、隔着墙', '之前只剩低沉的闷响；之后是闷声加上 0.2 倍原声，还听得出是枪声',
         wall(before_dir, False), wall(after_dir, True), 'wall'),
    ]
    rows = ''.join(row(t, n, keep('mix_%s_before' % k, mp3(b)), keep('mix_%s_after' % k, mp3(a))) for t, n, b, a, k in mixes)
    parts.append('<section class="card"><table><thead><tr><th></th><th>之前（合成）</th><th>之后（实录）</th></tr></thead><tbody>%s</tbody></table></section>' % rows)

    parts.append('<h2>每把枪的每一档</h2><p>每格是该档的第一个版本（游戏里每档有 2–4 个版本轮流播）。</p><nav>%s</nav>' % nav)
    for gid, name in GUNS:
        rows = ''.join(row(title, note, clip(before_dir, gid, band, 'before'), clip(after_dir, gid, band, 'after'))
                       for band, title, note in BANDS)
        auto = ''
        for n in (2, 3):
            if os.path.exists(os.path.join(after_dir, 'gun', gid, 'x%d_near_0.ogg' % n)):
                auto = row('连射 · 近处', '%d 发一段，按真实射速排好' % n, clip(before_dir, gid, 'x%d_near' % n, 'before'),
                           clip(after_dir, gid, 'x%d_near' % n, 'after'))
        parts.append('<section class="card" id="%s"><div class="gunhead"><h3>%s</h3><span>录音：%s</span></div>'
                     '<table><thead><tr><th>档位</th><th>之前（合成）</th><th>之后（实录）</th></tr></thead><tbody>%s%s</tbody></table></section>'
                     % (gid, html.escape(name), html.escape(RECORDED.get(gid, '')), rows, auto))

    doc = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>枪声对比</title><style>%s</style></head><body><main>
<h1>枪声对比：合成 → 实录</h1>
<p class="lead">左边是现在服务器上程序合成的枪声，右边是换成 CC0 实录（The Free Firearm Sound Library，消音版来自 Freesound）之后的。建议戴耳机听，音量调到平时玩的大小。</p>
<p>进游戏要确认的：</p>
<ul class="checks">
<li>每把枪都像它自己，手枪听起来明显比狙击枪小。</li>
<li>单发后不再冒出第二声“呜”；在室内连射时有房间的感觉。</li>
<li>M4C 800 发/分连射节奏均匀。</li>
<li>从 10 格走到 120 格，音色平滑过渡；远处和隔墙的声音还听得出是枪声。</li>
<li>能接受资源包变大（枪声从 2.8 MB 到 4.5 MB），进服后第一枪没有延迟。</li>
</ul>
%s
</main></body></html>
""" % (CSS, '\n'.join(parts))
    with open(page, 'w', encoding='utf-8') as f:
        f.write(doc)
    return page


if __name__ == '__main__':
    if len(sys.argv) != 4:
        print(__doc__)
        sys.exit(2)
    out = build(sys.argv[1], sys.argv[2], sys.argv[3])
    print('%s (%.1f MB)' % (out, os.path.getsize(out) / 1e6))
