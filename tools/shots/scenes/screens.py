"""Every designed lobby screen (#6): terminal, deploy, stash, trader, gunsmith and the mailbox, plus a
hovered button so the tooltip style shows. Shots: screen-main, screen-main-hover, screen-deploy,
screen-stash, screen-trader, screen-trader-tab, screen-gunsmith, screen-mail."""


def open_menu(s, command, name, rows=6, hover=None):
    s.clear_chat()
    s.cmd('cv ' + command)
    s.wait(1.5)
    s.park_mouse()
    s.shot('screen-' + name)
    if hover is not None:
        s.mouse_to(*s.slot_center(hover, rows))
        s.wait(0.6)
        s.shot('screen-' + name + '-hover')
        s.park_mouse()


def run(s):
    s.lobby()
    try:
        open_menu(s, 'menu', 'main', hover=10)
    finally:
        s.key('Escape')
    try:
        open_menu(s, 'deploy', 'deploy', rows=5)
    finally:
        s.key('Escape')
    try:
        open_menu(s, 'stash', 'stash')
    finally:
        s.key('Escape')
    try:
        open_menu(s, 'trader', 'trader')
        s.click_slot(50, 6)  # the medical tab: the title follows the tab in place
        s.park_mouse()
        s.shot('screen-trader-tab')
    finally:
        s.key('Escape')
    try:
        open_menu(s, 'mod', 'gunsmith')
    finally:
        s.key('Escape')
        s.wait(0.5)
    try:
        open_menu(s, 'mail', 'mail')
    finally:
        s.key('Escape')
        s.wait(0.5)
