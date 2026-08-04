package com.vandunxg.file_processing.fileimport.configuration.r2;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.file-import.storage.r2")
public record R2ClientProperties(
    String endpoint,
    String accessKeyId,
    String secretAccessKey,
    String bucket,
    Duration apiCallTimeout) {}
