package com.vandunxg.file_processing.fileimport.configuration.r2;

import java.net.URI;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class R2ClientConfiguration {

  @Bean
  public S3Client r2Client(R2ClientProperties properties) {
    AwsBasicCredentials credentials =
        AwsBasicCredentials.create(properties.accessKeyId(), properties.secretAccessKey());

    return S3Client.builder()
        .endpointOverride(URI.create(properties.endpoint()))
        .credentialsProvider(StaticCredentialsProvider.create(credentials))
        .overrideConfiguration(
            ClientOverrideConfiguration.builder()
                .apiCallTimeout(properties.apiCallTimeout())
                .build())
        .region(Region.US_EAST_1)
        .build();
  }
}
