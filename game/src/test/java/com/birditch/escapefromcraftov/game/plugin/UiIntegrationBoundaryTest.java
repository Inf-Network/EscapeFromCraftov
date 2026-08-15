package com.birditch.escapefromcraftov.game.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Prevents native GUI or hologram implementations from bypassing required plugins. */
class UiIntegrationBoundaryTest {

  private static final List<String> FORBIDDEN_GUI_TOKENS = List.of(
      ".createInventory(",
      ".openInventory(",
      ".closeInventory(",
      "org.bukkit.inventory.InventoryHolder");
  private static final List<String> FORBIDDEN_HOLOGRAM_TOKENS = List.of(
      "org.bukkit.entity.TextDisplay",
      "org.bukkit.entity.ArmorStand");

  @Test
  void productionCodeCannotCreateANativeInventoryMenu() throws IOException {
    for (Path source : productionSources()) {
      String java = Files.readString(source);
      for (String token : FORBIDDEN_GUI_TOKENS) {
        assertFalse(java.contains(token), source + " bypasses TrMenu with " + token);
      }
    }
  }

  @Test
  void productionCodeCannotCreateANativeEntityHologram() throws IOException {
    for (Path source : productionSources()) {
      String java = Files.readString(source);
      for (String token : FORBIDDEN_HOLOGRAM_TOKENS) {
        assertFalse(java.contains(token), source + " bypasses DecentHolograms with " + token);
      }
    }
  }

  @Test
  void allDecentHologramsCallsStayInsideTheAdapter() throws IOException {
    List<Path> callers = productionSources().stream().filter(source -> {
      try {
        return Files.readString(source).contains("DHAPI.");
      } catch (IOException error) {
        throw new java.io.UncheckedIOException(error);
      }
    }).toList();

    assertFalse(callers.isEmpty(), "DecentHolograms adapter must use DHAPI");
    assertTrue(callers.stream().allMatch(source ->
        source.getFileName().toString().equals("DecentHologramsProvider.java")), callers.toString());
  }

  @Test
  void personalHologramsCannotBroadcastTheirInitialLines() throws IOException {
    Path provider = Path.of("src/main/java/com/birditch/escapefromcraftov/game/plugin/integration/"
        + "DecentHologramsProvider.java");
    String java = Files.readString(provider);

    int createEmpty = java.indexOf(
        "DHAPI.createHologram(name, renderedLocation, false, List.of())");
    int defaultHidden = java.indexOf("hologram.setDefaultVisibleState(false)", createEmpty);
    int allowPlayer = java.indexOf("hologram.setShowPlayer(player)", defaultHidden);
    int addLines = java.indexOf("DHAPI.setHologramLines(hologram, renderedLines)", allowPlayer);
    int showPlayer = java.indexOf("hologram.show(player, 0)", addLines);

    assertTrue(createEmpty >= 0, "hologram must start with an empty page");
    assertTrue(defaultHidden > createEmpty, "default visibility must be disabled before lines exist");
    assertTrue(allowPlayer > defaultHidden, "the editor must be explicitly allowlisted");
    assertTrue(addLines > allowPlayer, "lines must be installed only after visibility is private");
    assertTrue(showPlayer > addLines, "only the allowlisted editor may be shown the label");
  }

  @Test
  void decentHologramsIsTheOnlyHologramProviderImplementation() throws IOException {
    List<Path> implementations = productionSources().stream().filter(source -> {
      try {
        return Files.readString(source).contains("implements HologramProvider");
      } catch (IOException error) {
        throw new java.io.UncheckedIOException(error);
      }
    }).toList();

    assertTrue(implementations.size() == 1, implementations.toString());
    assertTrue(implementations.getFirst().getFileName().toString()
        .equals("DecentHologramsProvider.java"), implementations.toString());
  }

  @Test
  void trMenuBridgeUsesTheCompleteProviderLifecycleWithoutABukkitFallback() throws IOException {
    Path bridge = Path.of("..").toAbsolutePath().normalize()
        .resolve("core/src/main/java/com/birditch/escapefromcraftov/bukkit/TrMenuBridge.java");
    String java = Files.readString(bridge);

    assertTrue(java.contains("getMethod(\"close\", boolean.class, boolean.class)"));
    assertTrue(java.contains("getMethod(\"shut\")"));
    assertTrue(java.contains("getMethod(\"getIcons\")"));
    assertTrue(java.contains("getMethod(\"getActiveIcons\")"));
    assertTrue(java.contains("getMethod(\"setArguments\", String[].class)"));
    assertTrue(java.contains("getMethod(\"onReset\", sessionType)"));
    assertTrue(java.contains("getMethod(\"onRefresh\", sessionType)"));
    assertTrue(java.contains("SUPPORTED_VERSION = \"3.12.5\""));
    assertTrue(java.contains("trplugins.menu.api.TrMenuAPI"));
    assertTrue(java.contains("trplugins.menu.api.event.CustomItemSourceEvent"));
    assertTrue(java.contains("trplugins.menu.module.display.MenuSession"));
    assertFalse(java.contains("player.closeInventory()"));

    int refreshStart = java.indexOf("public boolean refresh(Player player");
    int refreshEnd = java.indexOf("static void refreshIconCaches", refreshStart);
    String refresh = java.substring(refreshStart, refreshEnd);
    assertTrue(refresh.contains("refreshIconCaches("));
    assertTrue(refresh.contains("return true"));
    assertFalse(refresh.contains("return open("));
    assertFalse(refresh.contains("dispatchCommand"));
  }

  @Test
  void paperDescriptorsDeclareTheUiProvidersAsHardDependencies() throws IOException {
    Path repository = Path.of("..").toAbsolutePath().normalize();
    String game = Files.readString(repository.resolve("game/src/main/resources/paper-plugin.yml"));
    String lobby = Files.readString(repository.resolve("lobby/src/main/resources/paper-plugin.yml"));

    assertHardDependency(game, "TrMenu");
    assertHardDependency(game, "DecentHolograms");
    assertHardDependency(lobby, "TrMenu");
  }

  private static void assertHardDependency(String descriptor, String plugin) {
    Pattern declaration = Pattern.compile("(?ms)^    " + Pattern.quote(plugin)
        + ":\\R(?:      .+\\R)*?      required: true$");
    assertTrue(declaration.matcher(descriptor).find(), plugin + " must be a hard dependency");
  }

  private static List<Path> productionSources() throws IOException {
    Path repository = Path.of("..").toAbsolutePath().normalize();
    List<Path> roots = List.of(
        repository.resolve("core/src/main/java"),
        repository.resolve("game/src/main/java"),
        repository.resolve("lobby/src/main/java"));
    try (var paths = roots.stream().flatMap(root -> {
      try {
        return Files.walk(root);
      } catch (IOException error) {
        throw new java.io.UncheckedIOException(error);
      }
    })) {
      return paths.filter(path -> path.toString().endsWith(".java")).toList();
    }
  }
}
