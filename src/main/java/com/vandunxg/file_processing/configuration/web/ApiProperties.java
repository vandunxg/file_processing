package com.vandunxg.file_processing.configuration.web;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.api")
public record ApiProperties(@NotBlank String prefix, @NotBlank String version) {

  public String basePath() {
    return prefix + "/" + version;
  }

  public String authPath() {
    return basePath() + "/auth";
  }
}
