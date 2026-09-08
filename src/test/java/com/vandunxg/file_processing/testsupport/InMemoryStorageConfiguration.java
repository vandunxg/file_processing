package com.vandunxg.file_processing.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the object-storage adapter so no test reaches a real bucket. */
@TestConfiguration
public class InMemoryStorageConfiguration {

  @Bean
  @Primary
  InMemoryFileStorage inMemoryFileStorage() {
    return new InMemoryFileStorage();
  }
}
