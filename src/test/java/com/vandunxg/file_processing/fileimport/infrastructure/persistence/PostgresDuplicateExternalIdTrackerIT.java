package com.vandunxg.file_processing.fileimport.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.vandunxg.file_processing.testsupport.PostgresTestContainerBase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
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
  void doesNotHoldAWriteTransactionOpenWhileTheImportRuns() {
    // A run lasts as long as the file takes -- minutes for the supported size. A write transaction
    // left open for that long pins the vacuum horizon for the whole database, so the churn tables
    // the rest of the application writes to cannot be cleaned up while any import is in progress.
    try (var dataSource = productionShapedPool()) {
      var tracker = new PostgresDuplicateExternalIdTracker(dataSource);

      try (var run = tracker.open()) {
        assertThat(run.firstOccurrence("CUS_01")).isTrue();
        assertThat(run.firstOccurrence("CUS_01")).isFalse();

        assertThat(openWriteTransactions())
            .as("the tracker must not sit in an open write transaction between rows")
            .isZero();
      }
    }
  }

  @Test
  void dropsTheTemporaryTableBeforeReturningAPooledConnection() {
    try (var dataSource = productionShapedPool()) {
      var tracker = new PostgresDuplicateExternalIdTracker(dataSource);

      try (var firstRun = tracker.open()) {
        assertThat(firstRun.firstOccurrence("CUS_01")).isTrue();
      }
      try (var secondRun = tracker.open()) {
        assertThat(secondRun.firstOccurrence("CUS_01")).isTrue();
      }
    }
  }

  /**
   * Named so the transaction check can see this pool alone, and auto-commit off as in production.
   */
  private static final String APPLICATION_NAME = "duplicate-tracker-it";

  private static HikariDataSource productionShapedPool() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(taggedJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    config.setMaximumPoolSize(1);
    config.setAutoCommit(false);
    return new HikariDataSource(config);
  }

  private static String taggedJdbcUrl() {
    String url = POSTGRES.getJdbcUrl();
    return url + (url.contains("?") ? "&" : "?") + "ApplicationName=" + APPLICATION_NAME;
  }

  /** Backends of this pool that currently hold an assigned transaction id. */
  private static Long openWriteTransactions() {
    JdbcTemplate observer =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    return observer.queryForObject(
        """
        SELECT count(*) FROM pg_stat_activity
        WHERE application_name = ? AND backend_xid IS NOT NULL
        """,
        Long.class,
        APPLICATION_NAME);
  }
}
