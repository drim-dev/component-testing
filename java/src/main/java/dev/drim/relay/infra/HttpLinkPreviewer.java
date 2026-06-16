package dev.drim.relay.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.drim.relay.seams.LinkPreviewer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The correct G-HTTP seam: a REAL HTTP client with an 800 ms timeout that degrades gracefully
 * (timeout / non-2xx / network error → empty title, never escapes), behind a circuit breaker (5
 * consecutive failures → skip the call for 30 s). Breaker state lives in Redis (resettable by the
 * harness FLUSHDB). The naive variant has no timeout/guard. Mirrors
 * go/src/relay/infra/linkpreview.go.
 */
@Component
public class HttpLinkPreviewer implements LinkPreviewer {
  private static final Duration TIMEOUT = Duration.ofMillis(800);
  private static final long BREAKER_THRESHOLD = 5;
  private static final Duration BREAKER_WINDOW = Duration.ofSeconds(30);
  private static final String FAILURES_KEY = "unfurl:breaker:failures";
  private static final String OPEN_UNTIL_KEY = "unfurl:breaker:open_until";

  private final HttpClient http;
  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;
  private final String baseUrl;

  public HttpLinkPreviewer(
      StringRedisTemplate redis,
      ObjectMapper mapper,
      @Value("${relay.unfurl.base-url:http://localhost:0}") String baseUrl) {
    this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    this.redis = redis;
    this.mapper = mapper;
    this.baseUrl = baseUrl;
  }

  @Override
  public Optional<String> preview(String url) {
    if (breakerOpen()) {
      return Optional.empty();
    }
    Optional<String> title = fetch(url);
    if (title.isEmpty()) {
      recordFailure();
      return Optional.empty();
    }
    recordSuccess();
    return title;
  }

  private Optional<String> fetch(String target) {
    try {
      String endpoint =
          baseUrl + "/unfurl?url=" + URLEncoder.encode(target, StandardCharsets.UTF_8);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(endpoint)).timeout(TIMEOUT).GET().build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return Optional.empty();
      }
      JsonNode payload = mapper.readTree(response.body());
      JsonNode titleNode = payload.get("title");
      if (titleNode == null) {
        return Optional.of("");
      }
      return Optional.of(titleNode.asText());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  private boolean breakerOpen() {
    String raw = redis.opsForValue().get(OPEN_UNTIL_KEY);
    if (raw == null) {
      return false;
    }
    long openUntil = Long.parseLong(raw);
    return openUntil > System.currentTimeMillis();
  }

  private void recordSuccess() {
    redis.delete(FAILURES_KEY);
  }

  private void recordFailure() {
    Long failures = redis.opsForValue().increment(FAILURES_KEY);
    if (failures != null && failures >= BREAKER_THRESHOLD) {
      long openUntil = System.currentTimeMillis() + BREAKER_WINDOW.toMillis();
      redis.opsForValue().set(OPEN_UNTIL_KEY, Long.toString(openUntil), BREAKER_WINDOW);
    }
  }
}
