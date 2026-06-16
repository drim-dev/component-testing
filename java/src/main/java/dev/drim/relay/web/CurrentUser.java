package dev.drim.relay.web;

import dev.drim.relay.domain.Entities;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Holds the authenticated caller for the current request. {@link IdentityFilter} populates it after
 * resolving the trusted {@code X-User-Id} header; controllers inject this bean and call {@link
 * #require()}. Request-scoped with a proxy so singleton controllers can hold a reference.
 */
@Component
@RequestScope(proxyMode = ScopedProxyMode.TARGET_CLASS)
public class CurrentUser {
  private Entities.User user;

  public void set(Entities.User user) {
    this.user = user;
  }

  /** The caller; never null on an authenticated route (the filter 401s before the controller). */
  public Entities.User require() {
    return user;
  }

  public String id() {
    return user.id();
  }
}
