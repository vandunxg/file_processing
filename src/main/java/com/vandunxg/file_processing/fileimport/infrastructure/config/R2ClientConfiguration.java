package com.vandunxg.file_processing.fileimport.infrastructure.config;

import java.net.URI;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The object-storage client.
 *
 * <p>No timeout is set on the total duration of a call. A supported file is up to 500 MB, and the
 * SDK's api-call timeout covers the whole request including the body transfer, so it would abort an
 * upload or download that is progressing perfectly well -- the larger the file, the more certain
 * the failure. Stalled connections are caught by the HTTP client's own idle timeouts instead (two
 * seconds to connect, thirty seconds of silence on a socket), which bound how long nothing happens
 * rather than how long the transfer takes.
 *
 * <p>The configured timeout is applied per request, on the calls that move no data.
 */
@Configuration
public class R2ClientConfiguration {

  @Bean
  public S3Client r2Client(R2ClientProperties properties) {
    AwsBasicCredentials credentials =
        AwsBasicCredentials.create(properties.accessKeyId(), properties.secretAccessKey());

    return S3Client.builder()
        .endpointOverride(URI.create(properties.endpoint()))
        .credentialsProvider(StaticCredentialsProvider.create(credentials))
        .region(Region.US_EAST_1)
        .build();
  }
}
