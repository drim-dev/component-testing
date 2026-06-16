package dev.drim.relay.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The canonical ObjectMapper for broker event wire JSON. The cross-language contract serializes
 * timestamps as RFC-3339 strings (Go's {@code json.Marshal} of {@code time.Time}), so the mapper
 * registers the JavaTime module and disables numeric-timestamp serialization. Spring's
 * auto-configured mapper already matches this (the codec beans receive it via DI); outside Spring —
 * the test harnesses — use this factory so the wire bytes are identical.
 */
public final class EventCodecs {
  private EventCodecs() {}

  public static ObjectMapper canonicalMapper() {
    return new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }
}
