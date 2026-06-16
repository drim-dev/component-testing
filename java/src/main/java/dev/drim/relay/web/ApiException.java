package dev.drim.relay.web;

import org.springframework.http.HttpStatus;

/**
 * Relay's error model. Every deliberately-raised error carries an HTTP status and the pinned {@code
 * area:entity:reason} code the acceptance catalog asserts. Handlers throw these; {@link
 * ApiExceptionHandler} translates them into the pinned JSON body (02-api.md §0).
 *
 * <p>Existence-hiding (01-domain.md §5) is a property of the codes, not of special routing: an
 * unknown-id 404 and an unauthorized 404 throw the SAME code+message, so their JSON bodies are
 * byte-identical — exactly what the G-IDOR / G-BOLA-READ / G-S3 catches assert.
 */
public class ApiException extends RuntimeException {
  private final int status;
  private final String code;

  public ApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  /** 401 — the only identity failures (missing / unknown X-User-Id). */
  public static ApiException unauthorized(String code, String message) {
    return new ApiException(HttpStatus.UNAUTHORIZED.value(), code, message);
  }

  /** 403 — the caller can see the resource but lacks the membership/role. */
  public static ApiException forbidden(String code, String message) {
    return new ApiException(HttpStatus.FORBIDDEN.value(), code, message);
  }

  /** 404 — resource absent OR existence hidden from an unauthorized caller. */
  public static ApiException notFound(String code, String message) {
    return new ApiException(HttpStatus.NOT_FOUND.value(), code, message);
  }

  /** 409 — state conflict (duplicate handle, already a member, owner cannot leave). */
  public static ApiException conflict(String code, String message) {
    return new ApiException(HttpStatus.CONFLICT.value(), code, message);
  }

  /** 413 — attachment over the size limit. */
  public static ApiException tooLarge(String code, String message) {
    return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE.value(), code, message);
  }

  /** 422 — input failed validation or a business rule (no silent clamping). */
  public static ApiException invalid(String code, String message) {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY.value(), code, message);
  }

  /** 502 — an upstream (model / unfurl / presence stream) violated its contract. */
  public static ApiException upstream(String code, String message) {
    return new ApiException(HttpStatus.BAD_GATEWAY.value(), code, message);
  }

  /** 503 — required infrastructure (the event broker) is unavailable. */
  public static ApiException unavailable(String code, String message) {
    return new ApiException(HttpStatus.SERVICE_UNAVAILABLE.value(), code, message);
  }
}
