// The PostgreSQL harness (the system of record). Real container; the schema
// (prisma/schema.sql — the FKs, CHECKs, ON DELETE CASCADE, the deliberately
// absent feed FK, and the G-TX trigger) is applied at boot through the Prisma
// client over the container DSN. Fast reset via TRUNCATE (TS's idiomatic
// fast-reset brick — the deliberately divergent brick across languages, §6). It
// also installs/arms the deterministic one-shot fault the G-TX catching test uses.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { PrismaClient } from '@prisma/client';
import { PostgreSqlContainer, type StartedPostgreSqlContainer } from '@testcontainers/postgresql';

import { POSTGRES_IMAGE } from './images.js';
import type { DependencyHarness } from './dependency-harness.js';

const here = dirname(fileURLToPath(import.meta.url));
const SCHEMA_SQL_PATH = join(here, '..', 'prisma', 'schema.sql');

// allTables is every table the per-test reset truncates (../../spec/03-schema.md).
// Order is irrelevant under TRUNCATE ... CASCADE, but listing all keeps it explicit.
const ALL_TABLES = [
  'feed_entries',
  'notifications',
  'attachments',
  'dm_messages',
  'channel_messages',
  'channel_members',
  'channels',
  'dm_participants',
  'dm_conversations',
  'users',
];

// splitSqlStatements splits a schema script into individual statements on
// top-level semicolons, treating any `$$`-delimited block (the plpgsql function
// body) as opaque so its internal `;` separators are preserved. Comments and
// blank statements are dropped.
export function splitSqlStatements(sql: string): string[] {
  // Drop `--` comment lines FIRST: the schema's prose comments contain literal
  // `;` characters that would otherwise be read as statement boundaries. The
  // plpgsql function body carries no `--` lines, so this is safe to do globally.
  const code = sql
    .split('\n')
    .filter((line) => !line.trimStart().startsWith('--'))
    .join('\n');

  const statements: string[] = [];
  let current = '';
  let inDollar = false;
  for (let i = 0; i < code.length; i++) {
    const two = code.slice(i, i + 2);
    if (two === '$$') {
      inDollar = !inDollar;
      current += two;
      i++;
      continue;
    }
    const ch = code[i];
    if (ch === ';' && !inDollar) {
      const trimmed = current.trim();
      if (trimmed !== '') {
        statements.push(trimmed);
      }
      current = '';
      continue;
    }
    current += ch;
  }
  const tail = current.trim();
  if (tail !== '') {
    statements.push(tail);
  }
  return statements;
}

export class DatabaseHarness implements DependencyHarness {
  private container?: StartedPostgreSqlContainer;
  private prismaClient?: PrismaClient;
  private dsn = '';

  get prisma(): PrismaClient {
    if (!this.prismaClient) {
      throw new Error('DatabaseHarness not started');
    }
    return this.prismaClient;
  }

  async start(): Promise<void> {
    this.container = await new PostgreSqlContainer(POSTGRES_IMAGE)
      .withDatabase('relay')
      .withUsername('relay')
      .withPassword('relay')
      .start();
    this.dsn = this.container.getConnectionUri();

    this.prismaClient = new PrismaClient({ datasources: { db: { url: this.dsn } } });
    await this.prismaClient.$connect();

    const schema = readFileSync(SCHEMA_SQL_PATH, 'utf8');
    // Prisma's $executeRawUnsafe runs each call as a single prepared statement,
    // and Postgres rejects multiple commands in one prepared statement. The
    // schema also carries a plpgsql function body whose own `;` separators must
    // NOT be treated as statement boundaries, so split on top-level `;` while
    // honoring `$$` dollar-quoting, then run each statement on its own.
    for (const statement of splitSqlStatements(schema)) {
      await this.prismaClient.$executeRawUnsafe(statement);
    }
  }

  async reset(): Promise<void> {
    await this.prisma.$executeRawUnsafe(`TRUNCATE ${ALL_TABLES.join(', ')} RESTART IDENTITY CASCADE`);
    // Clear any armed TX fault so it never bleeds into the next test.
    await this.prisma.$executeRawUnsafe('DELETE FROM _tx_fault');
  }

  async stop(): Promise<void> {
    if (this.prismaClient) {
      await this.prismaClient.$disconnect();
    }
    if (this.container) {
      await this.container.stop();
    }
  }

  // armParticipantInsertFault arms the deterministic mid-transaction failure for
  // G-TX: the next time a transaction reaches its SECOND dm_participants insert,
  // the database raises. The correct transactional writer rolls everything back;
  // the naive non-transactional writer leaves an orphan — which the catching test
  // reads. Cleared by reset.
  async armParticipantInsertFault(): Promise<void> {
    await this.prisma.$executeRawUnsafe(
      'INSERT INTO _tx_fault (id, remaining) VALUES (1, 2) ON CONFLICT (id) DO UPDATE SET remaining = 2',
    );
  }

  // count returns the number of rows matching a WHERE clause — a DB-state assert.
  async count(table: string, where: string, ...args: unknown[]): Promise<number> {
    const rows = await this.prisma.$queryRawUnsafe<{ count: bigint }[]>(
      `SELECT COUNT(*)::bigint AS count FROM ${table} WHERE ${where}`,
      ...args,
    );
    return Number(rows[0]?.count ?? 0n);
  }
}
