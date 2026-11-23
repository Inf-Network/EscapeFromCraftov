"""A published map whose template world has someone in it (issue #44): the deploy menu marks it. No arg.

A throwaway map file (shot_maint, "演示地图") is written unpublished, the camera player loads its world
with /cv editor world and a puppet is put there; back in the lobby the file is published by hand and
reloaded. maint-lore: the deploy menu with the pointer on 演示地图, its tooltip titled
"演示地图 [维护中]" (the tag in red) with the red line "维护中 · 地图正在编辑，暂时不能匹配". The map
file is removed afterwards (the empty world folder stays).

The window is 1600x900 at GUI scale 3 (shots.py's default size), which the slot position assumes.
"""

MAP = 'shot_maint'
WORLD = 'shot_maint_world'
SCALE = 3
# Map buttons start at slot 10 in registry (file name) order: proving, rustport, then this one.
SLOT = 12


def slot(k, rows=5):
    """Screen pixel at the centre of slot k of a chest menu with this many rows (176 wide, centred)."""
    left, top = (533 - 176) // 2, (300 - (114 + 18 * rows)) // 2
    return (left + 8 + 18 * (k % 9) + 8) * SCALE, (top + 18 + 18 * (k // 9) + 8) * SCALE


def definition(enabled):
    spawns = '\n'.join(f'- {{group: {g}, x: {g * 4}.5, y: 65, z: 0.5}}' for g in range(1, 5))
    containers = '\n'.join(f'- {{type: crate, x: {i}.5, y: 65, z: 6.5}}' for i in range(5))
    return f"""id: {MAP}
name: 演示地图
description: 截图用的临时地图
template: {WORLD}
enabled: {'true' if enabled else 'false'}
spawns:
{spawns}
extracts:
- {{id: out, name: 出口, type: ALWAYS, x: 0.5, y: 65, z: 10.5}}
containers:
{containers}
"""


def run(s):
    path = s.cfg.server_dir / 'plugins' / 'Craftov' / 'maps' / f'{MAP}.yml'
    path.write_text(definition(False), encoding='utf-8')
    s.rcon('cv reload')
    try:
        # Unpublished, so /cv editor world loads it; it stays loaded once the camera leaves.
        s.cmd(f'cv editor world {WORLD}')
        s.wait_until(lambda: s.dimension().endswith(WORLD), 30, 'the build world')
        with s.puppet() as mate:
            s.rcon(f'execute in minecraft:{WORLD} run tp {mate.name} 0.5 65 0.5')
            s.lobby()
            path.write_text(definition(True), encoding='utf-8')
            s.rcon('cv reload')
            s.clear_chat()
            s.cmd('cv deploy')
            s.wait(1.5)
            try:
                s.mouse_to(*slot(SLOT))
                s.wait(0.8)
                s.shot('maint-lore')
            finally:
                s.key('Escape')
    finally:
        path.unlink(missing_ok=True)
        s.rcon('cv reload')
