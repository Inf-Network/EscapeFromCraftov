package net.infnetwork.snowball.craftov.weapon;

public record ThrowableSpec(Type type, int fuseTicks, double radius, double damage) {
    public enum Type {
        FRAG, SMOKE, FLASH
    }
}
