package dev.drim.relay.harness;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The PostgreSQL harness (the system of record). Real container, schema owned by Flyway when the
 * Spring context boots, fast reset via TRUNCATE (Java's idiomatic fast-reset brick — the
 * deliberately divergent brick across languages, §6). It also installs the deterministic one-shot
 * fault used by the G-TX catching test. Mirrors go/harness/database.go.
 */
public final class DatabaseHarness implements DependencyHarness {
  /**
   * Every table the per-test reset truncates (03-schema.md). Order is irrelevant under TRUNCATE …
   * CASCADE; listing all keeps the reset explicit.
   */
  private static final List<String> ALL_TABLES =
      List.of(
          "feed_entries",
          "notifications",
          "attachments",
          "dm_messages",
          "channel_messages",
          "channel_members",
          "channels",
          "dm_participants",
          "dm_conversations",
          "users");

  private final PostgreSQLContainer<?> container =
      new PostgreSQLContainer<>(
              DockerImageName.parse(HarnessImages.POSTGRES).asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("relay")
          .withUsername("relay")
          .withPassword("relay");

  private Connection connection;

  @Override
  public void start() {
    container.start();
    try {
      connection =
          DriverManager.getConnection(
              container.getJdbcUrl(), container.getUsername(), container.getPassword());
    } catch (SQLException e) {
      throw new IllegalStateException("postgres connect failed", e);
    }
  }

  public String jdbcUrl() {
    return container.getJdbcUrl();
  }

  public String username() {
    return container.getUsername();
  }

  public String password() {
    return container.getPassword();
  }

  /**
   * Installs the {@code _tx_fault} table + trigger. Must run AFTER Flyway created the schema (the
   * trigger is on {@code dm_participants}), so the suite calls this once the Spring context is up.
   */
  public void installTxFault() {
    exec(
        """
        CREATE TABLE IF NOT EXISTS _tx_fault (id int PRIMARY KEY, remaining int NOT NULL);

        CREATE OR REPLACE FUNCTION _tx_fault_raise() RETURNS trigger AS $$
        DECLARE left_count int;
        BEGIN
          UPDATE _tx_fault SET remaining = remaining - 1 WHERE id = 1 RETURNING remaining INTO left_count;
          IF left_count IS NOT NULL AND left_count = 0 THEN
            RAISE EXCEPTION 'tx fault injected on participant insert';
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql;

        DROP TRIGGER IF EXISTS _tx_fault_trg ON dm_participants;
        CREATE TRIGGER _tx_fault_trg BEFORE INSERT ON dm_participants
          FOR EACH ROW EXECUTE FUNCTION _tx_fault_raise();
        """);
  }

  /**
   * Arms the deterministic mid-transaction failure for G-TX: the next time a transaction reaches
   * its SECOND dm_participants insert, the database raises. The correct transactional writer rolls
   * everything back; the naive non-transactional writer leaves an orphan — which the catching test
   * reads. Cleared by {@link #reset()}.
   */
  public void armParticipantInsertFault() {
    exec(
        "INSERT INTO _tx_fault (id, remaining) VALUES (1, 2) "
            + "ON CONFLICT (id) DO UPDATE SET remaining = 2");
  }

  /** Returns the number of rows matching a WHERE clause on a table — a DB-state assert. */
  public int count(String table, String where) {
    try (var stmt =
            connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE " + where);
        ResultSet rs = stmt.executeQuery()) {
      rs.next();
      return rs.getInt(1);
    } catch (SQLException e) {
      throw new IllegalStateException("count failed", e);
    }
  }

  @Override
  public void reset() {
    exec("TRUNCATE " + String.join(", ", ALL_TABLES) + " RESTART IDENTITY CASCADE");
    exec("DELETE FROM _tx_fault");
  }

  @Override
  public void stop() {
    try {
      if (connection != null) {
        connection.close();
      }
    } catch (SQLException e) {
      // best-effort close at suite end
    }
    container.stop();
  }

  private void exec(String sql) {
    try (var stmt = connection.createStatement()) {
      stmt.execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException("exec failed: " + sql, e);
    }
  }
}
