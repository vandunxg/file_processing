package com.vandunxg.file_processing.fileimport.application.capability;

import java.io.InputStream;

public interface FileStorage {

  StoredObject store(
      String storageKey, String contentType, long contentLength, InputStream content);

  InputStream open(String storageKey);

  /** Verifies that a stored original can still be replayed without opening its full stream. */
  boolean exists(String storageKey);

  void delete(String storageKey);

  record StoredObject(String bucket, long sizeBytes, String checksumSha256, String contentType) {}
}
