package com.vandunxg.file_processing;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
    scanBasePackages = {
      "com.vandunxg.common.web.security",
      "com.vandunxg.file_processing.*",
    })
@EnableScheduling
@OpenAPIDefinition(
    servers = {
      @Server(url = "${springdoc.api-docs.url}", description = "Server URL of File Processing")
    })
@ConfigurationPropertiesScan
public class FileProcessingApplication {

  public static void main(String[] args) {
    SpringApplication.run(FileProcessingApplication.class, args);
  }
}
