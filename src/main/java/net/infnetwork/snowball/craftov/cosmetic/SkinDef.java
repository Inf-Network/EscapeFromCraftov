package net.infnetwork.snowball.craftov.cosmetic;

/**
 * One knife skin from skins.yml: the knife (item id) it is for, its two-character name, a line of
 * lore and its default price in 点券 (config.yml skins.prices.&lt;id&gt; overrides it).
 */
public record SkinDef(String id, String knife, String name, String lore, long price) {

    /** The item definition the resource pack has for it (tools/art/build.py, build_knife_skins). */
    public String model() {
        return SkinService.MODEL_PREFIX + id;
    }
}
