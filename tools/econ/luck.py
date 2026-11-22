#!/usr/bin/env python3
"""Personal loot luck simulator (issue #80): pity, the quota pool and loss compensation.

Mirrors LuckService.java on top of the container rolls of tools/econ/ev.py (LootService): every
search of a container that can hold a single gold or red item counts towards that rarity's pity,
past the soft mark it may hand one out and at the hard mark it does; jackpots count qualifying
raids; qualifying raids pay into the quota pool by minutes and searches, times the tier's factor
and the behaviour multiplier of the last raids; once the pool covers a gold or red item worth at
least min-item-value the container could hold, a search hands one out with luck.quota.chance; a
net loss over the tier's threshold within the window gives a few compensated raids, in which each
search may hand out one more gold or red item with (multiplier - 1) times the container's own
rate, the container's roll staying as it is. Pity and quota hand-outs the player dies with pay
luck.quota.refund of their value back into the pool. All
numbers come from config.yml (luck, tiers), loot.yml and items.yml of the checkout.

  python3 tools/econ/luck.py                     # players of three kinds, 300 raids each, per tier
  python3 tools/econ/luck.py --raids 1000 --players 400
  python3 tools/econ/luck.py check               # the acceptance checks, exits 1 when one fails

`check` plays a player who never finds a red item on their own (natural reds are taken out of
every container) and one who never finds a jackpot, and verifies that the red is handed out no
later than the hard-th container that can hold one, the jackpot in the raid after hard-raids
raids, and that both counts start again from zero afterwards. It also checks that quota
hand-outs never spend more than the pool holds, that a loss over the threshold sets off the
compensation and that a compensated player finds about multiplier times as many gold and red
items while the containers' own rolls stay the same.

A player of each kind searches --containers containers a raid (picked at random from the map,
fully searched, so every natural gold and red is found), is in the raid --minutes minutes and gets
out with the kind's extraction chance carrying the --cells most valuable items; dying loses the
kit (KITS per tier) and everything found. Kills per raid also depend on the kind.
"""

import argparse
import math
import random
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ev  # noqa: E402

PITY = ["RED", "GOLD"]
# Kit value taken in, per tier (the starter kit, a 机密 kit, a 绝密 kit).
KITS = {"NORMAL": 55000, "CONFIDENTIAL": 160000, "SECRET": 420000}
# name: (extraction chance, player kills per raid, revives + team exits per raid)
KINDS = {
    "average": (0.6, 0.3, 0.2),
    "unlucky": (0.3, 0.05, 0.1),
    "strong": (0.85, 1.2, 0.3),
}


def money(x):
    return f"{round(x):,}"


class Settings:
    """The luck section of config.yml with LuckService's code defaults."""

    def __init__(self, config):
        luck = config.get("luck") or {}
        self.raw = luck

        def get(path, default):
            node = luck
            for key in path.split("."):
                if not isinstance(node, dict) or key not in node:
                    return default
                node = node[key]
            return node

        self.get = get
        self.min_seconds = get("qualify.min-seconds", 240)
        self.min_searches = get("qualify.min-searches", 3)
        self.pity = {}
        for r, (soft, hard) in {"GOLD": (15, 30), "RED": (120, 240)}.items():
            h = max(1, get(f"pity.{r}.hard", hard))
            self.pity[r] = (max(0, min(h - 1, get(f"pity.{r}.soft", soft))), h)
        self.jackpot_hard = max(1, get("jackpot.hard-raids", 1000))
        self.jackpot_soft = max(0, min(self.jackpot_hard - 1, get("jackpot.soft-raids", 700)))
        self.jackpot_soft_chance = get("jackpot.soft-chance", 0.02)
        self.per_minute = get("quota.per-minute", 1500)
        self.per_search = get("quota.per-search", 1200)
        self.max_minutes = get("quota.max-minutes", 30)
        self.max_searches = get("quota.max-searches", 30)
        self.tier_factor = get("quota.tier", {}) or {}
        self.min_value = max(1, get("quota.min-item-value", 100000))
        self.quota_chance = get("quota.chance", 0.25)
        self.refund = get("quota.refund", 0.5)
        self.cap = get("quota.cap", 1500000)
        b = lambda k, d: get("behaviour." + k, d)  # noqa: E731
        self.b_raids = max(1, b("raids", 20))
        self.b_min, self.b_max = b("min", 0.3), max(b("min", 0.3), b("max", 2.0))
        self.b = {k: b(k, d) for k, d in {"profit-weight": 0.4, "profit-base": 50000, "profit-scale": 2.0,
                                           "kd-weight": 0.15, "kd-scale": 2.0, "red-weight": 0.3,
                                           "red-baseline": 0.15, "red-scale": 0.3, "coop-weight": 0.25,
                                           "coop-target": 1.0}.items()}
        self.thresholds = {"NORMAL": 200000, "CONFIDENTIAL": 600000, "SECRET": 1500000}
        self.thresholds.update(get("compensation.threshold", {}) or {})
        self.boost_raids = max(0, get("compensation.raids", 3))
        mult = get("compensation.multiplier", {}) or {}
        self.boost = {"GOLD": max(1, mult.get("GOLD", 2.0)), "RED": max(1, mult.get("RED", 2.5))}

    def pity_chance(self, rarity, count):
        soft, hard = self.pity[rarity]
        if count >= hard:
            return 1.0
        return 0.0 if count <= soft else (count - soft) / (hard - soft)

    def jackpot_chance(self, raids):
        if raids >= self.jackpot_hard:
            return 1.0
        if raids < self.jackpot_soft:
            return 0.0
        return self.jackpot_soft_chance * (raids - self.jackpot_soft + 1) / (self.jackpot_hard - self.jackpot_soft)

    def multiplier(self, history):
        """LuckService.behaviour: history is the qualifying raids, newest first."""
        h = history[:self.b_raids]
        if not h:
            return 1.0
        b = self.b
        gains = sum(max(0, r["net"]) for r in h)
        losses = sum(max(0, -r["net"]) for r in h)
        kills = sum(r["kills"] for r in h)
        deaths = sum(r["deaths"] for r in h)
        reds = sum(1 for r in h if r["red"])
        coop = sum(r["coop"] for r in h)
        clamp = lambda v, lo, hi: max(lo, min(hi, v))  # noqa: E731
        base = max(1, b["profit-base"])
        profit = clamp(math.log2((gains + base) / (losses + base)) / b["profit-scale"], -1, 1)
        kd = clamp(math.log2((kills + 1) / (deaths + 1)) / b["kd-scale"], -1, 1)
        red = clamp((reds / len(h) - b["red-baseline"]) / b["red-scale"], -1, 1)
        coop_s = clamp(coop / len(h) / b["coop-target"], 0, 1)
        m = 1 - b["profit-weight"] * profit - b["kd-weight"] * kd - b["red-weight"] * red + b["coop-weight"] * coop_s
        return clamp(m, self.b_min, self.b_max)

    def income(self, tier_id, tier, minutes, searches, multiplier):
        factor = self.tier_factor.get(tier_id, 1.0 + 0.5 * tier.get("loot-shift", 0))
        base = min(minutes, self.max_minutes) * self.per_minute + min(searches, self.max_searches) * self.per_search
        return max(0, round(base * factor * multiplier))


class Loot:
    """Container rolls as item ids (ev.Data has the probabilities), shared by everyone."""

    def __init__(self, data):
        self.d = data
        self.cache = {}
        self.rates = {}

    def dist(self, type_id, shift):
        key = (type_id, shift)
        if key not in self.cache:
            d = self.d
            out = d.item_distribution(type_id, shift)
            ids = list(out)
            weights = [out[i] for i in ids]
            nothing = max(0.0, 1 - sum(weights))
            counts = d.counts(type_id, shift)
            self.cache[key] = (ids + [None], weights + [nothing], counts, d.jackpot_chances(type_id, shift))
        return self.cache[key]

    def roll(self, type_id, shift, rng):
        ids, weights, counts, jackpots = self.dist(type_id, shift)
        k = rng.choices([c for c, _ in counts], [p for _, p in counts])[0]
        picks = [i for i in rng.choices(ids, weights, k=k) if i is not None]
        picks += [i for i, c in jackpots.items() if rng.random() < c]
        return picks

    def rate(self, type_id, shift, rarity):
        """LootService.rate: how many single, non-ammo, non-jackpot items of `rarity` a roll holds on average."""
        key = (type_id, shift, rarity)
        if key not in self.rates:
            d = self.d
            if not d.finds(type_id, shift, rarity):
                self.rates[key] = 0.0
            else:
                per_item = sum(p for i, p in d.item_distribution(type_id, shift).items()
                               if d.items[i].rarity == rarity and d.items[i].stack == 1 and d.items[i].category != "AMMO")
                self.rates[key] = per_item * sum(k * p for k, p in d.counts(type_id, shift))
        return self.rates[key]


class Player:
    def __init__(self, settings):
        self.s = settings
        self.pity = {r: 0 for r in PITY}
        self.jackpots = {}
        self.quota = 0
        self.boost_raids = 0
        self.loss_since = -1
        self.raids = []  # newest first: dicts with net, kills, deaths, red, coop, qualified, tier, raid
        self.found_jackpots = set()  # jackpots found in the raid being played
        self.stats = {"handouts": {}, "handout_value": 0, "refund": 0, "income": 0, "boosted": 0,
                      "natural_value": 0, "red_payout_at": [], "raids": 0}

    def history(self):
        return [r for r in self.raids if r["qualified"]]

    def loss_score(self, raid_no, window):
        since = max(self.loss_since, raid_no - window)
        return sum(-r["net"] / self.s.thresholds.get(r["tier"], 200000) for r in self.raids if r["raid"] > since)


def pick(data, type_id, pool, rng):
    """LuckService.pick: a category by the container's weights (among those in the pool), then any item of it."""
    weights = {c.upper(): float(w) for c, w in (data.containers[type_id].get("categories") or {}).items()}
    by_cat = {}
    for it in pool:
        by_cat.setdefault(it.category, []).append(it)
    cats = list(by_cat)
    cat = rng.choices(cats, [max(0.0, weights.get(c, 0.0)) for c in cats])[0]
    return rng.choice(by_cat[cat])


def search(player, loot, data, type_id, shift, boosted, rng, no_red=False):
    """One container: (natural item ids, hand-out (id, source) or None). Mirrors LuckService.onSearch."""
    s = player.s
    items = loot.roll(type_id, shift, rng)
    if no_red:
        items = [i for i in items if data.items[i].rarity != "RED"]
    finds = {r: data.finds(type_id, shift, r) for r in PITY}
    finds = {r: f for r, f in finds.items() if f}
    for r in finds:
        player.pity[r] += 1
    choice = None
    if finds:
        if "RED" in finds:
            for j in data.jackpots:
                if rng.random() < s.jackpot_chance(player.jackpots.get(j, 0)):
                    choice = (j, "jackpot")
                    break
        if choice is None:
            for r in PITY:
                if r not in finds:
                    continue
                if any(data.items[i].rarity == r and data.items[i].category != "AMMO" for i in items):
                    continue
                if rng.random() < s.pity_chance(r, player.pity[r]):
                    pool = finds[r]
                    choice = (pick(data, type_id, pool, rng).id, "pity-" + r)
                    break
        if choice is None and boosted:
            for r in PITY:
                if r in finds and rng.random() < (s.boost[r] - 1) * loot.rate(type_id, shift, r):
                    choice = (pick(data, type_id, finds[r], rng).id, "boost-" + r)
                    break
        if choice is None and player.quota >= s.min_value and rng.random() < s.quota_chance:
            pool = [it for f in finds.values() for it in f if s.min_value <= it.value <= player.quota]
            if pool:
                choice = (pick(data, type_id, pool, rng).id, "quota")
    if choice:
        item = data.items[choice[0]]
        if choice[1] == "quota":
            player.quota -= item.value
        found(player, data, item)
    for i in items:  # the player searches the container out: every natural find counts
        found(player, data, data.items[i])
    return items, choice


def found(player, data, item):
    if item.category == "AMMO":
        return
    if item.rarity in player.pity:
        player.pity[item.rarity] = 0
    if item.id in data.jackpots:
        player.jackpots[item.id] = 0
        player.found_jackpots.add(item.id)


def play_raid(player, loot, data, tier_id, kind, args, rng, raid_no, no_red=False, no_jackpot=False):
    s = player.s
    tier = data.tiers[tier_id]
    extract_p, kills_p, coop_p = KINDS[kind]
    boosted = player.boost_raids > 0
    player.found_jackpots = set()
    values, handouts = [], []
    for type_id, bonus in rng.sample(data.map, args.containers):
        shift = data.shift(tier_id, bonus)
        items, choice = search(player, loot, data, type_id, shift, boosted, rng, no_red)
        if no_jackpot:
            items = [i for i in items if i not in data.jackpots]
        for i in items:
            values.append((data.mean_value(data.items[i]), i, ""))
        if choice:
            item = data.items[choice[0]]
            player.stats["handouts"][choice[1]] = player.stats["handouts"].get(choice[1], 0) + 1
            player.stats["handout_value"] += item.value
            if choice[1] == "pity-RED":
                player.stats["red_payout_at"].append(raid_no)
            values.append((item.value, choice[0], choice[1]))
    values.sort(reverse=True)
    carried = values[:args.cells]
    extracted = rng.random() < extract_p
    fee = tier.get("entry-fee", 0)
    kit = KITS.get(tier_id, 55000)
    natural = sum(v for v, _, source in carried if not source)
    player.stats["natural_value"] += natural if extracted else 0
    if extracted:
        net = sum(v for v, _, _ in carried) - fee
        red = any(data.items[i].rarity == "RED" for _, i, _ in carried)
    else:
        net = -kit - fee
        red = False
        for v, i, source in carried:
            if source and not source.startswith("boost-"):  # compensation hand-outs pay nothing back
                back = round(v * s.refund)
                player.quota = max(player.quota, min(s.cap, player.quota + back))
                player.stats["refund"] += back
    kills = sum(1 for _ in range(4) if rng.random() < kills_p / 4)
    coop = 1 if rng.random() < coop_p else 0
    qualified = args.minutes * 60 >= s.min_seconds and args.containers >= s.min_searches
    player.raids.insert(0, {"net": net, "kills": kills, "deaths": 0 if extracted else 1, "red": red, "coop": coop,
                            "qualified": qualified, "tier": tier_id, "raid": raid_no})
    del player.raids[200:]
    # Loss compensation (LuckService.compensate); the window is counted in raids here.
    if boosted:
        player.boost_raids -= 1
        player.stats["boosted"] += 1
    if player.boost_raids <= 0 and s.boost_raids > 0 and player.loss_score(raid_no, args.window) >= 1:
        player.boost_raids = s.boost_raids
        player.loss_since = raid_no
    if qualified:
        for j in data.jackpots:
            if j not in player.found_jackpots:
                player.jackpots[j] = player.jackpots.get(j, 0) + 1
        income = s.income(tier_id, tier, args.minutes, args.containers, s.multiplier(player.history()))
        player.quota = max(player.quota, min(s.cap, player.quota + income))
        player.stats["income"] += income
    player.stats["raids"] += 1


def report(args):
    data = ev.Data(ev.Source())
    s = Settings(data.config)
    loot = Loot(data)
    rng = random.Random(args.seed)
    print(f"luck: pity GOLD {s.pity['GOLD']}, RED {s.pity['RED']}; jackpots {', '.join(data.jackpots)} "
          f"(raids {s.jackpot_soft}/{s.jackpot_hard}); quota {money(s.per_minute)}/min + {money(s.per_search)}/search, "
          f"min item {money(s.min_value)}, chance {s.quota_chance}, cap {money(s.cap)}; compensation "
          f"{s.boost_raids} raids x{s.boost['GOLD']}/x{s.boost['RED']}")
    print(f"each player: {args.raids} raids of {args.containers} containers and {args.minutes} min, best {args.cells} "
          f"items carried out; {args.players} players per row; kit {', '.join(f'{k} {money(v)}' for k, v in KITS.items())}")
    print()
    print(f"{'tier':14} {'kind':8} {'loot/raid':>10} {'luck/raid':>10} {'luck %':>7} {'income':>8} {'mult':>5} "
          f"{'pityG/100':>9} {'pityR/100':>9} {'quota/100':>9} {'jack/100':>8} {'boosted':>7} {'refund':>8} {'net/raid':>9}")
    for tier_id in data.tiers:
        for kind in KINDS:
            totals = {"natural": 0, "luck": 0, "income": 0, "boosted": 0, "refund": 0, "net": 0,
                      "pity-GOLD": 0, "pity-RED": 0, "quota": 0, "jackpot": 0, "mult": []}
            for _ in range(args.players):
                p = Player(s)
                for n in range(args.raids):
                    play_raid(p, loot, data, tier_id, kind, args, rng, n)
                st = p.stats
                totals["natural"] += st["natural_value"]
                totals["luck"] += st["handout_value"]
                totals["income"] += st["income"]
                totals["boosted"] += st["boosted"]
                totals["refund"] += st["refund"]
                totals["net"] += sum(r["net"] for r in p.raids[:args.raids])
                for k in ("pity-GOLD", "pity-RED", "quota", "jackpot"):
                    totals[k] += st["handouts"].get(k, 0)
                totals["mult"].append(s.multiplier(p.history()))
            n = args.players * args.raids
            luck = totals["luck"] / n
            natural = totals["natural"] / n
            print(f"{data.tiers[tier_id].get('name', tier_id) + ' ' + tier_id:14} {kind:8} {money(natural):>10} {money(luck):>10} "
                  f"{luck / max(1, natural):>7.1%} {money(totals['income'] / n):>8} {statistics.mean(totals['mult']):>5.2f} "
                  f"{100 * totals['pity-GOLD'] / n:>9.2f} {100 * totals['pity-RED'] / n:>9.2f} {100 * totals['quota'] / n:>9.2f} "
                  f"{100 * totals['jackpot'] / n:>8.3f} {totals['boosted'] / n:>7.1%} {money(totals['refund'] / n):>8} "
                  f"{money(totals['net'] / n):>9}")
    print("(loot/raid = natural loot carried out, luck/raid = value handed out by pity, quota and jackpot pity, "
          "income = quota paid in, x/100 = hand-outs per 100 raids, boosted = share of raids with loss compensation, "
          "net/raid = carried out less kit and fee)")


def check(args):
    """Acceptance checks; returns the list of failures."""
    data = ev.Data(ev.Source())
    s = Settings(data.config)
    loot = Loot(data)
    rng = random.Random(args.seed)
    failures = []

    def ok(label, cond, detail=""):
        print(("PASS " if cond else "FAIL ") + label + (f" ({detail})" if detail else ""))
        if not cond:
            failures.append(label)

    # 1. A player who never finds a red on their own gets one by the hard-th eligible container.
    soft, hard = s.pity["RED"]
    worst = 0
    earliest = 10 ** 9
    resets = True
    for trial in range(200):
        p = Player(s)
        p.found_jackpots = set()
        p.jackpots = {j: 0 for j in data.jackpots}
        eligible = 0
        got = False
        for _ in range(hard * 3):
            type_id, bonus = rng.choice(data.map)
            shift = data.shift("NORMAL", bonus)
            if not data.finds(type_id, shift, "RED"):
                continue
            eligible += 1
            p.jackpots = {j: 0 for j in data.jackpots}  # keep jackpots out of this check
            _, choice = search(p, loot, data, type_id, shift, False, rng, no_red=True)
            if choice and data.items[choice[0]].rarity == "RED":
                got = True
                worst = max(worst, eligible)
                earliest = min(earliest, eligible)
                resets = resets and p.pity["RED"] == 0
                break
        if not got:
            worst = 10 ** 9
    ok(f"a player who never finds a red is handed one by eligible container {hard} (hard pity)", worst <= hard,
       f"latest at container {worst} of 200 players")
    ok(f"never before the soft mark ({soft})", earliest > soft, f"earliest at container {earliest}")
    ok("the red pity count is 0 right after the hand-out", resets)

    # 2. Jackpot pity: after hard-raids raids without it, the next raid hands it out, then the count restarts.
    p = Player(s)
    if data.jackpots:
        p.jackpots = {j: s.jackpot_hard - 1 for j in data.jackpots}
        before = dict(p.jackpots)
        play_raid(p, loot, data, "NORMAL", "average", args, rng, 0, no_jackpot=True)
        ok(f"after {s.jackpot_hard - 1} raids the count reaches hard-raids ({s.jackpot_hard})",
           all(p.jackpots[j] == before[j] + 1 or p.jackpots[j] == 0 for j in data.jackpots), str(p.jackpots))
        for j in data.jackpots:
            p.jackpots[j] = s.jackpot_hard
        p.stats["handouts"] = {}
        play_raid(p, loot, data, "NORMAL", "average", args, rng, 1, no_jackpot=True)
        got = p.stats["handouts"].get("jackpot", 0)
        ok(f"the raid after {s.jackpot_hard} raids without a jackpot hands one out", got >= 1, f"{got} handed out")
        zero = [j for j in data.jackpots if p.jackpots[j] == 0]
        ok("a handed-out jackpot's raid count restarts at 0 (it counts from the next raid)", len(zero) >= 1, str(p.jackpots))

    # 3. The quota never pays more than it holds.
    p = Player(s)
    p.quota = s.min_value
    spent_ok = True
    for _ in range(500):
        type_id, bonus = rng.choice(data.map)
        before = p.quota
        _, choice = search(p, loot, data, type_id, data.shift("NORMAL", bonus), False, rng)
        if choice and choice[1] == "quota":
            spent_ok = spent_ok and data.items[choice[0]].value <= before and p.quota >= 0
    ok("quota hand-outs never cost more than the pool holds", spent_ok)

    # 4. A loss over the threshold sets off the compensation; a compensated player finds about
    # multiplier times as many gold and red single items, the containers' own rolls unchanged.
    p = Player(s)
    t = s.thresholds["NORMAL"]
    p.raids = [{"net": -t, "kills": 0, "deaths": 1, "red": False, "coop": 0, "qualified": True, "tier": "NORMAL", "raid": 0}]
    ok("a loss of the threshold within the window sets off the compensation", p.loss_score(1, args.window) >= 1)
    single = lambda i: data.items[i].stack == 1 and data.items[i].category != "AMMO" and i not in data.jackpots  # noqa: E731
    finds = {False: {r: 0 for r in PITY}, True: {r: 0 for r in PITY}}
    rolled = {False: {r: 0 for r in PITY}, True: {r: 0 for r in PITY}}
    for boosted in (False, True):
        p = Player(s)
        p.pity = {r: -10 ** 9 for r in PITY}  # pity out of the way; the quota is empty
        for _ in range(40000):
            type_id, bonus = rng.choice(data.map)
            shift = data.shift("NORMAL", bonus)
            items, choice = search(p, loot, data, type_id, shift, boosted, rng)
            p.pity = {r: -10 ** 9 for r in PITY}
            for r in PITY:
                n = sum(1 for i in items if data.items[i].rarity == r and single(i))
                rolled[boosted][r] += n
                finds[boosted][r] += n + (1 if choice and data.items[choice[0]].rarity == r else 0)
    for r in PITY:
        ratio = finds[True][r] / max(1, finds[False][r])
        ok(f"a compensated player finds about x{s.boost[r]} the {r.lower()} items", abs(ratio - s.boost[r]) <= 0.2 * s.boost[r],
           f"{finds[True][r]} against {finds[False][r]} in 40000 searches, x{ratio:.2f}")
        rr = rolled[True][r] / max(1, rolled[False][r])
        ok(f"the containers' own {r.lower()} rolls stay the same", abs(rr - 1) <= 0.1, f"{rolled[True][r]} against {rolled[False][r]}, x{rr:.2f}")
    return failures


def main():
    parser = argparse.ArgumentParser(description="Personal loot luck simulator.")
    parser.add_argument("mode", nargs="?", default="report", choices=["report", "check"])
    parser.add_argument("--raids", type=int, default=300)
    parser.add_argument("--players", type=int, default=60)
    parser.add_argument("--containers", type=int, default=12)
    parser.add_argument("--minutes", type=float, default=18)
    parser.add_argument("--cells", type=int, default=14)
    parser.add_argument("--window", type=int, default=6, help="loss window in raids (24 hours of play)")
    parser.add_argument("--seed", type=int, default=80)
    args = parser.parse_args()
    if args.mode == "check":
        failures = check(args)
        if failures:
            sys.exit(1)
        print("luck checks pass")
    else:
        report(args)


if __name__ == "__main__":
    main()
