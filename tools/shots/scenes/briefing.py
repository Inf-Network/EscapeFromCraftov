"""The first-join briefing (#7): its three pages (briefing-1 goal, briefing-2 rules, briefing-3
controls, which name the keys this client has bound), opened with /cv guide <page>. Esc answers each
with the start button, so the guide stays on."""


def run(s):
    s.lobby()
    s.clear_chat()
    for page in (1, 2, 3):
        s.cmd(f'cv guide {page}')
        s.wait(1.5)
        s.park_mouse()
        s.shot(f'briefing-{page}')
        s.key('Escape')
        s.wait(1)
