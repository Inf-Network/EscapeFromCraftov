#!/usr/bin/env python3
"""Expected loot of a raid, as a regression check for loot.yml, items.yml and the tier settings.

Mirrors LootService.roll and stackFor: the item count, the category and rarity draws (with the
difficulty shift), the fallback to another rarity when a category has nothing at the one drawn,
stack sizes, worn armour and rounds left in a gun, all valued like ItemFactory.value. The map is
the container list of maps/rustport.yml (a snapshot is kept in tools/econ/maps/rustport.json;
the generator has a fixed seed, so it only changes when RustPortGenerator does).

The rules follow LootService.java of the same commit: the old unbounded shift
(x (1 + 0.55 s)^rarity / (1 + 0.4 s), container tiers added without a cap) or the bounded one
(x (1 + shift-bonus x s) from loot.yml, capped at the tier's max-shift), and a fallback to the nearest
rarity either way or only to lower ones. So `--before origin/main` shows the old numbers next to
the new ones.

  python3 tools/econ/ev.py                        # the files in this checkout
  python3 tools/econ/ev.py --before origin/main   # the same tables at another commit first
  python3 tools/econ/ev.py --map server/run/plugins/Craftov/maps/rustport.yml

A "run" is N containers picked at random from the whole map, of which the C most valuable items
are carried out (one cell each), valued at item value. It only prints: the owner keeps the regular
loot generous on purpose and evens out bad luck with personal pity and a quota pool (#80,
tools/econ/luck.py), so there is no ceiling on what a run may be worth.
"""

import argparse
import json
import random
import statistics
import subprocess
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
RARITIES = ["WHITE", "GREEN", "BLUE", "PURPLE", "GOLD", "RED"]
DEFAULT_STACK = {"AMMO": 60, "VALUABLE": 1, "COLLECTIBLE": 1, "MEDICAL": 3, "THROWABLE": 3}
DEFAULT_BONUS = {"WHITE": -0.25, "GREEN": -0.05, "BLUE": 0.1, "PURPLE": 0.3, "GOLD": 0.5, "RED": 1.0}


class Source:
    """Reads repository files from the working tree, or from a commit with `git show`."""

    def __init__(self, ref=None):
        self.ref = ref

    def text(self, path):
        if self.ref is None:
            return (ROOT / path).read_text(encoding="utf-8")
        return subprocess.run(["git", "-C", str(ROOT), "show", f"{self.ref}:{path}"], check=True,
                              capture_output=True, text=True, encoding="utf-8").stdout

    def yaml(self, path):
        return yaml.safe_load(self.text(path))

    def label(self):
        return self.ref or "working tree"


class Item:
    def __init__(self, item_id, s):
        self.id = item_id
        self.category = s.get("category", "VALUABLE").upper()
        self.rarity = s.get("rarity", "WHITE").upper()
        self.value = s.get("value", 100)
        self.stack = max(1, min(99, s.get("stack", DEFAULT_STACK.get(self.category, 1))))
        self.lootable = s.get("lootable", True)
        self.gun = s.get("gun")
        self.ammo = s.get("ammo")
        self.durability = s.get("durability", (s.get("med") or {}).get("pool", 0))
        self.weight = s.get("weight")

    @property
    def armor(self):
        return self.category in ("HELMET", "ARMOR")


class Data:
    def __init__(self, source, map_path=None):
        self.source = source
        self.items = {k: Item(k, v) for k, v in source.yaml("src/main/resources/items.yml")["items"].items()}
        loot = source.yaml("src/main/resources/loot.yml")
        self.containers = loot["containers"]
        # LootService.DEFAULT_BONUS when loot.yml has no shift-bonus.
        self.bonus = dict(DEFAULT_BONUS, **{r.upper(): float(v) for r, v in (loot.get("shift-bonus") or {}).items()})
        self.config = source.yaml("src/main/resources/config.yml")
        if map_path:
            raw = yaml.safe_load(Path(map_path).read_text(encoding="utf-8"))
            self.map = [(c["type"], c.get("tier", 0)) for c in raw["containers"]]
            self.soldiers = sum(a.get("count", 1) for a in raw.get("ai", []))
            self.bosses = len(raw.get("bosses", []))
        else:
            raw = json.loads((ROOT / "tools/econ/maps/rustport.json").read_text(encoding="utf-8"))
            self.map = [(c["type"], c["tier"]) for c in raw["containers"] for _ in range(c["count"])]
            self.soldiers = sum(a["count"] for a in raw["ai"])
            self.bosses = len(raw["bosses"])
        code = source.text("src/main/java/net/infnetwork/snowball/craftov/loot/LootService.java")
        self.bounded = "0.55 * shift" not in code
        self.two_way = "new int[]{-1, 1}" in code
        self.tiers = self.config["tiers"]
        self.by_category = {}
        for item in self.items.values():
            self.by_category.setdefault(item.category, []).append(item)

    # ------------------------------------------------------------------ LootService

    def shift(self, tier_id, bonus):
        """Shift of a roll in a raid of this tier, `bonus` above the tier's own (container tier, boss)."""
        t = self.tiers[tier_id]
        own = t.get("loot-shift", 0)
        if not self.bounded:
            return own + bonus
        return max(0, min(own + bonus, t.get("max-shift", min(2, own + 1))))

    def rarity_weights(self, ctype, shift):
        base = {r.upper(): float(w) for r, w in (ctype.get("rarity") or {}).items()}
        if shift <= 0:
            return base
        if self.bounded:
            return {r: w * max(0.0, 1 + self.bonus.get(r, 0.0) * shift) for r, w in base.items()}
        return {r: w * (1 + 0.55 * shift) ** RARITIES.index(r) / (1 + 0.4 * shift) for r, w in base.items()}

    def candidates(self, category, rarity):
        """LootService.pick: the items it chooses between (uniformly), or [] when it gives up."""
        idx = RARITIES.index(rarity)
        order = [idx]
        if self.two_way:
            for d in range(1, len(RARITIES)):
                order += [idx - d, idx + d]
        else:
            order += list(range(idx - 1, -1, -1))
        for i in order:
            if 0 <= i < len(RARITIES):
                found = [it for it in self.by_category.get(category, []) if it.lootable and it.rarity == RARITIES[i]]
                if found:
                    return found
        return []

    def item_distribution(self, type_id, shift):
        """{item id: probability} of one rolled item; the missing mass is a roll that yields nothing."""
        ctype = self.containers[type_id]
        cats = {c.upper(): float(w) for c, w in (ctype.get("categories") or {}).items() if w > 0}
        rars = {r: w for r, w in self.rarity_weights(ctype, shift).items() if w > 0}
        ct, rt = sum(cats.values()), sum(rars.values())
        out = {}
        for c, cw in cats.items():
            for r, rw in rars.items():
                found = self.candidates(c, r)
                for it in found:
                    out[it.id] = out.get(it.id, 0) + cw / ct * rw / rt / len(found)
        return out

    def counts(self, type_id, shift):
        """[(item count, probability)]"""
        ctype = self.containers[type_id]
        lo, hi = (ctype.get("items") or [2, 4])[:2]
        n = max(1, hi - lo + 1)
        out = {}
        for k in range(lo, lo + n):
            for extra, p in ([(0, 0.5), (1, 0.5)] if shift > 1 else [(0, 1.0)]):
                out[k + extra] = out.get(k + extra, 0) + p / n
        return sorted(out.items())

    # ------------------------------------------------------------------ stackFor / ItemFactory.value

    def ammo_for(self, caliber):
        return [it for it in self.items.values() if it.ammo and it.ammo.get("caliber") == caliber]

    def mean_value(self, item):
        if item.category == "AMMO":
            amount = statistics.mean(min(item.stack, 10 + k) for k in range(31))
        elif item.stack > 1 and item.category != "MEDICAL":
            amount = statistics.mean(range(1, min(3, item.stack) + 1))
        else:
            amount = 1
        unit = item.value
        if item.armor and item.durability:
            unit *= 0.4 + 0.6 * (0.25 + 0.75 * 0.65)  # 60%: durability 35-95%
        total = unit * amount
        if item.gun:
            ammo = self.ammo_for(item.gun.get("caliber"))
            if ammo:
                total += 0.5 * statistics.mean(a.value for a in ammo) * item.gun.get("mag", 30) / 2
        return total

    def sample_value(self, item, rng):
        if item.category == "AMMO":
            amount = min(item.stack, 10 + rng.randrange(31))
        elif item.stack > 1 and item.category != "MEDICAL":
            amount = 1 + rng.randrange(min(3, item.stack))
        else:
            amount = 1
        unit = item.value
        if item.armor and item.durability and rng.random() < 0.6:
            dura = max(1, item.durability * (0.35 + rng.random() * 0.6))
            unit *= 0.25 + 0.75 * min(1, dura / item.durability)
        total = round(unit) * amount
        if item.gun and rng.random() < 0.5:
            ammo = self.ammo_for(item.gun.get("caliber"))
            if ammo:
                total += rng.choice(ammo).value * rng.randrange(item.gun.get("mag", 30) + 1)
        return total

    # ------------------------------------------------------------------ containers

    def container_ev(self, type_id, shift):
        dist = self.item_distribution(type_id, shift)
        per_item = sum(p * self.mean_value(self.items[i]) for i, p in dist.items())
        n = sum(k * p for k, p in self.counts(type_id, shift))
        return n * per_item

    def p_rarity(self, type_id, shift, rarity):
        """Chance the container holds at least one item of `rarity`."""
        dist = self.item_distribution(type_id, shift)
        p = sum(q for i, q in dist.items() if self.items[i].rarity == rarity)
        return sum(pk * (1 - (1 - p) ** k) for k, pk in self.counts(type_id, shift))

    def sampler(self, type_id, shift):
        dist = self.item_distribution(type_id, shift)
        ids = list(dist)
        weights = [dist[i] for i in ids]
        nothing = max(0.0, 1 - sum(weights))
        counts = self.counts(type_id, shift)

        def roll(rng):
            k = rng.choices([c for c, _ in counts], [p for _, p in counts])[0]
            picks = rng.choices(ids + [None], weights + [nothing], k=k)
            return [self.sample_value(self.items[i], rng) for i in picks if i is not None]

        return roll

    def map_ev(self, tier_id):
        return sum(self.container_ev(t, self.shift(tier_id, bonus)) for t, bonus in self.map)

    def runs(self, tier_id, containers, cells, trials, seed):
        rng = random.Random(seed)
        samplers = {}
        totals = []
        for _ in range(trials):
            values = []
            for t, bonus in rng.sample(self.map, containers):
                key = (t, self.shift(tier_id, bonus))
                if key not in samplers:
                    samplers[key] = self.sampler(*key)
                values += samplers[key](rng)
            values.sort(reverse=True)
            totals.append(sum(values[:cells]))
        totals.sort()
        return totals

    # ------------------------------------------------------------------ kits

    def kit_value(self, entries):
        total = 0
        for entry in entries:
            parts = str(entry).split(":")
            item = self.items.get(parts[0])
            if item is None:
                continue
            amount = int(parts[1]) if len(parts) > 1 else 1
            total += item.value * amount
            if item.gun and len(parts) > 2 and parts[2] in self.items:
                total += self.items[parts[2]].value * item.gun.get("mag", 30)
        return total

    def starter_value(self):
        return self.kit_value((self.config.get("starter") or {}).get("loadout", {}).values())

    def free_kit_value(self):
        return self.kit_value((self.config.get("free-kit") or {}).get("items", []))


def money(x):
    return f"{round(x):,}"


def report(data, containers, cells, trials, seed):
    """Prints the tables."""
    starter, free = data.starter_value(), data.free_kit_value()
    rules = ("bounded shift" if data.bounded else "old unbounded shift") + (
        ", fallback to either side" if data.two_way else ", fallback to lower rarities only")
    print(f"== {data.source.label()} ({rules}) ==")
    print(f"starter kit {money(starter)}   free kit {money(free)}   map: {len(data.map)} containers, "
          f"{data.soldiers} soldiers, {data.bosses} boss")
    print()
    print(f"{'tier':14} {'entry fee':>9} {'map EV':>11} {'run p10':>9} {'median':>9} {'mean':>9} {'p90':>10} "
          f"{'median/kit':>10}  vault RED  safe RED")
    for tier_id, t in data.tiers.items():
        totals = data.runs(tier_id, containers, cells, trials, seed)
        med = statistics.median(totals)
        vault_red = data.p_rarity("vault", data.shift(tier_id, 2), "RED")
        safe_red = data.p_rarity("safe", data.shift(tier_id, 1), "RED")
        print(f"{t.get('name', tier_id) + ' ' + tier_id:14} {money(t.get('entry-fee', 0)):>9} {money(data.map_ev(tier_id)):>11} "
              f"{money(totals[len(totals) // 10]):>9} {money(med):>9} {money(statistics.mean(totals)):>9} "
              f"{money(totals[len(totals) * 9 // 10]):>10} {med / starter:>10.2f}  {vault_red:>8.1%}  {safe_red:>8.1%}")
    print(f"(run = {containers} random containers, best {cells} items carried out, {trials} trials, item value)")
    print()
    shifts = sorted({data.shift(tid, b) for tid in data.tiers for _, b in data.map})
    print("expected value per container by shift" + ("" if data.bounded else " (shift = tier loot-shift + container tier)"))
    print(f"{'container':15}" + "".join(f"{'shift ' + str(s):>10}" for s in shifts) + f"{'RED@max':>9}")
    for type_id, ctype in data.containers.items():
        if ctype.get("all-of") or type_id == "body_bag":
            continue
        row = "".join(f"{money(data.container_ev(type_id, s)):>10}" for s in shifts)
        print(f"{type_id:15}{row}{data.p_rarity(type_id, shifts[-1], 'RED'):>9.1%}")
    print()
    for tier_id, t in data.tiers.items():
        parcel = data.container_ev("parcel", data.shift(tier_id, 0))
        boss = data.container_ev("vault", data.shift(tier_id, 1)) if t.get("boss") else 0
        soldiers = data.soldiers * t.get("ai-count", 1.0)
        print(f"{t.get('name', tier_id)}: each soldier's body adds a parcel roll worth {money(parcel)} "
              f"(~{soldiers:.0f} soldiers, {money(parcel * soldiers)} in all)"
              + (f"; the boss adds a vault roll worth {money(boss)}" if boss else ""))
    print()


def main():
    parser = argparse.ArgumentParser(description="Expected loot of a raid.")
    parser.add_argument("--before", metavar="REF", help="also print the tables for this git commit first")
    parser.add_argument("--map", help="a maps/*.yml file instead of the rustport snapshot")
    parser.add_argument("--containers", type=int, default=10)
    parser.add_argument("--cells", type=int, default=14)
    parser.add_argument("--trials", type=int, default=20000)
    parser.add_argument("--seed", type=int, default=22)
    args = parser.parse_args()
    if args.before:
        report(Data(Source(args.before), args.map), args.containers, args.cells, args.trials, args.seed)
    report(Data(Source(), args.map), args.containers, args.cells, args.trials, args.seed)


if __name__ == "__main__":
    main()
