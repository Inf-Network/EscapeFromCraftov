"""A gun in hand: hip, aimed (right mouse held), third person from behind and the front. Arg: item id (m4c)."""


def run(s):
    gun = s.arg or 'm4c'
    s.lobby()
    s.hold(gun)
    s.clear_chat()
    s.wait(1.5)
    s.shot(f'{gun}-hip')
    with s.mouse(3):
        s.wait(1.5)
        s.shot(f'{gun}-ads')
    s.wait(0.5)
    s.key('F5')
    s.wait(1)
    s.shot(f'{gun}-third')
    s.key('F5')
    s.wait(1)
    s.shot(f'{gun}-front')
    s.key('F5')  # back to first person
