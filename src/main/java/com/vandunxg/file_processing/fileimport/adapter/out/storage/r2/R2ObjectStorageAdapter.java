package com.vandunxg.file_processing.fileimport.adapter.out.storage.r2;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.vandunxg.file_processing.fileimport.application.port.out.ObjectStoragePort;
import com.vandunxg.file_processing.fileimport.configuration.r2.R2ClientProperties;
import com.vandunxg.file_processing.fileimport.domain.exception.FileImportErrorCode;
import com.vandunxg.file_processing.fileimport.domain.exception.FileImportException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
@RequiredArgsConstructor
public class R2ObjectStorageAdapter implements ObjectStoragePort {

  private final S3Client r2Client;
  private final R2ClientProperties properties;

  @Override
  public StoredObject store(
      String storageKey, String contentType, long contentLength, InputStream content) {
    MessageDigest digest = sha256();
    try {
      r2Client.putObject(
          PutObjectRequest.builder()
              .bucket(properties.bucket())
              .key(storageKey)
              .contentType(contentType)
              .contentLength(contentLength)
              .build(),
          RequestBody.fromInputStream(new DigestInputStream(content, digest), contentLength));
      return new StoredObject(
          properties.bucket(),
          contentLength,
          HexFormat.of().formatHex(digest.digest()),
          contentType);
    } catch (SdkException exception) {
      deleteAfterFailedStore(storageKey);
      throw new FileImportException(FileImportErrorCode.STORAGE_UNAVAILABLE, exception);
    }
  }

  @Override
  public void delete(String storageKey) {
    try {
      r2Client.deleteObject(
          DeleteObjectRequest.builder().bucket(properties.bucket()).key(storageKey).build());
    } catch (SdkException exception) {
      throw new FileImportException(FileImportErrorCode.STORAGE_UNAVAILABLE, exception);
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private void deleteAfterFailedStore(String storageKey) {
    try {
      r2Client.deleteObject(
          DeleteObjectRequest.builder().bucket(properties.bucket()).key(storageKey).build());
    } catch (SdkException ignored) {
      // Best effort only: the original storage failure remains the actionable error.
    }
  }
}
