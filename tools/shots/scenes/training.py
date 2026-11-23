"""The training raid (#7) as a new player sees it; run with `--fresh` (the deploy screen offers it
while the guide runs).

training-card: the deploy screen, pointer on its first card, the training raid.
training-start: on the course facing the supply crate: the objective line, the compass marker and
the first lesson's sign.
training-crate: the crate's search screen with the rounds, bandages and splint.
training-aim: at the firing line aiming down the red dot at the first target, objective 0/3.
training-hits: all three targets hit, the objective on to the next lesson.
training-trap: the tripwire went off: the title, the leg and bleed icons on the HUD.
training-yard: the extract yard after the wounds are treated: the lever ahead, objective and marker.
training-extract: standing in the extract after pulling the lever: the countdown.
training-settlement: the training settlement screen, pointer on the outcome with its lessons.
"""
import math
import re

G = 64
TARGETS = [(22, -7), (30, -17), (38, -27)]


def angles(frm, to):
    """yaw and pitch (degrees, as /tp takes them) from a standing player at `frm` to the point `to`."""
    dx, dy, dz = to[0] - frm[0], to[1] - (frm[1] + 1.62), to[2] - frm[2]
    yaw = -math.degrees(math.atan2(dx, dz))
    pitch = -math.degrees(math.atan2(dy, math.hypot(dx, dz)))
    return yaw, pitch


def hits(s):
    """Targets hit so far, read off the objective line (`cv hud`); 3 once the line moved on."""
    line = s.rcon(f'cv hud {s.name}')
    match = re.search(r'打靶 (\d)/3', line)
    return int(match.group(1)) if match else 3


def tp(s, x, y, z, yaw, pitch):
    s.rcon(f'execute as {s.name} at @s run tp @s {x} {y} {z} {yaw:.2f} {pitch:.2f}')


def run(s):
    s.lobby()
    s.clear_chat()
    s.cmd('cv deploy')
    s.wait(1.5)
    s.mouse_to(*s.slot_center(10, 5))
    s.wait(0.8)
    s.shot('training-card')
    s.click_slot(10, 5)
    s.wait_until(s.in_raid, 40, 'the training ground')
    s.wait(4)
    s.clear_chat()
    # The crate stands at 11.5 / 10.5, east of the spawn.
    tp(s, 5.5, G + 1, 10.5, -90, 12)
    s.wait(3)
    s.shot('training-start')
    tp(s, 9.0, G + 1, 10.5, -90, 30)
    s.wait(1)
    s.focus()
    s.xdo('click', '3')
    s.wait(6)  # the search reveals one item after another
    s.park_mouse()
    s.shot('training-crate')
    for slot in range(3):
        s.click_slot(slot, 3)
    s.key('Escape')
    s.wait(1.5)

    # The range: aimed hits on each target, then a reload.
    s.select(0)
    s.wait(1.5)
    spot = (30.5, G + 1, 5.5)
    for i, (x, z) in enumerate(TARGETS):
        yaw, pitch = angles(spot, (x + 0.5, G + 1.2, z + 0.5))
        for attempt in range(8):
            if hits(s) > i:
                break
            if attempt in (2, 5):
                s.key('q')  # the magazine holds 8: reload and go on
                s.wait(3.5)
            tp(s, *spot, yaw, pitch)
            s.wait(1)
            with s.mouse(3):
                s.wait(1.2)
                if i == 0 and attempt == 0:
                    s.shot('training-aim')
                with s.mouse(1):
                    s.wait(0.12)
                s.wait(0.8)
    s.key('q')
    s.wait(1.2)
    s.shot('training-hits')
    s.wait(3)

    # The trap corridor: through the gate and over the tripwire.
    tp(s, 47.5, G + 1, 10.5, -90, 0)
    s.wait(1)
    tp(s, 52.5, G + 1, 10.5, -90, 0)
    s.wait(1.2)
    s.shot('training-trap')
    s.wait(2)
    for item, seconds in (('bandage', 3.3), ('splint', 4.6)):
        slot = s.slot_of(item)
        if slot is not None:
            s.select(slot)
            s.wait(1)
            with s.mouse(3):
                s.wait(seconds)
            s.wait(1)
    s.wait(1.5)

    # The yard: the lever, then the extract.
    tp(s, 62.5, G + 1, 10.5, -140, 15)
    s.wait(2)
    s.shot('training-yard')
    yaw, pitch = angles((65.5, G + 1, 7.5), (65.5, G + 2.2, 5.5))
    tp(s, 65.5, G + 1, 7.5, yaw, pitch)
    s.wait(1)
    s.focus()
    s.xdo('click', '3')
    s.wait(1)
    tp(s, 74.5, G + 1, 10.5, -90, 10)
    s.wait(3)
    s.shot('training-extract')
    s.wait_until(s.in_lobby, 30, 'the way back to the lobby')
    s.wait(2.5)  # the curtain lifts and the screen opens 30 ticks after arriving
    s.mouse_to(*s.slot_center(8, 6))
    s.wait(0.8)
    s.shot('training-settlement')
    s.key('Escape')
    s.wait(0.5)
