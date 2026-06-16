package dev.drim.relay.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.drim.relay.store.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the trusted {@code X-User-Id} header into the request's {@link CurrentUser}. Missing →
 * 401 identity:missing; unknown → 401 identity:unknown. {@code POST /users} is the only exempt
 * route (02-api.md §0). The header is trusted because the BFF terminates auth and the backend is
 * network-isolated — the same contract the production platform uses.
 */
@Component
public class IdentityFilter extends OncePerRequestFilter {
  private final UserRepository users;
  private final CurrentUser currentUser;
  private final ObjectMapper objectMapper;

  public IdentityFilter(UserRepository users, CurrentUser currentUser, ObjectMapper objectMapper) {
    this.users = users;
    this.currentUser = currentUser;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (isExempt(request)) {
      chain.doFilter(request, response);
      return;
    }

    String id = request.getHeader("X-User-Id");
    if (id == null || id.isEmpty()) {
      writeError(
          response,
          new ApiExceptionHandler.ErrorBody(
              401, "identity:missing", "X-User-Id header is required."));
      return;
    }

    Optional<dev.drim.relay.store.entity.UserRow> row = users.findById(id);
    if (row.isEmpty()) {
      writeError(
          response,
          new ApiExceptionHandler.ErrorBody(
              401, "identity:unknown", "X-User-Id does not match a known user."));
      return;
    }

    currentUser.set(row.get().toDomain());
    chain.doFilter(request, response);
  }

  private static boolean isExempt(HttpServletRequest request) {
    return HttpMethod.POST.matches(request.getMethod())
        && "/users".equals(request.getServletPath());
  }

  private void writeError(HttpServletResponse response, ApiExceptionHandler.ErrorBody body)
      throws IOException {
    response.setStatus(body.status());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.setHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8");
    objectMapper.writeValue(response.getOutputStream(), body);
  }
}
