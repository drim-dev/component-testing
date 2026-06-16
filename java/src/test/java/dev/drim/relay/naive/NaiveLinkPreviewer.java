package dev.drim.relay.naive;

import dev.drim.relay.seams.LinkPreviewer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * The G-HTTP naive variant (exhibit, NEVER in src/): it calls the upstream with NO timeout, NO
 * circuit breaker, and lets any failure ESCAPE (no graceful empty). On a 500 / slow upstream the
 * exception propagates out of the post handler, so a failing unfurl turns a message post into a 500
 * instead of a 201 with a null preview. Caught by LinkPreviewTest S-LP-03 + GHttpNaiveDemoTest.
 */
public final class NaiveLinkPreviewer implements LinkPreviewer {
  private final HttpClient http = HttpClient.newHttpClient();

  @Override
  public Optional<String> preview(String url) {
    try {
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build();
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      // The bug: a non-2xx is not handled gracefully — it is thrown, escaping the post handler.
      if (resp.statusCode() >= 400) {
        throw new IllegalStateException("unfurl upstream returned " + resp.statusCode());
      }
      return Optional.of("naive");
    } catch (java.io.IOException | InterruptedException e) {
      throw new IllegalStateException("unfurl failed", e);
    }
  }
}
