#!/usr/bin/env python3
"""Checks that repairing armour never pays.

For every helmet and body armour in items.yml, and every durability from broken to one point
short of full, compares what the trader charges to repair it (TraderService.repairCost) with
what the repair adds to what the piece sells for:

  trader  the trader's price (ItemFactory.value x trader.sell-multiplier)
  market  the most the market's system buyers pay: the reference price x 1.25 (the highest price
          ratio they accept), less market.listing-fee and market.sale-tax

The reference price is item value x market.reference-multiplier, which holds while the market
keeps it anchored to item value (the market price fixes of issue #13). On a build where every sale,
system buyers' included, pulls the reference up, the margin shrinks as armour keeps selling; pass
the server's market.json to check against the prices it has learned:

  python3 tools/econ/repair.py [items.yml] [config.yml] [--market plugins/Craftov/data/market.json]

The arithmetic mirrors the Java code, including Math.round. Exits 1 if any repair is profitable.
"""

import argparse
import json
import math
from pathlib import Path
import sys

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
    parser = argparse.ArgumentParser(description="Checks that repairing armour never pays.")
    parser.add_argument("items", nargs="?", default=ROOT / "src/main/resources/items.yml", type=Path)
    parser.add_argument("config", nargs="?", default=ROOT / "src/main/resources/config.yml", type=Path)
    parser.add_argument("--market", type=Path, help="market.json whose learned reference prices to use")
    args = parser.parse_args()
    items = yaml.safe_load(args.items.read_text(encoding="utf-8"))["items"]
    config = yaml.safe_load(args.config.read_text(encoding="utf-8"))
    learned = {}
    if args.market:
        learned = json.loads(args.market.read_text(encoding="utf-8")).get("reference") or {}
    trader = config.get("trader", {})
    market = config.get("market", {})
    rate = trader.get("repair-rate", 0.75)
    sell = trader.get("sell-multiplier", 0.55)
    reference = market.get("reference-multiplier", 0.85)
    keep = 1 - market.get("listing-fee", 0.02) - market.get("sale-tax", 0.05)

    source = f"learned prices from {args.market}" if args.market else f"{reference} x value"
    print(f"repair-rate {rate}  trader sells at {sell}  market at most reference ({source}) x {VIRTUAL_MAX_RATIO} x {keep:.2f}")
    print(f"{'item':10} {'value':>8} {'repair':>8} {'trader+':>8} {'market+':>8} {'margin':>8}")
    failures = 0
    for item_id, defn in items.items():
        if defn.get("category") not in ("HELMET", "ARMOR") or not defn.get("durability"):
            continue
        full = defn["durability"]
        # MarketService.reference: the learned price if there is one, else value x multiplier;
        # referenceFor scales it by the worn piece's share of the full value.
        ratio = learned.get(item_id, defn["value"] * reference) / defn["value"]
        worst = None
        steps = int(full * 10)
        for step in range(steps):
            dura = step / 10
            missing = full - dura
            cost = jround(missing / full * defn["value"] * rate)
            before, after = value(defn, dura), value(defn, full)
            trader_gain = jround(after * sell) - jround(before * sell)
            market_gain = (after - before) * ratio * VIRTUAL_MAX_RATIO * keep
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
