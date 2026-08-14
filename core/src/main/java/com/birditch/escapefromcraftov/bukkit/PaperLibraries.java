package com.birditch.escapefromcraftov.bukkit;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

/// Adds pinned Maven dependencies to a Paper plugin's isolated runtime
/// classpath. Paper verifies repository checksums and caches resolved artifacts
/// under the server's `libraries/` directory, so unchanged restarts do not
/// redownload them.
public final class PaperLibraries {

  private PaperLibraries() {}

  public static void addCentral(PluginClasspathBuilder classpathBuilder, String... coordinates) {
    Preconditions.checkNotNull(classpathBuilder, "classpathBuilder");
    Preconditions.checkNotNull(coordinates, "coordinates");
    MavenLibraryResolver resolver = new MavenLibraryResolver();
    resolver.addRepository(new RemoteRepository.Builder(
        "central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());
    for (String coordinate : coordinates) {
      Preconditions.checkNotNull(coordinate, "coordinate");
      resolver.addDependency(new Dependency(new DefaultArtifact(coordinate), null));
    }
    classpathBuilder.addLibrary(resolver);
  }
}
