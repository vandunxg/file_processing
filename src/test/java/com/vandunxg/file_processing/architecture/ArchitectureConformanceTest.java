package com.vandunxg.file_processing.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Guards the layering the refactor established, so it cannot quietly erode.
 *
 * <p>Scans the source tree rather than the classpath: the rules are about where code lives and what
 * it names, which is exactly what a package layout expresses, and it needs no extra dependency.
 */
class ArchitectureConformanceTest {

  private static final Path SOURCE_ROOT = Path.of("src/main/java/com/vandunxg/file_processing");

  /** Layers of the module being refactored, plus the context extracted out of it. */
  private static final List<String> DDD_MODULES = List.of("fileimport", "customer");

  @Test
  void noHexagonalPackageSurvives() {
    for (String module : DDD_MODULES) {
      assertThat(SOURCE_ROOT.resolve(module).resolve("adapter"))
          .as("%s must not have an adapter package", module)
          .doesNotExist();
      assertThat(SOURCE_ROOT.resolve(module).resolve("application/port"))
          .as("%s must not have an application port package", module)
          .doesNotExist();
    }
  }

  @Test
  void noTypeIsNamedAfterHexagonalCeremony() {
    List<String> ceremony =
        List.of(
            "UseCase.java", "RepositoryPort.java", "PersistenceAdapter.java", "StoragePort.java");
    List<Path> offenders =
        walk(SOURCE_ROOT).stream()
            .filter(file -> ceremony.stream().anyMatch(file.getFileName().toString()::endsWith))
            .toList();

    assertThat(offenders).as("port/adapter/use-case naming must not come back").isEmpty();
  }

  @Test
  void aDomainNeverDependsOnAnOuterLayer() {
    forEachSourceIn(
        "domain",
        (file, imports) ->
            assertThat(imports)
                .as("%s is in a domain and must not reach outwards", file)
                .noneMatch(
                    line ->
                        line.matches("^import com\\.vandunxg\\.file_processing\\.\\w+\\.api\\..*")
                            || line.matches(
                                "^import com\\.vandunxg\\.file_processing\\.\\w+\\.application\\..*")
                            || line.matches(
                                "^import com\\.vandunxg\\.file_processing\\.\\w+\\.infrastructure\\..*")));
  }

  /**
   * Spring Data, JDBC, the object-storage SDK and the Redis client describe how data is fetched,
   * which is infrastructure's decision. JPA mapping is covered separately by {@link
   * #aDomainCarriesNoPersistenceMapping()}, because it is a rule about the model's shape rather
   * than about who fetches it.
   */
  @Test
  void anApplicationOrDomainNeverDependsOnAPersistenceOrStorageFramework() {
    List<String> forbidden =
        List.of(
            "org.springframework.data.",
            "org.springframework.jdbc.",
            "software.amazon.awssdk.",
            "com.amazonaws.",
            "redis.clients.");
    for (String layer : List.of("application", "domain")) {
      forEachSourceIn(
          layer,
          (file, imports) ->
              assertThat(imports)
                  .as("%s must not choose a persistence or storage technology", file)
                  .noneMatch(line -> forbidden.stream().anyMatch(line::contains)));
    }
  }

  @Test
  void noModuleReachesIntoAnotherModulesInfrastructure() {
    for (String module : DDD_MODULES) {
      List<String> others =
          Stream.of("fileimport", "customer", "auth")
              .filter(other -> !other.equals(module))
              .map(other -> "import com.vandunxg.file_processing." + other + ".infrastructure")
              .toList();
      walk(SOURCE_ROOT.resolve(module))
          .forEach(
              file ->
                  assertThat(importsOf(file))
                      .as("%s must not reach into another module's infrastructure", file)
                      .noneMatch(line -> others.stream().anyMatch(line::startsWith)));
    }
  }

  /**
   * The api layer of a refactored module stays off persistence, with one exception it may not
   * exceed.
   *
   * <p>{@code @ValidatePaging} builds its sort allow-list by reflecting over {@code @Column}
   * fields, so a controller has to name an entity to accept a {@code sortBy} at all: a domain model
   * carries no such fields and would produce an empty allow-list that rejects every sort. The
   * exception is therefore the annotation's argument and nothing else -- the entity may not be
   * imported for any other purpose, mapped, or returned. This is the pattern auth's controllers
   * already follow.
   */
  @Test
  void theApiLayerOfARefactoredModuleTouchesInfrastructureOnlyAsAPagingSortModel() {
    for (String module : DDD_MODULES) {
      String infrastructure = "import com.vandunxg.file_processing." + module + ".infrastructure";
      String entities = infrastructure + ".persistence.entity.";
      walk(SOURCE_ROOT.resolve(module).resolve("api"))
          .forEach(
              file -> {
                List<String> reached =
                    importsOf(file).stream()
                        .filter(line -> line.startsWith(infrastructure))
                        .toList();
                assertThat(reached)
                    .as("%s may reach infrastructure only for a paging sort model", file)
                    .allMatch(line -> line.startsWith(entities));
                reached.forEach(
                    line ->
                        assertThat(mentionsOnlyAsSortModel(file, simpleNameOf(line)))
                            .as("%s must name %s only as a @ValidatePaging sort model", file, line)
                            .isTrue());
              });
    }
  }

  private static String simpleNameOf(String importLine) {
    String qualified = importLine.substring("import ".length(), importLine.length() - 1);
    return qualified.substring(qualified.lastIndexOf('.') + 1);
  }

  private static boolean mentionsOnlyAsSortModel(Path file, String type) {
    return linesOf(file).stream()
        .filter(line -> !line.startsWith("import "))
        .filter(line -> line.contains(type))
        .allMatch(line -> line.contains("sortModel = " + type + ".class"));
  }

  /**
   * A domain model carries no persistence mapping.
   *
   * <p>The mapping describes the row, not the business rule. An aggregate that names its own
   * columns cannot be reshaped without a migration, cannot be unit-tested without a persistence
   * provider, and quietly invites the table's shape to dictate the model's. {@code RULE.md §6.4}
   * makes the separation mandatory; this is the executable form of that rule, because a rule kept
   * only in prose erodes.
   */
  @Test
  void aDomainCarriesNoPersistenceMapping() {
    forEachSourceIn(
        "domain",
        (file, imports) ->
            assertThat(imports)
                .as("%s is in a domain and must not carry persistence mapping", file)
                .noneMatch(
                    line ->
                        line.startsWith("import jakarta.persistence.")
                            || line.startsWith("import org.hibernate.")));
  }

  private interface SourceCheck {
    void check(Path file, List<String> imports);
  }

  private static void forEachSourceIn(String layer, SourceCheck check) {
    for (String module : DDD_MODULES) {
      List<Path> files = walk(SOURCE_ROOT.resolve(module).resolve(layer));
      // A rule that scanned nothing would pass for the wrong reason.
      assertThat(files).as("%s/%s must contain sources to check", module, layer).isNotEmpty();
      files.forEach(file -> check.check(file, importsOf(file)));
    }
  }

  private static List<Path> walk(Path root) {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(path -> path.toString().endsWith(".java")).toList();
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static List<String> importsOf(Path file) {
    return linesOf(file).stream().filter(line -> line.startsWith("import ")).toList();
  }

  private static List<String> linesOf(Path file) {
    try {
      return Files.readAllLines(file);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
