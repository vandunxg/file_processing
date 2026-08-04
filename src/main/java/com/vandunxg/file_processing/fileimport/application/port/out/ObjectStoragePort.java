package com.vandunxg.file_processing.fileimport.application.port.out;

import java.io.InputStream;

public interface ObjectStoragePort {

  StoredObject store(
      String storageKey, String contentType, long contentLength, InputStream content);

  void delete(String storageKey);

  record StoredObject(String bucket, long sizeBytes, String checksumSha256, String contentType) {}
}
