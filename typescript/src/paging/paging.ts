// paging implements the strict pagination contract (02-api.md §0.1): limit 1–100
// (default 50), VALIDATED never clamped. These exact 422s are the deterministic
// pin the §3 weakened-validation gaming story violates, so the bounds are
// identical across all five languages.

import { invalid } from '../apierr/apierr.js';

export const DEFAULT_LIMIT = 50;
export const MIN_LIMIT = 1;
export const MAX_LIMIT = 100;

// Page is one page of items plus the cursor for the next page (null when done).
export interface Page<T> {
  items: T[];
  nextBefore: string | null;
}

// parseLimit validates the raw limit query param. Undefined/empty → default 50.
// Non-integer → 422 pagination:limit:not_a_number. Out of [1,100] → 422
// pagination:limit:out_of_range. No silent clamping.
export function parseLimit(raw: string | undefined): number {
  if (raw === undefined || raw === '') {
    return DEFAULT_LIMIT;
  }
  // Reject anything that is not a clean integer literal (e.g. "abc", "1.5",
  // "1e3", " 1 ") — JS Number() is too forgiving for the strict contract.
  if (!/^-?\d+$/.test(raw)) {
    throw invalid('pagination:limit:not_a_number', 'limit must be an integer.');
  }
  const limit = Number.parseInt(raw, 10);
  if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
    throw invalid('pagination:limit:out_of_range', 'limit must be between 1 and 100.');
  }
  return limit;
}

// errUnknownBefore is the 422 for a before cursor that was never returned.
export function errUnknownBefore(): never {
  throw invalid('pagination:before:unknown', 'The before cursor is unknown.');
}

// build assembles a Page from rows fetched with limit+1: if more than limit came
// back, the extra row signals a next page whose cursor is the last kept id. When
// the cursor field is not part of the DTO shape (feed), pass an explicit id list.
export function build<T>(rows: T[], limit: number, id: (row: T) => string): Page<T> {
  if (rows.length > limit) {
    const kept = rows.slice(0, limit);
    return { items: kept, nextBefore: id(kept[kept.length - 1]) };
  }
  return { items: rows, nextBefore: null };
}

export function buildWithIds<T>(rows: T[], ids: string[], limit: number): Page<T> {
  if (rows.length > limit) {
    return { items: rows.slice(0, limit), nextBefore: ids[limit - 1] };
  }
  return { items: rows, nextBefore: null };
}
