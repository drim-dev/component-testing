package dev.drim.relay.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders every error as the pinned JSON body {@code {status, code, message}} (02-api.md §0). An
 * {@link ApiException} renders its own status/code; anything else is a generic 500 so an unexpected
 * failure never leaks internals.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /** The pinned error body. A record so the field order and JSON shape are fixed. */
  public record ErrorBody(int status, String code, String message) {}

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ErrorBody> handleApi(ApiException e) {
    return ResponseEntity.status(e.status())
        .body(new ErrorBody(e.status(), e.code(), e.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorBody> handleUnexpected(Exception e) {
    // The client gets an opaque 500 (no internals leak), but the cause must be observable
    // server-side — a silently swallowed exception is undebuggable.
    log.error("unexpected error", e);
    int status = HttpStatus.INTERNAL_SERVER_ERROR.value();
    return ResponseEntity.status(status)
        .body(new ErrorBody(status, "internal:error", "An unexpected error occurred."));
  }
}
