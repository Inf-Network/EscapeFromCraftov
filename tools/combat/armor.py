#!/usr/bin/env python3
"""How many hits it takes to down a player, by gun, round and armour.

Mirrors DamageService.bullet / armor / armorStrength and the combat.armor settings in config.yml:
a hit's damage is gun damage x round damage x hit-zone multiplier x armour multiplier, where the
armour multiplier is 1 - (1 - damage-taken[pen - class]) x strength and strength fades below
full-protection-above of the durability and by how much of the hit's wear the durability left
can absorb. Every hit wears gun damage x round damage x armor-damage x wear. A player has 100 HP
and is down when it reaches 0. Range falloff, bleeding and operator passives are left out.

  python3 tools/combat/armor.py [items.yml] [config.yml]

Prints
  1. chest shots to down each gun with each of its rounds against no vest and a fresh armor_1..6;
  2. the damage of every K47 PS shot into a fresh armor_4's chest (what tools/bot/armor.js measures);
  3. average hits to down when the part hit is random (shares of the standing front hitbox) against
     a matching fresh helmet and vest.

Exits 1 if one of the checks from issue #26 fails: K47 PS needs at least 4 chest shots against
armor_2 and 5 against armor_3, M4C M995 at least 5 against armor_4, a vest with no durability left
is the same as none, and armour above half its durability protects as well as a new one.
"""

import argparse
from pathlib import Path
import random
import sys

import yaml

ROOT = Path(__file__).resolve().parents[2]
MAX_HP = 100.0
# DamageService defaults, used when config.yml leaves a key out.
DAMAGE_TAKEN = [0.85, 0.6, 0.4, 0.25, 0.15]
FULL_ABOVE = 0.5
WEAR = 0.3
ZONES = {"chest": 1.0, "stomach": 0.95, "arm": 0.5, "leg": 0.5}
# Shares of a standing player's front hitbox (HitZone / BodyPart.of): head above (eye - 0.32) / 1.8,
# legs below 0.40, the torso's outer 38% each side are arms, chest above 0.55.
PARTS = {"head": 0.278, "leg": 0.40, "arm": 0.122, "chest": 0.2 * 0.172 / 0.322, "stomach": 0.2 * 0.150 / 0.322}


class Armour:
    def __init__(self, defn, fraction=1.0):
        self.cls = defn["class"]
        self.max = defn["durability"]
        self.dura = self.max * fraction


class Model:
    def __init__(self, items, config):
        armor = ((config.get("combat") or {}).get("armor")) or {}
        self.table = armor.get("damage-taken") or DAMAGE_TAKEN
        self.full_above = armor.get("full-protection-above", FULL_ABOVE)
        self.wear_blocked = armor.get("wear-blocked", WEAR)
        self.wear_pen = armor.get("wear-penetrated", WEAR)
        self.zones = dict(ZONES)
        self.zones.update(((config.get("combat") or {}).get("zone-multipliers")) or {})
        self.items = items

    def strength(self, dura, max_dura, loss):
        """DamageService.armorStrength."""
        if dura <= 0 or max_dura <= 0:
            return 0.0
        condition = 1.0 if self.full_above <= 0 else min(1.0, dura / max_dura / self.full_above)
        absorbed = 1.0 if loss <= 0 else min(1.0, dura / loss)
        return condition * absorbed

    def taken(self, diff):
        index = max(0, 1 - diff)
        return max(0.0, min(1.0, self.table[min(index, len(self.table) - 1)]))

    def hit(self, gun, ammo, part, armour):
        """One hit; wears `armour` (or None) and returns the damage dealt."""
        base = gun["damage"] * ammo.get("damage", 1.0)
        zone = gun.get("head", 2.0) if part == "head" else self.zones[part]
        mult = 1.0
        if armour is not None and armour.dura > 0:
            diff = ammo.get("pen", 1) - armour.cls
            loss = base * ammo.get("armor-damage", 1.0) * (self.wear_pen if diff >= 0 else self.wear_blocked)
            mult = 1.0 - (1.0 - self.taken(diff)) * self.strength(armour.dura, armour.max, loss)
            armour.dura = max(0.0, armour.dura - loss)
        return base * zone * mult

    def chest_shots(self, gun, ammo, vest):
        hp, shots = MAX_HP, 0
        while hp > 0:
            hp -= self.hit(gun, ammo, "chest", vest)
            shots += 1
        return shots

    def random_hits(self, gun, ammo, helmet_def, vest_def, trials, rng):
        names, weights = list(PARTS), list(PARTS.values())
        total = 0
        for _ in range(trials):
            helmet = Armour(helmet_def) if helmet_def else None
            vest = Armour(vest_def) if vest_def else None
            hp, hits = MAX_HP, 0
            while hp > 0:
                part = rng.choices(names, weights)[0]
                cover = helmet if part == "head" else vest if part in ("chest", "stomach") else None
                hp -= self.hit(gun, ammo, part, cover)
                hits += 1
            total += hits
        return total / trials


def main():
    parser = argparse.ArgumentParser(description="Hits to down a player, by gun, round and armour.")
    parser.add_argument("items", nargs="?", default=ROOT / "src/main/resources/items.yml", type=Path)
    parser.add_argument("config", nargs="?", default=ROOT / "src/main/resources/config.yml", type=Path)
    parser.add_argument("--trials", type=int, default=20000, help="random-part trials per cell")
    args = parser.parse_args()
    items = yaml.safe_load(args.items.read_text(encoding="utf-8"))["items"]
    config = yaml.safe_load(args.config.read_text(encoding="utf-8"))
    model = Model(items, config)

    guns = {k: v["gun"] for k, v in items.items() if "gun" in v}
    rounds = {k: v["ammo"] for k, v in items.items() if "ammo" in v}
    vests = [k for k, v in items.items() if v.get("category") == "ARMOR"]
    helmets = [k for k, v in items.items() if v.get("category") == "HELMET"]
    vests.sort(key=lambda k: items[k]["class"])
    helmets.sort(key=lambda k: items[k]["class"])

    print("Chest shots to down (fresh vest)")
    print(f"{'gun':6} {'round':16} {'pen':>3} {'none':>5}" + "".join(f"{v:>9}" for v in vests))
    for gid, gun in guns.items():
        for rid, ammo in rounds.items():
            if ammo["caliber"] != gun["caliber"]:
                continue
            row = [model.chest_shots(gun, ammo, None)] + [model.chest_shots(gun, ammo, Armour(items[v])) for v in vests]
            print(f"{gid:6} {rid:16} {ammo['pen']:>3} {row[0]:>5}" + "".join(f"{n:>9}" for n in row[1:]))

    k47, ps, m4c, m995 = guns["k47"], rounds["ammo_762_ps"], guns["m4c"], rounds["ammo_556_m995"]
    vest = Armour(items["armor_4"])
    seq = [model.hit(k47, ps, "chest", vest) for _ in range(10)]
    print("\nK47 PS into a fresh armor_4, damage per shot:")
    print("  " + " ".join(f"{d:.1f}" for d in seq))

    rng = random.Random(26)
    print(f"\nAverage hits to down, random part ({args.trials} trials), matching fresh helmet + vest")
    pairs = [(None, None)] + list(zip(helmets, vests))
    print(f"{'gun':6} {'round':16}" + "".join(f"{('none' if h is None else h[-1] + '/' + v[-1]):>7}" for h, v in pairs))
    for gid, rid in (("k47", "ammo_762_ps"), ("k47", "ammo_762_bp"), ("k47", "ammo_762_ap"),
                     ("m4c", "ammo_556_m855"), ("m4c", "ammo_556_a1"), ("m4c", "ammo_556_m995")):
        cells = [model.random_hits(guns[gid], rounds[rid], items[h] if h else None, items[v] if v else None,
                                   args.trials, rng) for h, v in pairs]
        print(f"{gid:6} {rid:16}" + "".join(f"{c:>7.2f}" for c in cells))

    failures = []

    def check(name, ok, got):
        print(f"{'PASS' if ok else 'FAIL'}  {name}: {got}")
        if not ok:
            failures.append(name)

    print()
    n = model.chest_shots(k47, ps, Armour(items["armor_2"]))
    check("K47 PS needs >= 4 chest shots against armor_2", n >= 4, n)
    n = model.chest_shots(k47, ps, Armour(items["armor_3"]))
    check("K47 PS needs >= 5 chest shots against armor_3", n >= 5, n)
    n = model.chest_shots(m4c, m995, Armour(items["armor_4"]))
    check("M4C M995 needs >= 5 chest shots against a fresh armor_4", n >= 5, n)
    want = [14.4] * 4 + [15.6, 21.3, 27.0, 34.1] + [36.0] * 2
    check("K47 PS into armor_4 shot by shot", all(abs(a - b) < 0.05 for a, b in zip(seq, want)),
          " ".join(f"{d:.1f}" for d in seq))
    broken_same = all(
        model.chest_shots(guns[g], a, Armour(items[v], 0.0)) == model.chest_shots(guns[g], a, None)
        and model.hit(guns[g], a, "chest", Armour(items[v], 0.0)) == model.hit(guns[g], a, "chest", None)
        for g in guns for a in rounds.values() if a["caliber"] == guns[g]["caliber"] for v in vests)
    check("a vest with no durability left is the same as none", broken_same, broken_same)
    half_same = True
    for g in guns:
        for a in rounds.values():
            if a["caliber"] != guns[g]["caliber"]:
                continue
            for v in vests + helmets:
                part = "head" if v in helmets else "chest"
                fresh = model.hit(guns[g], a, part, Armour(items[v]))
                for fraction in (0.5, 0.75):
                    armour = Armour(items[v], fraction)
                    # Only when the hit does not wear more than the durability left.
                    wear = model.wear_pen if a.get("pen", 1) >= items[v]["class"] else model.wear_blocked
                    loss = guns[g]["damage"] * a.get("damage", 1.0) * a.get("armor-damage", 1.0) * wear
                    if armour.dura >= loss and abs(model.hit(guns[g], a, part, armour) - fresh) > 1e-9:
                        half_same = False
    check("armour at 50% durability or more protects as well as new", half_same, half_same)
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
