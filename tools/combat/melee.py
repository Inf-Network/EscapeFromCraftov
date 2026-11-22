#!/usr/bin/env python3
"""How many knife hits it takes to down a player or a soldier, by knife and armour.

Mirrors DamageService.melee: a knife hit goes through the same armour step as a bullet (see
armor.py), with the knife's damage as the base, its pen as the penetration and its armor-damage
as the wear multiplier. The part hit scales it like a bullet (combat.zone-multipliers) except the
head, which takes the knife's own head multiplier (1.0 unless items.yml says otherwise), and the
arms and legs, which take combat.melee.zone-multipliers. A helmet covers the head and a vest the
chest and stomach. Players have 100 HP; a boss has 380 and wears an armor_4..6 vest and, four times
in five, a helmet one class lower or the same (helmet_3..6).

A knife cut lands where the attacker looks, and a level swing at a standing player or soldier
lands on its head (its head line is below the attacker's eye), so the head counts as much as the
chest.

  python3 tools/combat/melee.py [items.yml] [config.yml]

Prints, for every knife, the hits (and seconds of swinging at its cooldown) to down
  1. a 100 HP player hit in the chest, with no vest and a fresh armor_1..6;
  2. the same hit in the head, with no helmet and a fresh helmet_1..6;
  3. the same hit in an arm and in a leg (no armour covers them);
  4. a boss (380 HP) hit in the chest through a fresh armor_4..6, in the head with no helmet and
     through a fresh helmet_3..6, and in an arm and a leg.

Exits 1 if one of the checks from issue #27 fails: a boss takes at least 10 hits from every knife
wherever it is hit, a player in a class 4 or better vest at least 5 chest hits and in a class 4 or
better helmet at least 5 head hits, a player at least 4 arm or leg hits (as many as a K47 with PS
takes in the legs) whatever armour it wears, and a target with no armour 2 or 3 (chest or head,
player or 100 HP soldier).
"""

import argparse
from pathlib import Path
import sys

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
from armor import Armour, Model  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
PLAYER_HP = 100.0
BOSS_HP = 380.0
# MeleeSpec defaults, used when items.yml leaves a key out.
DEFAULTS = {"damage": 34.0, "pen": 1, "armor-damage": 1.0, "head": 1.0, "cooldown": 12}
# DamageService.meleeZoneMultiplier defaults, used when config.yml has no combat.melee.zone-multipliers.
MELEE_ZONES = {"arm": 0.6, "leg": 0.6}
# Fewest arm or leg hits that may down a player in any armour: a K47 with PS takes 4 in the legs.
MIN_LIMB_HITS = 4


def knife(spec):
    out = dict(DEFAULTS)
    out.update(spec)
    return out


def as_gun(k):
    """The knife as the gun / round pair armor.Model.hit takes: same base, head, pen and wear."""
    return {"damage": k["damage"], "head": k["head"]}, {"pen": k["pen"], "armor-damage": k["armor-damage"], "damage": 1.0}


def hits_to_down(model, k, hp, part, armour):
    gun, ammo = as_gun(k)
    hits = 0
    while hp > 0:
        hp -= model.hit(gun, ammo, part, armour)
        hits += 1
        if hits > 999:
            break
    return hits


def seconds(k, hits):
    return (hits - 1) * k["cooldown"] / 20.0


def main():
    parser = argparse.ArgumentParser(description="Knife hits to down a player or a soldier, by armour.")
    parser.add_argument("items", nargs="?", default=ROOT / "src/main/resources/items.yml", type=Path)
    parser.add_argument("config", nargs="?", default=ROOT / "src/main/resources/config.yml", type=Path)
    args = parser.parse_args()
    items = yaml.safe_load(args.items.read_text(encoding="utf-8"))["items"]
    config = yaml.safe_load(args.config.read_text(encoding="utf-8"))
    model = Model(items, config)
    model.zones.update(MELEE_ZONES)
    model.zones.update((((config.get("combat") or {}).get("melee") or {}).get("zone-multipliers")) or {})

    knives = {k: knife(v["melee"]) for k, v in items.items() if "melee" in v}
    vests = sorted((k for k, v in items.items() if v.get("category") == "ARMOR"), key=lambda k: items[k]["class"])
    helmets = sorted((k for k, v in items.items() if v.get("category") == "HELMET"), key=lambda k: items[k]["class"])

    def cell(k, hp, part, armour_id):
        n = hits_to_down(model, k, hp, part, Armour(items[armour_id]) if armour_id else None)
        return n, f"{n} ({seconds(k, n):.1f}s)"

    def table(title, hp, part, covers):
        print(title)
        print(f"{'knife':15} {'dmg':>4} {'pen':>3} {'wear':>4} {'cd':>3}" + "".join(f"{c or 'none':>13}" for c in covers))
        for kid, k in knives.items():
            row = [cell(k, hp, part, c)[1] for c in covers]
            print(f"{kid:15} {k['damage']:>4g} {k['pen']:>3} {k['armor-damage']:>4g} {k['cooldown']:>3}"
                  + "".join(f"{c:>13}" for c in row))
        print()

    def limbs(title, hp):
        print(title)
        print(f"{'knife':15} {'dmg':>4} {'x arm':>6} {'x leg':>6}" + "".join(f"{p + ' hits':>13}" for p in ("arm", "leg")))
        for kid, k in knives.items():
            print(f"{kid:15} {k['damage']:>4g} {model.zones['arm']:>6g} {model.zones['leg']:>6g}"
                  + "".join(f"{cell(k, hp, p, None)[1]:>13}" for p in ("arm", "leg")))
        print()

    heavy_vests = [v for v in vests if items[v]["class"] >= 4]
    heavy_helmets = [h for h in helmets if items[h]["class"] >= 4]
    boss_helmets = [h for h in helmets if items[h]["class"] >= 3]
    table("Player (100 HP), chest hits to down", PLAYER_HP, "chest", [None] + vests)
    table("Player (100 HP), head hits to down (where a level swing lands)", PLAYER_HP, "head", [None] + helmets)
    limbs("Player (100 HP), arm and leg hits to down, whatever the armour", PLAYER_HP)
    table("Boss (380 HP), chest hits through its vest", BOSS_HP, "chest", heavy_vests)
    table("Boss (380 HP), head hits with no helmet and through its helmet", BOSS_HP, "head", [None] + boss_helmets)
    limbs("Boss (380 HP), arm and leg hits to down", BOSS_HP)

    failures = []

    def check(name, ok, got):
        print(f"{'PASS' if ok else 'FAIL'}  {name}: {got}")
        if not ok:
            failures.append(name)

    for kid, k in knives.items():
        boss = [cell(k, BOSS_HP, "chest", v)[0] for v in heavy_vests]
        check(f"{kid}: a boss in armor_4..6 takes >= 10 chest hits", min(boss) >= 10, boss)
        boss_head = [cell(k, BOSS_HP, "head", h)[0] for h in [None] + boss_helmets]
        check(f"{kid}: a boss with no helmet or helmet_3..6 takes >= 10 head hits", min(boss_head) >= 10, boss_head)
        boss_limbs = [cell(k, BOSS_HP, p, None)[0] for p in ("arm", "leg")]
        check(f"{kid}: a boss takes >= 10 arm and leg hits", min(boss_limbs) >= 10, boss_limbs)
        heavy = [cell(k, PLAYER_HP, "chest", v)[0] for v in heavy_vests]
        check(f"{kid}: a player in armor_4..6 takes >= 5 chest hits", min(heavy) >= 5, heavy)
        helmeted = [cell(k, PLAYER_HP, "head", h)[0] for h in heavy_helmets]
        check(f"{kid}: a player in helmet_4..6 takes >= 5 head hits (level swings)", min(helmeted) >= 5, helmeted)
        limb = [cell(k, PLAYER_HP, p, None)[0] for p in ("arm", "leg")]
        check(f"{kid}: a player in any armour takes >= {MIN_LIMB_HITS} arm and leg hits", min(limb) >= MIN_LIMB_HITS, limb)
        bare = [cell(k, PLAYER_HP, "chest", None)[0], cell(k, PLAYER_HP, "head", None)[0]]
        check(f"{kid}: no armour takes 2-3 hits (chest, head)", all(2 <= n <= 3 for n in bare), bare)
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
