"""Craftov design tokens: the one palette every screen, HUD panel and icon plate is drawn with.

gui/Style.java holds the same values for chat, lore and menu titles (MiniMessage tags <text>, <muted>,
<accent> ...). Rarity colours are not here: they belong to items only (build.RARITY_COLORS, Rarity.java).
"""
from px import rgb

BG = '#0D1014'       # screen backdrop, slot wells
PANEL = '#151A20'    # window body
RAISED = '#1C222A'   # buttons, headers, raised tiles
LINE = '#2C333D'     # dividers, frames
ACCENT = '#F2B544'   # the one accent: selection, money, primary action
TEXT = '#E9E5DA'     # primary text, titles
MUTED = '#A4AAB3'    # secondary text, labels
DIM = '#69707A'      # disabled, placeholders
OK = '#7FD1AE'
DANGER = '#E5484D'
INFO = '#8AB4F8'
# Carry zones (#10): the rig row and the backpack rows are told apart by a hue of their own, kept dull
# so they never read as a rarity. Used for the wells, the margin tags and the locked-cell stripes.
ZONE_RIG = '#5C87A8'   # steel blue
ZONE_PACK = '#8C8A5A'  # olive drab


def luminance(color):
    """WCAG relative luminance of '#RRGGBB'."""
    def channel(c):
        c /= 255
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = rgb(color)[:3]
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)


def contrast(fg, bg):
    """WCAG contrast ratio between two '#RRGGBB' colours (1..21)."""
    a, b = sorted((luminance(fg), luminance(bg)), reverse=True)
    return (a + 0.05) / (b + 0.05)
