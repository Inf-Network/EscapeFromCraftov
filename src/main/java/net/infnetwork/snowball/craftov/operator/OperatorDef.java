package net.infnetwork.snowball.craftov.operator;

import java.util.List;

/** Playable operator: one active skill plus passives. */
public record OperatorDef(String id, String name, String role, String color, String skillName, int cooldownSeconds,
                          List<String> description, String icon) {
}
