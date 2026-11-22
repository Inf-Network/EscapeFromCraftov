#!/usr/bin/env python3
"""Economy balance report: the key tables of issue #22, from the files of one commit or two.

  python3 tools/econ/balance.py                        # this checkout
  python3 tools/econ/balance.py --before origin/main   # the same tables at another commit first

Sections (each mirrors the Java code it names, and follows the rules of the commit it reads):

  loot       per tier: entry fee, whole-map value and the median 10-box run (tools/econ/ev.py
             has the full tables and checks)
  market     what the trader and the market's system buyers pay (MarketService.virtualPrice /
             virtualChance, or the old anchor x 1.25 rule), and whether a listing at 1.0x value sells
  insurance  premium and expected cost of a death for two kits (InsuranceService)
  medical    what one kit treats and what a treatment costs (HealthService.treat)
  guns       damage per second against price (items.yml)
  load       sprint time, refill time and slowdown by carried weight (StaminaService)
  hideout    every workbench and med station recipe (HideoutService.FACILITIES): what its product
             would cost at the trader against what goes in, and whether selling the product beats
             selling the materials; intel centre income and payback

Exits 1 when a recipe's trader-price ratio is outside 1.3-1.6 or its product sells for more than
its money and materials would.
"""

import argparse
import math
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ev  # noqa: E402

RECIPE_MIN, RECIPE_MAX = 1.3, 1.6
HIDEOUT = "src/main/java/net/infnetwork/snowball/craftov/economy/HideoutService.java"


def money(x):
    return f"{round(x):,}"


def section(title):
    print()
    print(f"-- {title} " + "-" * max(0, 70 - len(title)))


class Report:
    def __init__(self, source):
        self.source = source
        self.data = ev.Data(source)
        self.items = self.data.items
        self.config = self.data.config
        self.raw_items = source.yaml("src/main/resources/items.yml")["items"]

    def cfg(self, path, default):
        node = self.config
        for key in path.split("."):
            if not isinstance(node, dict) or key not in node:
                return default
            node = node[key]
        return node

    # ------------------------------------------------------------------ loot

    def loot(self):
        section("loot (python3 tools/econ/ev.py for the full tables)")
        starter = self.data.starter_value()
        print(f"starter kit {money(starter)}, free kit {money(self.data.free_kit_value())}")
        print(f"{'tier':14} {'entry fee':>9} {'map EV':>11} {'median run':>11} {'x kit':>6} {'vault RED':>9}")
        for tier_id, t in self.data.tiers.items():
            runs = self.data.runs(tier_id, 10, 14, 4000, 22)
            med = runs[len(runs) // 2]
            red = self.data.p_rarity("vault", self.data.shift(tier_id, 2), "RED")
            print(f"{t.get('name', tier_id) + ' ' + tier_id:14} {money(t.get('entry-fee', 0)):>9} "
                  f"{money(self.data.map_ev(tier_id)):>11} {money(med):>11} {med / starter:>6.2f} {red:>9.1%}")

    # ------------------------------------------------------------------ market

    def market(self):
        section("market: trader and system buyers")
        sell = self.cfg("trader.sell-multiplier", 0.55)
        buy = self.cfg("trader.buy-multiplier", 1.15)
        keep = 1 - self.cfg("market.listing-fee", 0.02) - self.cfg("market.sale-tax", 0.05)
        new = self.cfg("market.virtual-max-value", None) is not None
        if new:
            share = self.cfg("market.virtual-max-value", 0.7)
            max_rarity = ev.RARITIES.index(str(self.cfg("market.virtual-max-rarity", "PURPLE")).upper())
            cap = self.cfg("market.virtual-daily-cap", 10)
            print(f"system buyers: at most {share} x value, nothing above {ev.RARITIES[max_rarity]}, "
                  f"{cap} listings per item and seller a day")
        else:
            anchor = self.cfg("market.reference-multiplier", 0.85)
            print(f"system buyers (old rule): up to 1.25 x the reference price ({anchor} x value), any rarity, "
                  f"{self.cfg('market.virtual-hourly-cap', 40)} listings per item and seller an hour")

        def chance(item, price):
            """Chance per 10 s round that a system buyer takes the listing at `price`."""
            if new:
                if ev.RARITIES.index(item.rarity) > max_rarity:
                    return 0.0
                top = item.value * share
                if price > top:
                    return 0.0
                r = price / top
                return 0.3 if r <= 0.85 else 0.12 if r <= 0.93 else 0.04
            r = price / (item.value * anchor)
            return 0.45 if r <= 0.9 else 0.22 if r <= 1.0 else 0.09 if r <= 1.1 else 0.025 if r <= 1.25 else 0.0

        print(f"{'item':14} {'rarity':6} {'value':>9} {'trader':>8} {'buyers top':>10} {'net':>8} {'net/val':>7}"
              f" {'sold in 10 min at 1.0x value':>29}")
        for item_id in ("laptop", "armor_4", "m4c", "gold_bar", "star_crystal"):
            item = self.items[item_id]
            if new:
                top = item.value * share if ev.RARITIES.index(item.rarity) <= max_rarity else 0
            else:
                top = item.value * anchor * 1.25
            p = chance(item, item.value)
            sold = 1 - (1 - p) ** 60  # 60 rounds of 10 s
            print(f"{item_id:14} {item.rarity:6} {money(item.value):>9} {money(item.value * sell):>8} {money(top):>10} "
                  f"{money(top * keep):>8} {top * keep / item.value:>7.2f} {sold:>29.1%}")
        print(f"(trader sells at {buy} x value; net = system buyers' top price after fee and tax)")

    # ------------------------------------------------------------------ insurance

    def insurance(self):
        section("insurance")
        rate = self.cfg("insurance.rate", 0.08)
        rarity_rate = self.cfg("insurance.rarity-rate", {}) or {}
        new = self.cfg("insurance.loss-chance", None) is not None
        loss = self.cfg("insurance.loss-chance", 0.3) if new else 0.0
        minutes = self.cfg("insurance.return-minutes", 30) if new else 0
        if new and not rarity_rate:
            rarity_rate = {"GOLD": 0.12, "RED": 0.16}
        print(f"premium {rate} ({', '.join(f'{k} {v}' for k, v in rarity_rate.items()) or 'every rarity alike'}), "
              f"loss chance {loss}, back after {minutes} min")
        kits = {
            "starter kit": ["k47", "p18", "pack_1", "rig_1", "armor_2", "helmet_2"],
            "armor_6 + helmet_6 + AWR": ["armor_6", "helmet_6", "awr"],
        }
        print(f"{'kit':26} {'value':>9} {'premium':>9} {'death, insured':>15} {'uninsured':>10} {'kept on death':>13}")
        for name, ids in kits.items():
            value = sum(self.items[i].value for i in ids)
            premium = sum(round(self.items[i].value * rarity_rate.get(self.items[i].rarity, rate)) for i in ids)
            lost = premium + loss * value
            print(f"{name:26} {money(value):>9} {money(premium):>9} {money(lost):>15} {money(value):>10} {1 - lost / value:>13.0%}")
        print("(death, insured = premium + the expected loss when nobody else carries the gear out)")

    # ------------------------------------------------------------------ medical

    def medical(self):
        section("medical")
        costs = self.cfg("medical.wound-cost", None)
        if costs is None:
            print("treating a wound costs nothing from a kit's pool (old rule)")
            costs = {"LIGHT_BLEED": 0, "HEAVY_BLEED": 0, "FRACTURE": 0}
        else:
            print("pool per wound treated: " + ", ".join(f"{k} {v}" for k, v in costs.items()))
        print(f"{'item':11} {'value':>7} {'pool':>5} {'heal/use':>8} {'wounds/use':>10} {'use s':>6}  what one kit treats at full health")
        for item_id, raw in self.raw_items.items():
            med = raw.get("med")
            if not med or not med.get("cures"):
                continue
            pool = med.get("pool", 0)
            count = med.get("count", 1)
            per_use = "1 part" if med.get("one-part") else str(count)
            if pool <= 0:
                treats = "one wound, then gone"
            else:
                cures = [c for c in med["cures"]]
                worst = max(cures, key=lambda c: costs.get(c, 0))
                cost = costs.get(worst, 0)
                treats = (f"{math.ceil(pool / cost)} x {worst}" if cost > 0
                          else ("every wound on the body, every use, never used up" if count >= 99
                                else f"{count} wound{'s' if count != 1 else ''} a use, never used up"))
            print(f"{item_id:11} {money(raw.get('value', 0)):>7} {pool:>5} {med.get('per-use', 0):>8} {per_use:>10} "
                  f"{med.get('use', 2.0):>6}  {treats}")

    # ------------------------------------------------------------------ guns

    def guns(self):
        section("guns")
        print(f"{'gun':6} {'rarity':6} {'value':>8} {'damage':>7} {'rpm':>4} {'DPS':>5} {'mag':>4} {'ADS':>5} {'move':>5} "
              f"{'kg':>4} {'value/DPS':>9}")
        for item_id, raw in self.raw_items.items():
            gun = raw.get("gun")
            if not gun:
                continue
            damage = gun.get("damage", 30) * gun.get("pellets", 1)
            dps = damage * gun.get("rpm", 600) / 60
            print(f"{item_id:6} {raw.get('rarity', 'WHITE'):6} {money(raw.get('value', 0)):>8} {damage:>7g} {gun.get('rpm'):>4} "
                  f"{dps:>5.0f} {gun.get('mag'):>4} {gun.get('ads-spread'):>5} {gun.get('move-speed'):>5} "
                  f"{raw.get('weight', 3.5):>4} {raw.get('value', 0) / dps:>9.0f}")

    # ------------------------------------------------------------------ load

    def load(self):
        section("load and stamina")
        st = self.cfg("stamina", None)
        if st is None:
            st = {"sprint-drain": 0.22, "regen": 0.6, "comfort-kg": 25, "overload-kg": None, "penalty-per-kg": 0.01,
                  "max-penalty": 0.25, "drain-divisor": 50}
            print("old rules: no overload limit, slowdown 1%/kg over 25 kg up to 25%")
        drain, regen = st.get("sprint-drain", 0.3), st.get("regen", 0.4)
        comfort, overload = st.get("comfort-kg", 25), st.get("overload-kg", 40)
        divisor = st.get("drain-divisor", 30)

        def penalty(w):
            if overload is None:
                return min(st["max-penalty"], max(0, w - comfort) * st["penalty-per-kg"])
            p = max(0, min(w, overload) - comfort) * st.get("penalty-per-kg", 0.01) \
                + max(0, w - overload) * st.get("overload-penalty-per-kg", 0.025)
            return min(st.get("max-penalty", 0.5), p)

        print(f"full bar refills in {0.75 + 100 / regen / 20:.1f} s")
        print(f"{'load kg':>7} {'slowdown':>8} {'sprint s':>8}")
        for w in (15, 25, 30, 40, 41, 45, 50, 57):
            can = overload is None or w <= overload
            factor = 1 + max(0, w - comfort) / divisor
            sprint = f"{100 / (drain * factor) / 20:.1f}" if can else "none"
            print(f"{w:>7} {penalty(w):>8.0%} {sprint:>8}")
        heavy = ["gold_bar", "lion_statue", "eel_generator", "gramophone", "gold_crown"]
        print("weights: " + ", ".join(f"{i} {self.items[i].weight if self.items[i].weight is not None else 'default'}"
                                       for i in heavy))

    # ------------------------------------------------------------------ hideout

    def hideout(self):
        section("hideout")
        code = self.source.text(HIDEOUT)
        buy = self.cfg("trader.buy-multiplier", 1.15)
        keep = 1 - self.cfg("market.listing-fee", 0.02) - self.cfg("market.sale-tax", 0.05)
        share = self.cfg("market.virtual-max-value", None)
        best_sell = max(self.cfg("trader.sell-multiplier", 0.55), (share if share is not None else 0.85 * 1.25) * keep)
        print(f"ratio = product at the trader's price ({buy} x value) / (money + materials at value); "
              f"wanted {RECIPE_MIN}-{RECIPE_MAX}")
        print(f"sell margin = (money + materials sold at {best_sell:.3f} x value) - product sold at the same; "
              "negative means crafting to sell pays")
        print(f"{'recipe':13} {'in: money':>9} {'+ materials':>11} {'product':>22} {'trader':>8} {'ratio':>6} {'sell margin':>11}")
        failures = []
        pattern = re.compile(r'new Recipe\("(\w+)", \d+, new Cost\(([\d_]+), items\(([^)]*)\)\), "(\w+)", (\d+), \d+\)')
        for rid, cash, mats, out, amount in pattern.findall(code):
            cash = int(cash.replace("_", ""))
            parts = [p.strip().strip('"') for p in mats.split(",") if p.strip()]
            material = sum(self.items[parts[i]].value * int(parts[i + 1]) for i in range(0, len(parts), 2))
            amount = int(amount)
            product = self.items[out].value * amount
            ratio = product * buy / (cash + material)
            margin = cash + material * best_sell - product * best_sell
            flag = "" if RECIPE_MIN <= ratio <= RECIPE_MAX and margin > 0 else "  <-"
            if flag:
                failures.append(rid)
            print(f"{rid:13} {money(cash):>9} {money(material):>11} {out + ' x' + str(amount):>22} {money(product * buy):>8} "
                  f"{ratio:>6.2f} {money(margin):>11}{flag}")
        rates = self.cfg("hideout.intel-income", None)
        if rates is None:
            m = re.search(r"INCOME = (?:\{([^}]*)\}|List\.of\(([^)]*)\))", code)
            rates = [int(x.strip().rstrip("L").replace("_", "")) for x in (m.group(1) or m.group(2)).split(",")]
        upgrades = self.intel_upgrades(code)
        print("intel centre: " + ", ".join(f"Lv.{lv} {money(r)}/h ({money(r * 24)}/day)" for lv, r in enumerate(rates) if lv))
        for lv, cost in enumerate(upgrades, start=1):
            gain = rates[min(lv, len(rates) - 1)] - rates[min(lv - 1, len(rates) - 1)]
            print(f"  upgrade to Lv.{lv}: {money(cost)} with materials at value, pays back in {cost / gain:.0f} h of income"
                  if gain > 0 else f"  upgrade to Lv.{lv}: {money(cost)}")
        return failures

    def intel_upgrades(self, code):
        block = code[code.index('new Facility("intel"'):]
        block = block[:block.index("List.of()))")]
        out = []
        for cash, mats in re.findall(r"new Cost\(([\d_]+), items\(([^)]*)\)\)", block):
            parts = [p.strip().strip('"') for p in mats.split(",") if p.strip()]
            out.append(int(cash.replace("_", "")) + sum(self.items[parts[i]].value * int(parts[i + 1])
                                                         for i in range(0, len(parts), 2)))
        return out

    def run(self):
        print(f"==== {self.source.label()} ====")
        self.loot()
        self.market()
        self.insurance()
        self.medical()
        self.guns()
        self.load()
        failures = self.hideout()
        print()
        return failures


def main():
    parser = argparse.ArgumentParser(description="Economy balance report.")
    parser.add_argument("--before", metavar="REF", help="also print the tables for this git commit first")
    args = parser.parse_args()
    if args.before:
        Report(ev.Source(args.before)).run()
    failures = Report(ev.Source()).run()
    if failures:
        print(f"FAIL: recipes outside {RECIPE_MIN}-{RECIPE_MAX} or worth more sold than their inputs: {', '.join(failures)}")
        sys.exit(1)
    print("hideout checks pass")


if __name__ == "__main__":
    main()
