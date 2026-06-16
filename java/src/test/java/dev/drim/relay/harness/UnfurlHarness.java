package dev.drim.relay.harness;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The outbound-HTTP harness: a REAL local stub server (not an in-process client mock — the timeout,
 * the socket, and the status codes must be real). Seed = program the route (200+title / delay &gt;
 * timeout / 500 / connection reset); Assert = received-request count (circuit-breaker proof); Reset
 * = clear route + counter. Mirrors go/harness/unfurl.go.
 */
public final class UnfurlHarness implements DependencyHarness {
  private enum Mode {
    OK,
    DELAY,
    SERVER_ERROR,
    RESET
  }

  private final Object lock = new Object();
  private final AtomicInteger requests = new AtomicInteger(0);

  private HttpServer server;
  private Mode mode = Mode.OK;
  private String title = "Example";
  private long delayMs = 0;

  @Override
  public void start() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/unfurl", this::handle);
      server.start();
    } catch (IOException e) {
      throw new IllegalStateException("unfurl stub start failed", e);
    }
    mode = Mode.OK;
    title = "Example";
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** The number of /unfurl requests the stub received (breaker proof). */
  public int requestCount() {
    return requests.get();
  }

  @Override
  public void reset() {
    synchronized (lock) {
      mode = Mode.OK;
      title = "Example";
      delayMs = 0;
    }
    requests.set(0);
  }

  @Override
  public void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  /** Programs a 200 response with the given title. */
  public void programOk(String title) {
    synchronized (lock) {
      this.mode = Mode.OK;
      this.title = title;
    }
  }

  /** Programs a response slower than the 800 ms client timeout. */
  public void programDelay(long delayMs) {
    synchronized (lock) {
      this.mode = Mode.DELAY;
      this.delayMs = delayMs;
    }
  }

  /** Programs a 500 response. */
  public void programServerError() {
    synchronized (lock) {
      this.mode = Mode.SERVER_ERROR;
    }
  }

  /** Programs an abrupt connection close (no HTTP response). */
  public void programConnectionReset() {
    synchronized (lock) {
      this.mode = Mode.RESET;
    }
  }

  private void handle(HttpExchange exchange) throws IOException {
    requests.incrementAndGet();
    Mode m;
    String t;
    long d;
    synchronized (lock) {
      m = mode;
      t = title;
      d = delayMs;
    }
    switch (m) {
      case DELAY -> {
        sleep(d);
        respondJson(exchange, t);
      }
      case SERVER_ERROR -> {
        exchange.sendResponseHeaders(500, -1);
        exchange.close();
      }
      case RESET -> exchange.close();
      default -> respondJson(exchange, t);
    }
  }

  private static void respondJson(HttpExchange exchange, String title) throws IOException {
    byte[] body =
        ("{\"title\":\"" + title.replace("\"", "\\\"") + "\"}").getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
