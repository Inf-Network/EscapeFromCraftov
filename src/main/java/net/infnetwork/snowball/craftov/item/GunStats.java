package net.infnetwork.snowball.craftov.item;

import net.infnetwork.snowball.craftov.weapon.GunSpec;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The stat bars on a gun's tooltip and in the gunsmith: damage, rate of fire, hip-fire accuracy,
 * aimed accuracy, recoil, magazine and penetration, each against the whole armory so guns compare
 * at a glance. A longer bar is always better: the spreads and the recoil, where less is better, fill
 * the bar the smaller they are, on a log scale because they run from a few hundredths of a degree
 * to several degrees. Attachments show as the part of the bar they add (green) or take off (red).
 */
public final class GunStats {

    /** Segments in a full bar. */
    public static final int SEGMENTS = 10;
    /** The line under the bars: what they mean, in a sentence. */
    public static final String HINT = "<#6B7079>条越长越好 · 腰射看精准，开镜看开镜 · 度数越小越准，移动、跳跃会变大";

    private static final String FILL = "#F2C94C";
    private static final String GAIN = "#5BD16B";
    private static final String LOSS = "#FF4D4D";
    private static final String EMPTY = "#3A3F47";
    private static final String BAR = "▍";

    /** The armory's extremes each bar is measured against. */
    private record Scale(double damage, double rpm, double hipLo, double hipHi, double adsLo, double adsHi,
                         double recoilLo, double recoilHi, double magazine, double pen) {
    }

    private GunStats() {
    }

    /**
     * Worked out afresh each time: a hundred-odd items cost nothing, and a cached copy would outlive a
     * /cv reload that changed them.
     */
    private static Scale scale(ItemRegistry registry) {
        double damage = 1, rpm = 1, hipLo = Double.MAX_VALUE, hipHi = 0.01, adsLo = Double.MAX_VALUE, adsHi = 0.01;
        double recoilLo = Double.MAX_VALUE, recoilHi = 0.01, magazine = 1, pen = 1;
        for (ItemDef def : registry.all()) {
            GunSpec gun = def.gun();
            if (gun != null) {
                damage = Math.max(damage, gun.damage() * gun.pellets());
                rpm = Math.max(rpm, gun.rpm());
                hipLo = Math.min(hipLo, gun.hipSpread());
                hipHi = Math.max(hipHi, gun.hipSpread());
                adsLo = Math.min(adsLo, gun.adsSpread());
                adsHi = Math.max(adsHi, gun.adsSpread());
                recoilLo = Math.min(recoilLo, gun.recoil());
                recoilHi = Math.max(recoilHi, gun.recoil());
                magazine = Math.max(magazine, gun.magazine());
            }
            if (def.ammo() != null) {
                pen = Math.max(pen, def.ammo().penetration());
            }
        }
        return new Scale(damage, rpm, Math.min(hipLo, hipHi), hipHi, Math.min(adsLo, adsHi), adsHi,
                Math.min(recoilLo, recoilHi), recoilHi, magazine, pen);
    }

    /** One stat: how full its bar is for the gun as it comes and as fitted, and the number shown. */
    private record Stat(String label, double base, double now, double baseFill, double nowFill, String text, boolean lowerIsBetter) {
    }

    private static List<Stat> stats(ItemRegistry registry, GunSpec base, GunSpec now, @Nullable ItemDef ammo) {
        Scale s = scale(registry);
        List<Stat> out = new ArrayList<>();
        double baseDamage = base.damage() * base.pellets();
        double damage = now.damage() * now.pellets();
        out.add(new Stat("伤害", baseDamage, damage, up(baseDamage, s.damage()), up(damage, s.damage()),
                fmt(now.damage()) + (now.pellets() > 1 ? "×" + now.pellets() : ""), false));
        out.add(new Stat("射速", base.rpm(), now.rpm(), up(base.rpm(), s.rpm()), up(now.rpm(), s.rpm()), now.rpm() + "", false));
        out.add(new Stat("精准", base.hipSpread(), now.hipSpread(), down(base.hipSpread(), s.hipLo(), s.hipHi()),
                down(now.hipSpread(), s.hipLo(), s.hipHi()), deg(now.hipSpread()), true));
        out.add(new Stat("开镜", base.adsSpread(), now.adsSpread(), down(base.adsSpread(), s.adsLo(), s.adsHi()),
                down(now.adsSpread(), s.adsLo(), s.adsHi()), deg(now.adsSpread()), true));
        out.add(new Stat("后坐", base.recoil(), now.recoil(), down(base.recoil(), s.recoilLo(), s.recoilHi()),
                down(now.recoil(), s.recoilLo(), s.recoilHi()), deg(now.recoil()), true));
        out.add(new Stat("弹匣", base.magazine(), now.magazine(), up(base.magazine(), s.magazine()), up(now.magazine(), s.magazine()),
                now.magazine() + "", false));
        int pen = ammo == null || ammo.ammo() == null ? 0 : ammo.ammo().penetration();
        out.add(new Stat("穿透", pen, pen, up(pen, s.pen()), up(pen, s.pen()), pen > 0 ? pen + " 级" : "-", false));
        return out;
    }

    /**
     * The seven bar lines of a gun's tooltip. {@code base} is the gun as it comes, {@code now} with
     * its attachments; {@code ammo} is the round its penetration is shown for (the loaded one).
     */
    public static List<String> bars(ItemRegistry registry, GunSpec base, GunSpec now, @Nullable ItemDef ammo) {
        List<String> out = new ArrayList<>();
        for (Stat stat : stats(registry, base, now, ammo)) {
            out.add(line(stat, stat.baseFill(), stat.nowFill(), stat.base(), stat.now()));
        }
        return out;
    }

    /**
     * The bars a change of attachments would move, from {@code before} to {@code after}, for the
     * gunsmith's parts: only the ones that change, with what changes coloured.
     */
    public static List<String> changes(ItemRegistry registry, GunSpec before, GunSpec after, @Nullable ItemDef ammo) {
        List<String> out = new ArrayList<>();
        List<Stat> was = stats(registry, before, before, ammo);
        List<Stat> will = stats(registry, after, after, ammo);
        for (int i = 0; i < was.size(); i++) {
            Stat a = was.get(i);
            Stat b = will.get(i);
            if (Math.abs(a.now() - b.now()) > 1e-9) {
                out.add(line(new Stat(b.label(), a.now(), b.now(), a.nowFill(), b.nowFill(), b.text(), b.lowerIsBetter()),
                        a.nowFill(), b.nowFill(), a.now(), b.now()));
            }
        }
        return out;
    }

    private static String line(Stat stat, double baseFill, double nowFill, double base, double now) {
        int had = segments(baseFill);
        int has = segments(nowFill);
        StringBuilder bar = new StringBuilder();
        String color = null;
        for (int i = 0; i < SEGMENTS; i++) {
            String c = i < Math.min(had, has) ? FILL : i < has ? GAIN : i < had ? LOSS : EMPTY;
            if (!c.equals(color)) {
                bar.append('<').append(c).append('>');
                color = c;
            }
            bar.append(BAR);
        }
        boolean changed = Math.abs(base - now) > 1e-9;
        boolean better = stat.lowerIsBetter() ? now < base : now > base;
        String number = changed ? (better ? "<" + GAIN + ">" : "<" + LOSS + ">") : "<white>";
        return "<#8A8F98>" + stat.label() + " " + bar + " " + number + stat.text();
    }

    private static int segments(double fill) {
        return Math.max(0, Math.min(SEGMENTS, (int) Math.round(fill * SEGMENTS)));
    }

    /** More is better: the share of the armory's best, never less than a sliver for anything at all. */
    private static double up(double value, double max) {
        return value <= 0 ? 0 : Math.max(0.05, Math.min(1, value / Math.max(1e-9, max)));
    }

    /**
     * Less is better, on a log scale from a little under the armory's best (a full bar) to a little
     * over its worst (a sliver: no gun's bar is quite empty).
     */
    private static double down(double value, double lo, double hi) {
        double top = Math.log(Math.max(1e-6, hi * 1.25));
        double bottom = Math.log(Math.max(1e-6, lo * 0.8));
        double fill = (top - Math.log(Math.max(1e-6, value))) / Math.max(1e-9, top - bottom);
        return Math.max(0.05, Math.min(1, fill));
    }

    private static String deg(double value) {
        return String.format(Locale.ROOT, value < 1 ? "%.2f" : "%.1f", value) + "°";
    }

    private static String fmt(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }
}
