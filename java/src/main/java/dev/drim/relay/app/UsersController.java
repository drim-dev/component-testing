package dev.drim.relay.app;

import dev.drim.relay.id.IdFactory;
import dev.drim.relay.store.UserRepository;
import dev.drim.relay.store.entity.UserRow;
import dev.drim.relay.web.ApiException;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UsersController {
  private static final Pattern HANDLE = Pattern.compile("^[a-z0-9_]+$");

  private final UserRepository users;
  private final IdFactory ids;

  public UsersController(UserRepository users, IdFactory ids) {
    this.users = users;
    this.ids = ids;
  }

  public record CreateUserBody(String handle, String displayName) {}

  @PostMapping("/users")
  public ResponseEntity<UserDto> create(@RequestBody(required = false) CreateUserBody body) {
    String handle = body == null ? null : body.handle();
    String displayName = body == null ? null : body.displayName();

    if (handle == null
        || handle.length() < 3
        || handle.length() > 32
        || !HANDLE.matcher(handle).matches()) {
      throw ApiException.invalid("user:handle:invalid", "handle must be 3–32 chars of [a-z0-9_].");
    }
    if (displayName == null || displayName.isEmpty() || displayName.length() > 64) {
      throw ApiException.invalid("user:display_name:invalid", "displayName must be 1–64 chars.");
    }

    UserRow row = new UserRow(ids.create(), handle, displayName, Instant.now());
    try {
      users.saveAndFlush(row);
    } catch (DataIntegrityViolationException e) {
      throw ApiException.conflict("user:handle:taken", "That handle is taken.");
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(UserDto.of(row.toDomain()));
  }

  @GetMapping("/users/{id}")
  public UserDto get(@PathVariable String id) {
    return users
        .findById(id)
        .map(r -> UserDto.of(r.toDomain()))
        .orElseThrow(() -> ApiException.notFound("user:not_found", "User not found."));
  }
}
