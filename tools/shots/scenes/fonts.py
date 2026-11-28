"""The game's text in the client's fonts (#163): a gun's and an attachment's tooltip over the stash,
the stash itself, a main-menu button (its name is bold), the chat with game and player lines, and
the lobby view: the HUD and the world's name labels at a distance.

Arg: a label that starts every shot name (default: font). Shots are <label>-<height>p-gui<scale>-
gun / -attachment / -stash / -bold / -chat / -hud, so runs at other window sizes and GUI scales
(`--size 2560x1440 --gui-scale 2`) and before / after a change sit side by side in tools/shots/out/.
"""

GUN, ATTACHMENT = 'm4c', 'att_taclight'
BOLD_BUTTON = 11  # the terminal's stash button, "<b>仓库"
CHAT = [
    '{"text":"系统：你的仓库价值 ◈1,234,567，本局收益 +◈56,780（12.5%）","color":"#F2C94C"}',
    '{"text":"M4C 突击步枪 · 5.56x45mm · 射速 800 RPM · 后坐 0.92","color":"#9AA0A6"}',
    '{"text":"<Snowball_233> 我在北边的仓库，ABC abc 0123456789，快来！","color":"white"}',
]


def menu_index(slot):
    """Menu slot of a player inventory slot under a 6-row chest (main rows 54..80, hotbar 81..89)."""
    return 54 + (slot - 9) if slot >= 9 else 81 + slot


def run(s):
    _, height = s.cfg.size.split('x')
    prefix = f'{s.arg or "font"}-{height}p-gui{s.gui_scale()}-'
    s.lobby()
    s.hold(GUN)
    found = {}
    for _ in range(2):
        for slot in range(36):
            item = s.item_in(slot)
            if item in (GUN, ATTACHMENT) and item not in found:
                found[item] = slot
        if ATTACHMENT in found:
            break
        s.give(ATTACHMENT)
        s.wait(1)
    s.clear_chat()
    s.wait(1.5)
    s.shot(prefix + 'hud')

    s.cmd('cv stash')
    s.wait(1.5)
    try:
        s.park_mouse()
        s.shot(prefix + 'stash')
        for name, item in (('gun', GUN), ('attachment', ATTACHMENT)):
            if item in found:
                s.mouse_to(*s.slot_center(menu_index(found[item]), 6))
                s.wait(0.8)
                s.shot(prefix + name)
    finally:
        s.park_mouse()
        s.key('Escape')
        s.wait(0.5)

    s.cmd('cv menu')
    s.wait(1.5)
    try:
        s.mouse_to(*s.slot_center(BOLD_BUTTON, 6))
        s.wait(0.8)
        s.shot(prefix + 'bold')
    finally:
        s.park_mouse()
        s.key('Escape')
        s.wait(0.5)

    s.clear_chat()
    s.cmd('cv help')
    for line in CHAT:
        s.rcon(f'tellraw {s.name} {line}')
    s.wait(0.5)
    s.key('t')  # an open chat shows every line at full opacity
    s.wait(0.8)
    try:
        s.shot(prefix + 'chat')
    finally:
        s.key('Escape')
        s.wait(0.5)
