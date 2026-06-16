package dev.drim.relay.web;

import java.util.List;
import java.util.function.Function;

/**
 * The strict pagination contract (02-api.md §0.1): limit 1–100 (default 50), VALIDATED never
 * clamped. These exact 422s are the deterministic pin the §3 weakened-validation gaming story
 * violates, so the bounds are identical across all five languages.
 */
public final class Paging {
  public static final int DEFAULT_LIMIT = 50;
  public static final int MIN_LIMIT = 1;
  public static final int MAX_LIMIT = 100;

  private Paging() {}

  /** One page of items plus the cursor for the next page (null when exhausted). */
  public record Page<T>(List<T> items, String nextBefore) {}

  /**
   * Validates the raw limit query param. Null/blank → default 50. Non-integer → 422
   * pagination:limit:not_a_number. Out of [1,100] → 422 pagination:limit:out_of_range.
   */
  public static int parseLimit(String raw) {
    if (raw == null || raw.isEmpty()) {
      return DEFAULT_LIMIT;
    }
    int limit;
    try {
      limit = Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      throw ApiException.invalid("pagination:limit:not_a_number", "limit must be an integer.");
    }
    if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
      throw ApiException.invalid(
          "pagination:limit:out_of_range", "limit must be between 1 and 100.");
    }
    return limit;
  }

  /** The 422 for a before cursor that was never returned in this scope. */
  public static ApiException unknownBefore() {
    return ApiException.invalid("pagination:before:unknown", "The before cursor is unknown.");
  }

  /**
   * Assembles a page from rows fetched with limit+1: if more than limit came back, the extra row
   * signals there is a next page and its cursor is the last kept item's id.
   */
  public static <T> Page<T> build(List<T> rows, int limit, Function<T, String> id) {
    if (rows.size() > limit) {
      List<T> kept = rows.subList(0, limit);
      return new Page<>(List.copyOf(kept), id.apply(kept.get(kept.size() - 1)));
    }
    return new Page<>(List.copyOf(rows), null);
  }
}
