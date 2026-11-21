#!/usr/bin/env python3
"""Checks that repairing armour never pays.

For every helmet and body armour in items.yml, and every durability from broken to one point
short of full, compares what the trader charges to repair it (TraderService.repairCost) with
what the repair adds to what the piece sells for:

  trader  the trader's price (ItemFactory.value x trader.sell-multiplier)
  market  the most the market's system buyers pay: item value x market.reference-multiplier x 1.25
          (the highest price ratio they accept), less market.listing-fee and market.sale-tax

The arithmetic mirrors the Java code, including Math.round. Exits 1 if any repair is profitable.

  python3 tools/econ/repair.py [items.yml] [config.yml]
"""

import math
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
# MarketService.tick: system buyers never take a listing priced above 1.25x the reference price.
VIRTUAL_MAX_RATIO = 1.25


def jround(x):
    """Java's Math.round: half up."""
    return math.floor(x + 0.5)


def value(defn, dura):
    """ItemFactory.value for one armour piece at `dura`."""
    fraction = max(0.0, min(1.0, dura / defn["durability"]))
    return jround(defn["value"] * (0.25 + 0.75 * fraction))


def main():
    items_path = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "src/main/resources/items.yml"
    config_path = Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "src/main/resources/config.yml"
    items = yaml.safe_load(items_path.read_text(encoding="utf-8"))["items"]
    config = yaml.safe_load(config_path.read_text(encoding="utf-8"))
    trader = config.get("trader", {})
    market = config.get("market", {})
    rate = trader.get("repair-rate", 0.75)
    sell = trader.get("sell-multiplier", 0.55)
    reference = market.get("reference-multiplier", 0.85)
    keep = 1 - market.get("listing-fee", 0.02) - market.get("sale-tax", 0.05)

    print(f"repair-rate {rate}  trader sells at {sell}  market at most {reference} x {VIRTUAL_MAX_RATIO} x {keep:.2f}")
    print(f"{'item':10} {'value':>8} {'repair':>8} {'trader+':>8} {'market+':>8} {'margin':>8}")
    failures = 0
    for item_id, defn in items.items():
        if defn.get("category") not in ("HELMET", "ARMOR") or not defn.get("durability"):
            continue
        full = defn["durability"]
        worst = None
        steps = int(full * 10)
        for step in range(steps):
            dura = step / 10
            missing = full - dura
            cost = jround(missing / full * defn["value"] * rate)
            before, after = value(defn, dura), value(defn, full)
            trader_gain = jround(after * sell) - jround(before * sell)
            market_gain = (after - before) * reference * VIRTUAL_MAX_RATIO * keep
            margin = cost - max(trader_gain, market_gain)
            if margin < 0:
                failures += 1
            if worst is None or margin < worst[-1]:
                worst = (dura, cost, trader_gain, market_gain, margin)
        dura, cost, trader_gain, market_gain, margin = worst
        flag = "" if margin >= 0 else "  PROFITABLE"
        print(f"{item_id:10} {defn['value']:>8} {cost:>8} {trader_gain:>8} {market_gain:>8.0f} {margin:>8.0f}"
              f"  (worst at durability {dura:g}/{full:g}){flag}")
    if failures:
        print(f"{failures} repairs pay more than they cost")
        sys.exit(1)
    print("no repair pays")


if __name__ == "__main__":
    main()
