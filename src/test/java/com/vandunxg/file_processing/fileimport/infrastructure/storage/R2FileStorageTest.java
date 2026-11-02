package com.vandunxg.file_processing.fileimport.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.vandunxg.file_processing.fileimport.infrastructure.config.R2ClientProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * How the storage calls are bounded.
 *
 * <p>A supported file is up to 500 MB, so an upload or a download legitimately runs for minutes. A
 * timeout on the total duration of those calls would abort a transfer that is progressing perfectly
 * well, and the SDK applies such a timeout to the whole request -- the body upload included.
 * Stalled connections are caught by the HTTP client's idle read and write timeouts instead, which
 * do not care how long a healthy transfer takes.
 */
class R2FileStorageTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final S3Client client = mock(S3Client.class);
  private final R2FileStorage storage =
      new R2FileStorage(
          client,
          new R2ClientProperties("http://localhost", "key", "secret", "file-processing", TIMEOUT));

  @Test
  void storingAnObjectIsNotCappedByATotalCallDuration() {
    when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenReturn(PutObjectResponse.builder().build());

    storage.store(
        "imports/one",
        "text/csv",
        3,
        new ByteArrayInputStream("a,b".getBytes(StandardCharsets.UTF_8)));

    ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(client).putObject(request.capture(), any(RequestBody.class));
    assertThat(totalCallTimeoutOf(request.getValue().overrideConfiguration().orElse(null)))
        .as("a 500 MB upload must not be aborted for taking longer than a metadata call")
        .isNull();
  }

  @Test
  void openingAnObjectIsNotCappedByATotalCallDuration() {
    when(client.getObject(any(GetObjectRequest.class)))
        .thenReturn(
            new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(new byte[0]))));

    storage.open("imports/one");

    ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
    verify(client).getObject(request.capture());
    assertThat(totalCallTimeoutOf(request.getValue().overrideConfiguration().orElse(null)))
        .isNull();
  }

  @Test
  void deletingAnObjectIsCappedBecauseItMovesNoData() {
    when(client.deleteObject(any(DeleteObjectRequest.class)))
        .thenReturn(DeleteObjectResponse.builder().build());

    storage.delete("imports/one");

    ArgumentCaptor<DeleteObjectRequest> request =
        ArgumentCaptor.forClass(DeleteObjectRequest.class);
    verify(client).deleteObject(request.capture());
    assertThat(totalCallTimeoutOf(request.getValue().overrideConfiguration().orElse(null)))
        .isEqualTo(TIMEOUT);
  }

  private static Duration totalCallTimeoutOf(
      software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration override) {
    return override == null ? null : override.apiCallTimeout().orElse(null);
  }
}
