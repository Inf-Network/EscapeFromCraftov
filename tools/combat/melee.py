#!/usr/bin/env python3
"""How many knife hits it takes to down a player or a soldier, by knife and armour.

Mirrors DamageService.melee: a knife hit goes through the same armour step as a bullet (see
armor.py), with the knife's damage as the base, its pen as the penetration and its armor-damage
as the wear multiplier. The part hit scales it like a bullet (combat.zone-multipliers; the head by
the knife's own head multiplier, 1.0 unless items.yml says otherwise), a helmet covers the head and
a vest the chest and stomach. Players have 100 HP; soldiers have AiService's health pools and wear
an armor_N vest and, four times in five, a helmet one class lower or the same.

  python3 tools/combat/melee.py [items.yml] [config.yml]

Prints, for every knife, the hits (and seconds of swinging at its cooldown) to down
  1. a 100 HP player hit in the chest, with no vest and a fresh armor_1..6;
  2. the same hit in the head, with no helmet and a fresh helmet_1..6;
  3. a boss (380 HP) hit in the chest through a fresh armor_4..6, and in the head with no helmet.

Exits 1 if one of the checks from issue #27 fails: a boss in its vest takes at least 10 chest hits
from every knife, a player in a class 4 or better vest at least 5, and a target with no armour
2 or 3 (chest or head, player or 100 HP soldier).
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

    table("Player (100 HP), chest hits to down", PLAYER_HP, "chest", [None] + vests)
    table("Player (100 HP), head hits to down", PLAYER_HP, "head", [None] + helmets)
    table("Boss (380 HP), chest hits through its vest / head hits with no helmet", BOSS_HP, "chest",
          [v for v in vests if items[v]["class"] >= 4])
    print("Boss (380 HP), head hits with no helmet")
    for kid, k in knives.items():
        print(f"  {kid:15} {cell(k, BOSS_HP, 'head', None)[1]}")
    print()

    failures = []

    def check(name, ok, got):
        print(f"{'PASS' if ok else 'FAIL'}  {name}: {got}")
        if not ok:
            failures.append(name)

    for kid, k in knives.items():
        boss = [cell(k, BOSS_HP, "chest", v)[0] for v in vests if items[v]["class"] >= 4]
        check(f"{kid}: a boss in armor_4..6 takes >= 10 chest hits", min(boss) >= 10, boss)
        heavy = [cell(k, PLAYER_HP, "chest", v)[0] for v in vests if items[v]["class"] >= 4]
        check(f"{kid}: a player in armor_4..6 takes >= 5 chest hits", min(heavy) >= 5, heavy)
        bare = [cell(k, PLAYER_HP, "chest", None)[0], cell(k, PLAYER_HP, "head", None)[0]]
        check(f"{kid}: no armour takes 2-3 hits (chest, head)", all(2 <= n <= 3 for n in bare), bare)
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
