"""Checks the generated BetterHud HUD against the vanilla HUD on common screens (#6).

For 16:9 and 21:9 screens at GUI scale 2, 3 and 4 (where the client allows that scale), every
always-on HUD layout must stay on screen and off the hotbar, the offhand slot and the chat area (the
chat's bottom line sits 40 GUI px above the screen edge). Camera overlays that cover the whole screen
(hit, blood, suppression) must leave the middle of the screen fully transparent.

Usage: python3 tools/art/hudcheck.py   (after build.py; exit code 1 on any problem)
"""
import os
import sys

import yaml
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, '..', '..', 'src', 'main', 'resources')
BH = os.path.join(RES, 'betterhud')
MISC = os.path.join(RES, 'pack', 'craftov', 'resourcepack', 'assets', 'craftov', 'textures', 'misc')

SCREENS = [(1280, 720), (1366, 768), (1600, 900), (1920, 1080), (2560, 1440), (2560, 1080), (3440, 1440)]
SCALES = (2, 3, 4)
# Text-only layouts have no image to measure: the widest text each is allowed (x0, y0, x1, y1).
TEXT_BOXES = {
    **{'craftov_feed_%d' % i: (-170, 0, 0, 12) for i in (1, 2, 3, 4, 5)},
    'craftov_prompt': (0, 0, 200, 12),
}
# Shown in raids all the time (the centre ones come and go with an action and may cover the world).
ALWAYS = ['craftov_vitals', 'craftov_weapon', 'craftov_skill', 'craftov_info', 'craftov_mate_1',
          'craftov_mate_2', 'craftov_feed_1', 'craftov_feed_2', 'craftov_feed_3', 'craftov_feed_4', 'craftov_feed_5',
          'craftov_alert', 'craftov_objective', 'craftov_objective_raid_0', 'craftov_objective_raid_1',
          'craftov_objective_raid_2']


def load(name):
    with open(os.path.join(BH, name), encoding='utf-8') as f:
        return yaml.safe_load(f)


def layout_box(layout_name, layouts, images):
    if layout_name in TEXT_BOXES:
        return TEXT_BOXES[layout_name]
    box = None
    for img in (layouts[layout_name].get('images') or {}).values():
        spec = images[img['name']]
        w, h = Image.open(os.path.join(BH, 'assets', spec['file'])).size
        if spec.get('type') == 'listener':
            w //= 1  # a listener image is the full bar, cut by value
        b = (img['x'], img['y'], img['x'] + w, img['y'] + h)
        box = b if box is None else (min(box[0], b[0]), min(box[1], b[1]), max(box[2], b[2]), max(box[3], b[3]))
    return box


def overlaps(a, b):
    return a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]


def main():
    layouts = load('layouts/craftov.yml')
    images = load('images/craftov.yml')
    huds = load('huds/craftov.yml')['craftov_hud']['layouts'].values()
    problems = []
    checked = 0
    for sw, sh in SCREENS:
        for scale in SCALES:
            if sw // scale < 320 or sh // scale < 240:
                continue
            w, h = -(-sw // scale), -(-sh // scale)
            cx = w // 2
            hotbar = (cx - 91, h - 22, cx + 91, h)
            offhand = (cx - 91 - 29, h - 23, cx - 91, h)
            chat = (0, h - 40 - 9 * 10, 328, h - 40)
            for place in huds:
                name = place['name']
                if name not in ALWAYS:
                    continue
                box = layout_box(name, layouts, images)
                ox = w * place['gui']['x'] / 100 + place['pixel']['x']
                oy = h * place['gui']['y'] / 100 + place['pixel']['y']
                b = (ox + box[0], oy + box[1], ox + box[2], oy + box[3])
                checked += 1
                where = '%dx%d at GUI %d (%dx%d)' % (sw, sh, scale, w, h)
                if b[0] < 0 or b[1] < 0 or b[2] > w or b[3] > h:
                    problems.append('%s leaves the screen on %s: %s' % (name, where, b))
                for what, rect in (('hotbar', hotbar), ('offhand slot', offhand), ('chat', chat)):
                    if overlaps(b, rect):
                        problems.append('%s overlaps the %s on %s: %s vs %s' % (name, what, where, b, rect))
    for overlay in ('hit.png', 'blood_1.png', 'blood_2.png', 'blood_3.png', 'suppressed.png'):
        img = Image.open(os.path.join(MISC, overlay)).convert('RGBA')
        w, h = img.size
        centre = img.crop((w * 3 // 8, h * 3 // 8, w * 5 // 8, h * 5 // 8))
        if centre.getchannel('A').getextrema()[1] > 0:
            problems.append('%s is not transparent in the middle of the screen' % overlay)
    for p in problems:
        print('PROBLEM', p)
    print('checked %d placements and 5 overlays: %s' % (checked, 'all clear' if not problems else '%d problems' % len(problems)))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
