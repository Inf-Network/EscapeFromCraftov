package com.birditch.escapefromcraftov.game.plugin.network;

import java.util.List;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

/** Normalizes mutable Bukkit state inherited from the lobby/editor before a raid. */
final class RaidEntryState {

  private static final int FULL_FOOD = 20;
  private static final float DEFAULT_SATURATION = 5.0F;

  private RaidEntryState() {
  }

  static void reset(Player player) {
    prepare(player);
    AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
    resetVitalsAndMotion(player, maxHealth == null ? 20.0D : maxHealth.getValue());
  }

  static void reset(Player player, double maxHealth) {
    prepare(player);
    resetVitalsAndMotion(player, maxHealth);
  }

  private static void prepare(Player player) {
    player.setGameMode(GameMode.SURVIVAL);
    player.setFlying(false);
    player.setAllowFlight(false);

    for (PotionEffect effect : List.copyOf(player.getActivePotionEffects())) {
      player.removePotionEffect(effect.getType());
    }
  }

  private static void resetVitalsAndMotion(Player player, double maxHealth) {
    player.setHealth(Math.max(1.0D, maxHealth));
    player.setFoodLevel(FULL_FOOD);
    player.setSaturation(DEFAULT_SATURATION);
    player.setExhaustion(0.0F);
    player.setAbsorptionAmount(0.0D);

    player.setFireTicks(0);
    player.setFreezeTicks(0);
    player.setFallDistance(0.0F);
    player.setNoDamageTicks(0);
    player.setRemainingAir(player.getMaximumAir());
    player.setArrowsInBody(0);
    player.setBeeStingersInBody(0);

    player.setGliding(false);
    player.setSprinting(false);
    player.setSneaking(false);
    player.setVelocity(new Vector());
  }
}
