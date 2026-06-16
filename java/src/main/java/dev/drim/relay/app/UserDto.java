package dev.drim.relay.app;

import dev.drim.relay.domain.Entities;
import java.time.Instant;

/** The wire shape for a user (02-api.md §1). */
public record UserDto(String id, String handle, String displayName, Instant createdAt) {
  public static UserDto of(Entities.User u) {
    return new UserDto(u.id(), u.handle(), u.displayName(), u.createdAt());
  }
}
