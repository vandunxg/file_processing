package com.vandunxg.file_processing.fileimport.domain.model;

/** Immutable SHA-256 identity of an accepted import file. */
public record FileChecksum(String value) {

  private static final String SHA_256_HEX = "[0-9a-f]{64}";

  public FileChecksum {
    if (value == null || !value.matches(SHA_256_HEX)) {
      throw new IllegalArgumentException("File checksum must be lowercase SHA-256 hex");
    }
  }

  public static FileChecksum of(String value) {
    return new FileChecksum(value);
  }
}
