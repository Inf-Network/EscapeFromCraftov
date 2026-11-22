"""Another player holding a gun, as everyone else sees it: front and right side. Arg: item id (m4c).

The plugin rewrites other players' guns into a charged crossbow in equipment packets, so they hold
them with both hands; the player's own third-person view never shows that. The other player is a
mineflayer bot (tools/bot/puppet.js), so tools/bot needs its node_modules. The HUD is hidden (F1)
for a clean look at the model.
"""


def run(s):
    gun = s.arg or 'm4c'
    s.lobby()
    with s.puppet() as mate:
        mate.hold(gun)
        mate.tp(0.5, 65, 17.5, 180)  # open floor behind the lobby spawn, facing the hall
        s.key('F1')
        try:
            for view, angle in (('front', 30), ('side', 90)):
                mate.orbit(angle, distance=2.5)
                s.shot(f'others-{gun}-{view}')
        finally:
            s.key('F1')
