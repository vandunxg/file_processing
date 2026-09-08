package com.vandunxg.file_processing.fileimport.infrastructure.storage;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.vandunxg.file_processing.fileimport.application.capability.FileStorage;
import com.vandunxg.file_processing.fileimport.application.exception.FileImportErrorCode;
import com.vandunxg.file_processing.fileimport.application.exception.FileImportException;
import com.vandunxg.file_processing.fileimport.infrastructure.config.R2ClientProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Reads and writes the originals and the reports.
 *
 * <p>Neither the upload nor the download is given a timeout on its total duration: both stream a
 * file that may be 500 MB, and the SDK's api-call timeout covers the body transfer, so it would
 * abort a healthy transfer for being large. A stalled connection is still caught by the HTTP
 * client's idle read and write timeouts. Deleting moves no data, so it does get the configured cap.
 */
@Component
@RequiredArgsConstructor
public class R2FileStorage implements FileStorage {

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
      throw new FileImportException(FileImportErrorCode.FILE_IMPORT_STORAGE_UNAVAILABLE, exception);
    }
  }

  @Override
  public void delete(String storageKey) {
    try {
      r2Client.deleteObject(deleteRequest(storageKey));
    } catch (SdkException exception) {
      throw new FileImportException(FileImportErrorCode.FILE_IMPORT_STORAGE_UNAVAILABLE, exception);
    }
  }

  @Override
  public InputStream open(String storageKey) {
    try {
      return r2Client.getObject(
          GetObjectRequest.builder().bucket(properties.bucket()).key(storageKey).build());
    } catch (SdkException exception) {
      throw new FileImportException(FileImportErrorCode.FILE_IMPORT_STORAGE_UNAVAILABLE, exception);
    }
  }

  @Override
  public boolean exists(String storageKey) {
    try {
      r2Client.headObject(
          HeadObjectRequest.builder().bucket(properties.bucket()).key(storageKey).build());
      return true;
    } catch (S3Exception exception) {
      if (exception.statusCode() == 404) {
        return false;
      }
      throw new FileImportException(FileImportErrorCode.FILE_IMPORT_STORAGE_UNAVAILABLE, exception);
    } catch (SdkException exception) {
      throw new FileImportException(FileImportErrorCode.FILE_IMPORT_STORAGE_UNAVAILABLE, exception);
    }
  }

  private DeleteObjectRequest deleteRequest(String storageKey) {
    return DeleteObjectRequest.builder()
        .bucket(properties.bucket())
        .key(storageKey)
        .overrideConfiguration(override -> override.apiCallTimeout(properties.apiCallTimeout()))
        .build();
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
      r2Client.deleteObject(deleteRequest(storageKey));
    } catch (SdkException ignored) {
      // Best effort only: the original storage failure remains the actionable error.
    }
  }
}
