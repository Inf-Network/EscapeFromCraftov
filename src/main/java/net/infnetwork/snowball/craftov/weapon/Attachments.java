package net.infnetwork.snowball.craftov.weapon;

import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemRegistry;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Attachment slots and what fitted parts do to a gun. */
public final class Attachments {

    /** Order matters: index + 1 is the custom_model_data string the item model reads (0 is the sight). */
    public static final List<String> SLOTS = List.of("muzzle", "grip", "tactical", "mag");

    private Attachments() {
    }

    public static String slotName(String slot) {
        return switch (slot) {
            case "muzzle" -> "枪口";
            case "grip" -> "握把";
            case "tactical" -> "战术位";
            case "mag" -> "弹匣";
            default -> slot;
        };
    }

    /** Which attachments a gun takes: pistols, the pump shotgun, or rifles (everything else). */
    public static String gunClass(GunSpec gun) {
        if (gun.slot() == WeaponSlot.SECONDARY) {
            return "pistol";
        }
        return "pump".equals(gun.action()) ? "shotgun" : "rifle";
    }

    public static boolean fits(GunSpec gun, AttachSpec part) {
        return part.mounts().contains(gunClass(gun));
    }

    /** The fitted parts that exist and fit, by slot. */
    public static Map<String, AttachSpec> resolve(GunSpec gun, Map<String, String> fitted, ItemRegistry registry) {
        Map<String, AttachSpec> out = new LinkedHashMap<>();
        for (String slot : SLOTS) {
            ItemDef def = registry.get(fitted.get(slot));
            if (def != null && def.attach() != null && def.attach().slot().equals(slot) && fits(gun, def.attach())) {
                out.put(slot, def.attach());
            }
        }
        return out;
    }

    /** The gun's stats with its attachments applied. */
    public static GunSpec effective(GunSpec gun, Map<String, AttachSpec> parts) {
        if (parts.isEmpty()) {
            return gun;
        }
        double recoil = 1, recoilH = 1, hip = 1, ads = 1, reload = 1, move = 1, mag = 1;
        for (AttachSpec part : parts.values()) {
            recoil *= part.recoil();
            recoilH *= part.recoilH();
            hip *= part.hipSpread();
            ads *= part.adsSpread();
            reload *= part.reload();
            move *= part.moveSpeed();
            mag *= part.magazine();
        }
        return new GunSpec(gun.caliber(), gun.slot(), (int) Math.ceil(gun.magazine() * mag), gun.rpm(), gun.modes(), gun.damage(),
                gun.range(), gun.maxRange(), gun.hipSpread() * hip, gun.adsSpread() * ads, gun.recoil() * recoil,
                gun.recoilH() * recoilH, (int) Math.round(gun.reloadTicks() * reload), gun.zoom(), gun.pellets(), gun.headMultiplier(),
                gun.sound(), gun.soundVolume(), gun.moveSpeed() * move, gun.mount(), gun.scope(), gun.audio(), gun.action());
    }

    public static boolean suppressed(Map<String, AttachSpec> parts) {
        return parts.values().stream().anyMatch(AttachSpec::suppressed);
    }

    public static @Nullable String light(Map<String, AttachSpec> parts) {
        AttachSpec tactical = parts.get("tactical");
        return tactical == null ? null : tactical.light();
    }

    /** One line describing what a part changes, for lore. */
    public static String effects(AttachSpec part) {
        StringBuilder out = new StringBuilder();
        effect(out, "后坐", part.recoil(), true);
        effect(out, "水平后坐", part.recoilH(), true);
        effect(out, "腰射扩散", part.hipSpread(), true);
        effect(out, "开镜扩散", part.adsSpread(), true);
        effect(out, "换弹时间", part.reload(), true);
        effect(out, "移动速度", part.moveSpeed(), false);
        effect(out, "弹匣容量", part.magazine(), false);
        if (part.suppressed()) {
            out.append(out.isEmpty() ? "" : " ").append("<#5BD16B>消音");
        }
        if (part.light() != null) {
            out.append(out.isEmpty() ? "" : " ").append("<#5BD16B>").append("strobe".equals(part.light()) ? "照明 / 爆闪" : "照明");
        }
        return out.toString();
    }

    private static void effect(StringBuilder out, String name, double mult, boolean lowerIsBetter) {
        if (Math.abs(mult - 1) < 0.005) {
            return;
        }
        int pct = (int) Math.round((mult - 1) * 100);
        boolean good = lowerIsBetter ? pct < 0 : pct > 0;
        out.append(out.isEmpty() ? "" : " ").append(good ? "<#5BD16B>" : "<#FF8A80>").append(name)
                .append(pct > 0 ? " +" : " ").append(pct).append('%');
    }
}
