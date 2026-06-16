// Package store is Relay's PostgreSQL data layer over pgx. Queries are hand-written typed
// pgx (the role sqlc would generate; sqlc is in the locked stack, but its generated code
// IS pgx — writing the queries directly keeps the layer idiomatic without a code-gen
// step). Migrations run once at boot via golang-migrate as a library (embedded SQL, no
// binary). Constraints here are product behavior: the unique pair (RACE), unique
// notification (RABBIT), and unique feed entry (KAFKA) are the backstops the gallery leans on.
package store

import (
	"context"
	"embed"
	"errors"
	"fmt"

	"github.com/golang-migrate/migrate/v4"
	// Registers the pgx/v5 migrate driver so migrate.NewWithInstance can resolve "pgx5".
	_ "github.com/golang-migrate/migrate/v4/database/pgx/v5"
	"github.com/golang-migrate/migrate/v4/source/iofs"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

//go:embed migrations/*.sql
var migrationFiles embed.FS

// Store wraps a pgx connection pool. It exposes typed methods for every query Relay needs.
type Store struct {
	pool *pgxpool.Pool
}

// Open connects to dsn and returns a Store. The caller owns Close.
func Open(ctx context.Context, dsn string) (*Store, error) {
	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		return nil, fmt.Errorf("connect: %w", err)
	}
	return &Store{pool: pool}, nil
}

// Close releases the pool.
func (s *Store) Close() { s.pool.Close() }

// Pool exposes the underlying pool for the transactional writer and DB-state assertions.
func (s *Store) Pool() *pgxpool.Pool { return s.pool }

// Migrate applies the embedded migrations to dsn. Idempotent (no-change is not an error).
// The dsn is the testcontainers postgres connection string; golang-migrate's pgx5 driver
// is selected by the "pgx5://" scheme.
func Migrate(dsn string) error {
	source, err := iofs.New(migrationFiles, "migrations")
	if err != nil {
		return fmt.Errorf("migration source: %w", err)
	}
	m, err := migrate.NewWithSourceInstance("iofs", source, toPgx5URL(dsn))
	if err != nil {
		return fmt.Errorf("migrate init: %w", err)
	}
	defer func() { _, _ = m.Close() }()
	if err := m.Up(); err != nil && !errors.Is(err, migrate.ErrNoChange) {
		return fmt.Errorf("migrate up: %w", err)
	}
	return nil
}

func toPgx5URL(dsn string) string {
	for _, prefix := range []string{"postgres://", "postgresql://", "pgx5://"} {
		if len(dsn) >= len(prefix) && dsn[:len(prefix)] == prefix {
			return "pgx5://" + dsn[len(prefix):]
		}
	}
	return dsn
}

// IsUniqueViolation reports whether err is a Postgres unique-constraint violation (SQLSTATE
// 23505) — the duplicate-treated-as-success path for the RABBIT and KAFKA consumers.
func IsUniqueViolation(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == "23505"
}

// Querier is the subset of pgx used by queries — satisfied by both *pgxpool.Pool and pgx.Tx,
// so the same query helpers run inside or outside a transaction.
type Querier interface {
	Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error)
	Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error)
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

var _ Querier = (*pgxpool.Pool)(nil)
