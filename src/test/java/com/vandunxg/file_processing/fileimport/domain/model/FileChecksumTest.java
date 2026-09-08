package com.vandunxg.file_processing.fileimport.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FileChecksumTest {

  @Test
  void acceptsOnlyLowercaseSha256Hex() {
    String checksum = "a".repeat(64);

    assertThat(FileChecksum.of(checksum).value()).isEqualTo(checksum);
    assertThatThrownBy(() -> FileChecksum.of(checksum.toUpperCase()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
