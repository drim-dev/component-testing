package dev.drim.relay.domain;

/** A channel membership role, ordered owner &gt; admin &gt; member. */
public enum Role {
  MEMBER,
  ADMIN,
  OWNER;

  /**
   * Reports whether this role is at least as privileged as {@code min} — the pure ordering
   * predicate the G-BOLA-ROLE honesty note unit-tests.
   */
  public boolean atLeast(Role min) {
    return ordinal() >= min.ordinal();
  }

  /** The lowercase DB/wire value (the only form the schema CHECK and the API contract accept). */
  public String wire() {
    return name().toLowerCase();
  }

  public static Role parse(String s) {
    return switch (s) {
      case "owner" -> OWNER;
      case "admin" -> ADMIN;
      default -> MEMBER;
    };
  }
}
