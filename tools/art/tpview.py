"""Offline third-person preview: a player model in one of the stances other players see (StanceService),
holding a gun's third-person model, with the arm angles the 1.21.11 client gives each pose
(HumanoidModel.poseRightArm, AnimationUtils.animateCrossbowCharge / animateCrossbowHold) and the hand
chain of ItemInHandLayer. Lets the low carry and the shouldered gun be tuned without a second client.

    python3 tools/art/tpview.py k47 sheet out.png           # every stance, front three-quarter and side
    python3 tools/art/tpview.py k47 low out.png [angle]      # one stance from one side (0 = in front)
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fpview  # noqa: E402

W, H = 640, 640
FOV = 40.0
SKIN = (205, 160, 120)
SHIRT = (64, 96, 70)
TROUSERS = (58, 62, 70)

# Arm rotations (x, y radians, the client's ModelPart xRot / yRot) of a right-handed player looking level.
POSES = {
    # Crossbow charge at the start of the use: the low carry (StanceService restarts the use so it stays there).
    'low': {'right': (-0.97079635, -0.8), 'left': (-0.97079635, 0.4)},
    # Crossbow hold: aimed along the look.
    'shoulder': {'right': (-math.pi / 2 + 0.1, -0.3), 'left': (-1.5, 0.6)},
    # The same use run on for 25 ticks (a reload): the support arm comes up level, turned in.
    'reload': {'right': (-0.97079635, -0.8), 'left': (-math.pi / 2, 0.85)},
}
# Which model variant each stance shows (build.py: the low carry on custom_model_data flag 0, the
# shouldered gun on the crossbow's charge).
VARIANT = {'low': 'gun_lowready', 'shoulder': 'gun_aimed', 'reload': 'gun_lowready'}


def rot_zyx(x, y, z=0.0):
    return fpview.rot_zyx(z, y, x)


def box_element(lo, hi, colour):
    return {'from': list(lo), 'to': list(hi), 'colour': colour,
            'faces': {f: {} for f in ('north', 'south', 'east', 'west', 'up', 'down')}}


def body_parts(pose, crouch=False):
    """[(matrix, element)] of the player model in model space (pixels, +Y down, -Z forward)."""
    parts = []
    dy = 3.2 if crouch else 0.0
    body_rot = fpview.rot('x', math.degrees(0.5)) if crouch else fpview.translate(0, 0, 0)
    head = fpview.translate(0, dy + (1.0 if crouch else 0.0), 0)
    parts.append((head, box_element((-4, -8, -4), (4, 0, 4), SKIN)))
    parts.append((fpview.mat_mul(fpview.translate(0, dy, 0), body_rot), box_element((-4, 0, -2), (4, 12, 2), SHIRT)))
    for side, pivot, box in (('right', (-5, 2, 0), ((-3, -2, -2), (1, 10, 2))), ('left', (5, 2, 0), ((-1, -2, -2), (3, 10, 2)))):
        ax, ay = POSES[pose][side]
        if crouch:
            ax += 0.4
        m = fpview.mat_mul(fpview.translate(pivot[0], pivot[1] + dy, pivot[2]), rot_zyx(ax, ay))
        parts.append((m, box_element(box[0], box[1], SKIN)))
        sleeve = (box[0], (box[1][0], 4, box[1][2]))
        parts.append((m, box_element((sleeve[0][0] - 0.05, sleeve[0][1] - 0.05, sleeve[0][2] - 0.05),
                                     (sleeve[1][0] + 0.05, sleeve[1][1], sleeve[1][2] + 0.05), SHIRT)))
    for x in (-1.9, 1.9):
        legs = fpview.translate(x, 12, 4 if crouch else 0)
        parts.append((legs, box_element((-2, 0, -2), (2, 12, 2), TROUSERS)))
    return parts


def item_matrix(pose, display, crouch=False):
    """Model-space pose of the held item (blocks; the model's units are 1/16): the arm, then
    ItemInHandLayer's -90 X, 180 Y and (1/16, 2/16, -10/16), then the display transform and -0.5 centring."""
    ax, ay = POSES[pose]['right']
    if crouch:
        ax += 0.4
    m = fpview.translate(-5 / 16.0, (2 + (3.2 if crouch else 0)) / 16.0, 0)
    m = fpview.mat_mul(m, rot_zyx(ax, ay))
    m = fpview.mat_mul(m, fpview.rot('x', -90))
    m = fpview.mat_mul(m, fpview.rot('y', 180))
    m = fpview.mat_mul(m, fpview.translate(1 / 16.0, 0.125, -0.625))
    t = [v / 16.0 for v in display.get('translation', [0, 0, 0])]
    r = display.get('rotation', [0, 0, 0])
    m = fpview.mat_mul(m, fpview.translate(*t))
    m = fpview.mat_mul(m, fpview.mat_mul(fpview.rot('x', r[0]), fpview.mat_mul(fpview.rot('y', r[1]), fpview.rot('z', r[2]))))
    m = fpview.mat_mul(m, fpview.scale(*display.get('scale', [1, 1, 1])))
    return fpview.mat_mul(m, fpview.translate(-0.5, -0.5, -0.5))


def camera(angle, height=1.3, distance=3.4):
    """World -> view matrix for a camera `angle` degrees round the player (0 in front, 90 on its right),
    looking at the waist. World: blocks, +Y up, the player at the origin facing -Z, its right towards +X."""
    a = math.radians(angle)
    eye = np.array([distance * math.sin(a), height, -distance * math.cos(a)])
    target = np.array([0.0, 0.95, 0.0])
    f = target - eye
    f /= np.linalg.norm(f)
    r = np.cross(f, [0, 1, 0])
    r /= np.linalg.norm(r)
    u = np.cross(r, f)
    view = np.identity(4)
    view[0, :3], view[1, :3], view[2, :3] = r, u, -f
    view[:3, 3] = -view[:3, :3] @ eye
    return view


def to_world(p):
    """Entity model space (blocks, +Y down) to world (+Y up, feet at 0): the renderer's scale(-1, -1, 1)
    and its 1.501 lift."""
    return np.array([-p[0], 1.501 - p[1], p[2]])


def project(v):
    x, y, z = v
    if z > -0.05:
        return None
    f = 1.0 / math.tan(math.radians(FOV) / 2)
    return ((f * x / -z + 1) / 2 * W, (1 - f * y / -z) / 2 * H)


def polys_of(elements, matrix, view, textures=None, model=None, units=16.0):
    out = []
    for element in elements:
        for name, pts, face in fpview.faces_of(element):
            if units != 16.0:
                pts = [tuple(c * 16.0 / units for c in p) for p in pts]
            world = [to_world(fpview.apply(matrix, p)) for p in pts]
            cam = [tuple((view @ np.append(w, 1.0))[:3]) for w in world]
            if any(c[2] > -0.05 for c in cam):
                continue
            screen = [project(c) for c in cam]
            shade = fpview.SHADE[fpview.lit_face(element, name)]
            if 'colour' in element:
                tex = np.array([[list(element['colour']) + [255]]], dtype=np.uint8)
                uvs = [(0, 0)] * 4
            else:
                key = face.get('texture', '#p').lstrip('#')
                ref = model.get('textures', {}).get(key, 'craftov:item/gun_palette')
                if ref not in textures:
                    textures[ref] = np.array(fpview.load_texture(model, key))
                tex = textures[ref]
                u0, v0, u1, v1 = face['uv']
                uvs = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
            out.append(([c[2] for c in cam], screen, shade, tex, uvs))
    return out


def render(gid, pose, angle, out, crouch=False, label=''):
    view = camera(angle)
    polys = []
    for matrix, element in body_parts(pose, crouch):
        # Body boxes are in pixels: scale to blocks before the world flip.
        m = fpview.mat_mul(fpview.scale(1 / 16.0, 1 / 16.0, 1 / 16.0), matrix)
        polys += polys_of([element], m, view, units=1.0)
    path = os.path.join(fpview.ROOT, 'models', 'item', VARIANT[pose], gid + ('_crouch' if crouch and pose != 'reload' else '') + '.json')
    model = fpview.load_model(path)
    display = model['display']['thirdperson_righthand']
    polys += polys_of(model['elements'], item_matrix(pose, display, crouch), view, {}, model)
    img = Image.new('RGB', (W, H), (150, 172, 196))
    d = ImageDraw.Draw(img)
    d.rectangle([0, H * 0.62, W, H], fill=(112, 116, 108))
    fpview.zbuffer_fill(img, polys)
    ImageDraw.Draw(img).text((8, 8), label or '%s %s%s %d deg' % (gid, pose, ' crouched' if crouch else '', angle), fill=(255, 255, 255))
    img.save(out)


def sheet(gid, out):
    tiles = []
    for pose, crouch in (('low', False), ('low', True), ('shoulder', False), ('shoulder', True)):
        for angle in (35, 90):
            tmp = '/tmp/_tp_tile.png'
            render(gid, pose, angle, tmp, crouch)
            tiles.append(Image.open(tmp).copy())
    cols = 4
    rows = (len(tiles) + cols - 1) // cols
    img = Image.new('RGB', (cols * W // 2, rows * H // 2))
    for i, t in enumerate(tiles):
        img.paste(t.resize((W // 2, H // 2)), ((i % cols) * W // 2, (i // cols) * H // 2))
    img.save(out)
    print(out)


def main():
    gid = sys.argv[1] if len(sys.argv) > 1 else 'k47'
    what = sys.argv[2] if len(sys.argv) > 2 else 'sheet'
    out = sys.argv[3] if len(sys.argv) > 3 else '/tmp/tp_%s_%s.png' % (gid, what)
    if what == 'sheet':
        sheet(gid, out)
        return
    render(gid, what, float(sys.argv[4]) if len(sys.argv) > 4 else 35, out)
    print(out)


if __name__ == '__main__':
    main()
