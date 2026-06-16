package dev.drim.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.drim.relay.infra.EventCodecs;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Drives the assembled correct app's REAL HTTP boundary as a given user — the Java analog of the Go
 * suite's {@code client}. JSON in/out via the canonical mapper, {@code X-User-Id} attached unless
 * the user is null (the anonymous/identity scenarios). The {@link Response} wrapper carries the
 * pinned-status / pinned-code assertions every error scenario uses.
 */
public final class RelayClient {
  private static final ObjectMapper MAPPER = EventCodecs.canonicalMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private final String baseUrl;
  private final String userId;

  public RelayClient(String baseUrl, String userId) {
    this.baseUrl = baseUrl;
    this.userId = userId;
  }

  public Response get(String path) {
    return send(builder(path).GET());
  }

  public Response post(String path, Object body) {
    return send(jsonBody(builder(path), body));
  }

  public Response delete(String path) {
    return send(builder(path).DELETE());
  }

  /**
   * A {@code multipart/form-data} POST with a single file part named {@code file} (the attachment
   * upload endpoint's field). Hand-builds the multipart body so no extra HTTP-client dep is needed.
   */
  public Response postFile(String path, String filename, byte[] content) {
    String boundary = "----relayBoundary" + System.nanoTime();
    var out = new java.io.ByteArrayOutputStream();
    try {
      String header =
          "--"
              + boundary
              + "\r\n"
              + "Content-Disposition: form-data; name=\"file\"; filename=\""
              + filename
              + "\"\r\n"
              + "Content-Type: application/octet-stream\r\n\r\n";
      out.write(header.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      out.write(content);
      out.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("build multipart failed", e);
    }
    HttpRequest.Builder b =
        builder(path)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
    return send(b);
  }

  private HttpRequest.Builder builder(String path) {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15));
    if (userId != null) {
      b.header("X-User-Id", userId);
    }
    return b;
  }

  private static HttpRequest.Builder jsonBody(HttpRequest.Builder b, Object body) {
    try {
      byte[] raw = MAPPER.writeValueAsBytes(body);
      return b.header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofByteArray(raw));
    } catch (Exception e) {
      throw new IllegalStateException("marshal request body failed", e);
    }
  }

  private static Response send(HttpRequest.Builder b) {
    try {
      HttpResponse<byte[]> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
      return new Response(resp.statusCode(), resp.body(), resp);
    } catch (Exception e) {
      throw new IllegalStateException("request failed", e);
    }
  }

  /** An HTTP reply with the assertion helpers every scenario leans on. */
  public static final class Response {
    private final int status;
    private final byte[] body;
    private final HttpResponse<byte[]> raw;

    Response(int status, byte[] body, HttpResponse<byte[]> raw) {
      this.status = status;
      this.body = body;
      this.raw = raw;
    }

    public int status() {
      return status;
    }

    public byte[] bytes() {
      return body;
    }

    public String bodyString() {
      return new String(body);
    }

    public String header(String name) {
      return raw.headers().firstValue(name).orElse(null);
    }

    public Response expectStatus(int expected) {
      assertThat(status).as("status (body: %s)", new String(body)).isEqualTo(expected);
      return this;
    }

    /** Asserts the ProblemDetails {@code code} field — the i18n error contract. */
    public Response expectCode(String expected) {
      assertThat(json().path("code").asText())
          .as("error code (body: %s)", new String(body))
          .isEqualTo(expected);
      return this;
    }

    public JsonNode json() {
      try {
        return MAPPER.readTree(body);
      } catch (Exception e) {
        throw new IllegalStateException("decode body failed (raw: " + new String(body) + ")", e);
      }
    }

    public String string(String field) {
      return json().path(field).asText();
    }
  }

  /** Convenience: a JSON object body from a map literal. */
  public static Map<String, Object> body(Object... kv) {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }
}
