package com.vandunxg.file_processing.fileimport.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.vandunxg.file_processing.testsupport.PostgresTestContainerBase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PostgresDuplicateExternalIdTrackerIT extends PostgresTestContainerBase {

  @Test
  void keepsOnlyTheFirstExternalIdWithinOneParseRun() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var tracker = new PostgresDuplicateExternalIdTracker(dataSource);

    try (var run = tracker.open()) {
      assertThat(run.firstOccurrence("CUS_01")).isTrue();
      assertThat(run.firstOccurrence("CUS_01")).isFalse();
      assertThat(run.firstOccurrence("CUS_02")).isTrue();
    }
  }

  @Test
  void dropsTheTemporaryTableBeforeReturningAPooledConnection() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    config.setMaximumPoolSize(1);
    try (var dataSource = new HikariDataSource(config)) {
      var tracker = new PostgresDuplicateExternalIdTracker(dataSource);

      try (var firstRun = tracker.open()) {
        assertThat(firstRun.firstOccurrence("CUS_01")).isTrue();
      }
      try (var secondRun = tracker.open()) {
        assertThat(secondRun.firstOccurrence("CUS_01")).isTrue();
      }
    }
  }
}
