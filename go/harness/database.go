package harness

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/store"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/testcontainers/testcontainers-go"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"
)

// allTables is every table the per-test reset truncates (03-schema.md). Order is irrelevant
// under TRUNCATE ... CASCADE, but listing all keeps the reset explicit.
var allTables = []string{
	"feed_entries", "notifications", "attachments", "dm_messages", "channel_messages",
	"channel_members", "channels", "dm_participants", "dm_conversations", "users",
}

// DatabaseHarness is the PostgreSQL harness (the system of record). Real container, schema
// from golang-migrate at boot, fast reset via TRUNCATE (Go's idiomatic fast-reset brick —
// the deliberately divergent brick across languages, §6). It also installs the deterministic
// one-shot fault used by the G-TX catching test.
type DatabaseHarness struct {
	container *tcpostgres.PostgresContainer
	dsn       string
	pool      *pgxpool.Pool
}

func (h *DatabaseHarness) DSN() string { return h.dsn }

// Pool exposes a direct pool for DB-state assertions (counts, orphan checks).
func (h *DatabaseHarness) Pool() *pgxpool.Pool { return h.pool }

func (h *DatabaseHarness) Start(ctx context.Context) error {
	container, err := tcpostgres.Run(ctx, PostgresImage,
		tcpostgres.WithDatabase("relay"),
		tcpostgres.WithUsername("relay"),
		tcpostgres.WithPassword("relay"),
		testcontainers.WithWaitStrategy(
			wait.ForLog("database system is ready to accept connections").
				WithOccurrence(2).WithStartupTimeout(60*time.Second)),
	)
	if err != nil {
		return fmt.Errorf("start postgres: %w", err)
	}
	h.container = container

	dsn, err := container.ConnectionString(ctx, "sslmode=disable")
	if err != nil {
		return fmt.Errorf("postgres dsn: %w", err)
	}
	h.dsn = dsn

	if err := store.Migrate(dsn); err != nil {
		return fmt.Errorf("migrate: %w", err)
	}
	if err := h.installTxFault(ctx, dsn); err != nil {
		return fmt.Errorf("install tx fault: %w", err)
	}
	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		return fmt.Errorf("pool: %w", err)
	}
	h.pool = pool
	return nil
}

func (h *DatabaseHarness) Reset(ctx context.Context) error {
	_, err := h.pool.Exec(ctx,
		fmt.Sprintf("TRUNCATE %s RESTART IDENTITY CASCADE", strings.Join(allTables, ", ")))
	if err != nil {
		return err
	}
	// Clear any armed TX fault so it never bleeds into the next test.
	_, err = h.pool.Exec(ctx, "DELETE FROM _tx_fault")
	return err
}

func (h *DatabaseHarness) Stop(ctx context.Context) error {
	if h.pool != nil {
		h.pool.Close()
	}
	if h.container != nil {
		return h.container.Terminate(ctx)
	}
	return nil
}

// ArmParticipantInsertFault arms the deterministic mid-transaction failure for G-TX: the next
// time a transaction reaches its SECOND dm_participants insert, the database raises. The
// correct transactional writer rolls everything back; the naive non-transactional writer
// leaves an orphan — which the catching test reads. Cleared by Reset.
func (h *DatabaseHarness) ArmParticipantInsertFault(ctx context.Context) error {
	_, err := h.pool.Exec(ctx,
		"INSERT INTO _tx_fault (id, remaining) VALUES (1, 2) ON CONFLICT (id) DO UPDATE SET remaining = 2")
	return err
}

// Count returns the number of rows matching a WHERE clause on a table — a DB-state assert.
func (h *DatabaseHarness) Count(ctx context.Context, table, where string, args ...any) (int, error) {
	var n int
	err := h.pool.QueryRow(ctx, fmt.Sprintf("SELECT COUNT(*) FROM %s WHERE %s", table, where), args...).Scan(&n)
	return n, err
}

func (h *DatabaseHarness) installTxFault(ctx context.Context, dsn string) error {
	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		return err
	}
	defer pool.Close()
	_, err = pool.Exec(ctx, `
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
	`)
	return err
}

var _ DependencyHarness = (*DatabaseHarness)(nil)
