package dev.drim.relay.domain;

/**
 * Pure text helpers the gallery honesty notes call out as legitimate unit territory: the
 * feed/notification preview truncation and the link-unfurl URL trigger. The component tests only
 * assert these are WIRED into the event/notification/post paths; that they are correct is unit
 * territory.
 */
public final class Previews {
  /** Bounds feed/notification previews (first 100 chars). */
  public static final int PREVIEW_MAX_LENGTH = 100;

  private Previews() {}

  public static String preview(String text) {
    // Count by code points so a surrogate pair is not split — matches the Go []rune slice.
    int count = text.codePointCount(0, text.length());
    if (count <= PREVIEW_MAX_LENGTH) {
      return text;
    }
    int end = text.offsetByCodePoints(0, PREVIEW_MAX_LENGTH);
    return text.substring(0, end);
  }

  /** Returns the first {@code http(s)://} token in text, or {@code null} — the unfurl trigger. */
  public static String firstUrl(String text) {
    for (String field : text.split("\\s+")) {
      if (field.startsWith("http://") || field.startsWith("https://")) {
        return field;
      }
    }
    return null;
  }
}
