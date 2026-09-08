package com.vandunxg.file_processing.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The import must stay bounded in memory whatever the file size, so the code must never reach for
 * an API that materialises a whole object.
 *
 * <p>A single {@code readAllBytes} on a 500 MB original defeats every other precaution -- the
 * streaming reader, the batch size, the temp-file report -- and it fails at the worst moment, in
 * production, on the largest customer. This catches it at build time instead.
 */
class StreamingDisciplineTest {

  private static final List<Path> STREAMING_ROOTS =
      List.of(
          Path.of("src/main/java/com/vandunxg/file_processing/fileimport"),
          Path.of("src/main/java/com/vandunxg/file_processing/customer"));

  /**
   * Calls that read an entire stream, file or upload into one array or list.
   *
   * <p>{@code transferTo} is absent on purpose: it copies through a fixed-size buffer, which is the
   * behaviour wanted when the report is streamed to the client.
   */
  private static final List<String> WHOLE_OBJECT_CALLS =
      List.of(
          "readAllBytes(",
          "readAllLines(",
          "Files.readString(",
          "Files.lines(",
          "toByteArray(",
          "copyToByteArray(",
          "copyToString(",
          "IOUtils.",
          "FileCopyUtils.");

  @Test
  void noImportCodePathLoadsAWholeObjectIntoMemory() {
    List<String> offenders = new ArrayList<>();
    for (Path root : STREAMING_ROOTS) {
      List<Path> files = javaFiles(root);
      assertThat(files).as("%s must contain sources to check", root).isNotEmpty();
      for (Path file : files) {
        String source = read(file);
        WHOLE_OBJECT_CALLS.stream()
            .filter(source::contains)
            .forEach(call -> offenders.add(file + " calls " + call));
      }
    }

    assertThat(offenders).isEmpty();
  }

  private static List<Path> javaFiles(Path root) {
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(path -> path.toString().endsWith(".java")).toList();
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static String read(Path file) {
    try {
      return new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
