package com.birditch.raidcore.game.plugin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Keeps every Brigadier value argument discoverable through client Tab completion. */
class CommandSuggestionBoundaryTest {

  @Test
  void everyGameAndLobbyArgumentHasASuggestionProvider() throws IOException {
    Path repository = Path.of("..").toAbsolutePath().normalize();
    for (Path root : List.of(
        repository.resolve("game/src/main/java/com/birditch/raidcore/game/plugin/command"),
        repository.resolve("lobby/src/main/java/com/birditch/raidcore/lobby/command"))) {
      int arguments = 0;
      try (var files = Files.walk(root)) {
        for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
          String source = Files.readString(file);
          int offset = 0;
          while ((offset = source.indexOf("Commands.argument(", offset)) >= 0) {
            arguments++;
            int openingParenthesis = offset + "Commands.argument".length();
            int closingParenthesis = matchingParenthesis(source, openingParenthesis);
            int chainedMethod = skipWhitespace(source, closingParenthesis + 1);
            int line = 1 + (int) source.substring(0, offset).chars()
                .filter(character -> character == '\n').count();
            assertTrue(source.startsWith(".suggests(", chainedMethod),
                file + ":" + line + " must attach suggestions directly to this argument");
            offset = closingParenthesis + 1;
          }
        }
      }
      assertTrue(arguments > 0, root + " must contain command arguments");
    }
  }

  private static int matchingParenthesis(String source, int openingParenthesis) {
    int depth = 0;
    boolean string = false;
    boolean character = false;
    boolean escaped = false;
    for (int index = openingParenthesis; index < source.length(); index++) {
      char current = source.charAt(index);
      if (escaped) {
        escaped = false;
        continue;
      }
      if ((string || character) && current == '\\') {
        escaped = true;
        continue;
      }
      if (!character && current == '"') {
        string = !string;
        continue;
      }
      if (!string && current == '\'') {
        character = !character;
        continue;
      }
      if (string || character) continue;
      if (current == '(') depth++;
      if (current == ')' && --depth == 0) return index;
    }
    throw new IllegalArgumentException("Unclosed Commands.argument call");
  }

  private static int skipWhitespace(String source, int offset) {
    while (offset < source.length() && Character.isWhitespace(source.charAt(offset))) offset++;
    return offset;
  }
}
