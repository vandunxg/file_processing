package com.vandunxg.file_processing.fileimport.configuration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.file-import")
public record FileImportProperties(Duration retention) {

  public FileImportProperties {
    if (retention == null || retention.isZero() || retention.isNegative()) {
      throw new IllegalArgumentException("File import retention must be positive");
    }
  }
}
