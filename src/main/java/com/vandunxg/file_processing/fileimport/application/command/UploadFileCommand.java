package com.vandunxg.file_processing.fileimport.application.command;

import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

public record UploadFileCommand(
    UUID ownerId,
    String originalFilename,
    String contentType,
    long contentLength,
    InputStream content) {

  public UploadFileCommand {
    Objects.requireNonNull(ownerId, "ownerId is required");
    Objects.requireNonNull(originalFilename, "originalFilename is required");
    Objects.requireNonNull(contentType, "contentType is required");
    Objects.requireNonNull(content, "content is required");
    if (contentLength < 0) {
      throw new IllegalArgumentException("contentLength must be non-negative");
    }
  }
}
