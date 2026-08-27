package com.vandunxg.file_processing.fileimport.adapter.out.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

import com.vandunxg.file_processing.fileimport.application.port.out.DuplicateExternalIdTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresDuplicateExternalIdTracker implements DuplicateExternalIdTracker {

  private static final String CREATE_TABLE =
      "CREATE TEMP TABLE csv_seen_external_ids (external_id VARCHAR(64) PRIMARY KEY) "
          + "ON COMMIT PRESERVE ROWS";
  private static final String INSERT_EXTERNAL_ID =
      "INSERT INTO csv_seen_external_ids (external_id) VALUES (?) ON CONFLICT DO NOTHING";
  private static final String DROP_TABLE = "DROP TABLE IF EXISTS csv_seen_external_ids";

  private final DataSource dataSource;

  @Override
  public Run open() {
    Connection connection = null;
    try {
      connection = dataSource.getConnection();
      try (Statement statement = connection.createStatement()) {
        statement.execute(CREATE_TABLE);
      }
      return new PostgresRun(connection, connection.prepareStatement(INSERT_EXTERNAL_ID));
    } catch (SQLException exception) {
      close(connection);
      throw new IllegalStateException("Unable to start duplicate external ID tracking", exception);
    }
  }

  private static void close(Connection connection) {
    if (connection == null) {
      return;
    }
    if (!dropTemporaryTable(connection)) {
      abort(connection);
    }
    try {
      connection.close();
    } catch (SQLException ignored) {
      // There is no usable session when tracker initialization fails.
    }
  }

  private static boolean dropTemporaryTable(Connection connection) {
    try (Statement statement = connection.createStatement()) {
      statement.execute(DROP_TABLE);
      return true;
    } catch (SQLException ignored) {
      return false;
    }
  }

  private static void abort(Connection connection) {
    try {
      connection.abort(Runnable::run);
    } catch (SQLException ignored) {
      // Closing below is the final best-effort cleanup.
    }
  }

  private static final class PostgresRun implements Run {

    private final Connection connection;
    private final PreparedStatement insert;

    private PostgresRun(Connection connection, PreparedStatement insert) {
      this.connection = connection;
      this.insert = insert;
    }

    @Override
    public boolean firstOccurrence(String externalId) {
      try {
        insert.setString(1, externalId);
        return insert.executeUpdate() == 1;
      } catch (SQLException exception) {
        throw new IllegalStateException("Unable to track duplicate external ID", exception);
      }
    }

    @Override
    public void close() {
      try {
        insert.close();
      } catch (SQLException ignored) {
        // The temporary table is still cleaned up below.
      }
      PostgresDuplicateExternalIdTracker.close(connection);
    }
  }
}
