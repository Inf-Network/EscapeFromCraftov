package com.birditch.escapefromcraftov.game.plugin.integration;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.function.BooleanSupplier;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.util.Tristate;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/// Uses the LuckPerms cached permission API; console and not-yet-loaded users use Bukkit.
public final class LuckPermsPermissionProvider implements PermissionProvider {

  private final LuckPerms luckPerms;
  private final PermissionProvider fallback;

  public LuckPermsPermissionProvider(LuckPerms luckPerms, PermissionProvider fallback) {
    this.luckPerms = Preconditions.checkNotNull(luckPerms, "luckPerms");
    this.fallback = Preconditions.checkNotNull(fallback, "fallback");
  }

  @Override
  public boolean has(CommandSender sender, String permission) {
    if (!(sender instanceof Player player)) return fallback.has(sender, permission);
    var user = luckPerms.getUserManager().getUser(player.getUniqueId());
    if (user == null) return fallback.has(sender, permission);
    Tristate result = user.getCachedData().getPermissionData().checkPermission(permission);
    return resolve(result, () -> fallback.has(sender, permission));
  }

  static boolean resolve(Tristate result, BooleanSupplier fallback) {
    Preconditions.checkNotNull(result, "result");
    Preconditions.checkNotNull(fallback, "fallback");
    return switch (result) {
      case TRUE -> true;
      case FALSE -> false;
      case UNDEFINED -> fallback.getAsBoolean();
    };
  }
}
