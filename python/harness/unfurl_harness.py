"""The outbound-HTTP harness: a REAL local stub server (not an in-process client
mock — the timeout, the socket, and the status codes must be real).

Seed = program the route (200+title / delay > timeout / 500). Assert =
received-request count (circuit-breaker proof). Reset = clear route + counter.
"""

from __future__ import annotations

import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class UnfurlHarness:
    def __init__(self) -> None:
        self._server: ThreadingHTTPServer | None = None
        self._thread: threading.Thread | None = None
        self._lock = threading.Lock()
        self._mode = "ok"
        self._title = "Example"
        self._delay = 0.0
        self._requests = 0

    def start(self) -> None:
        harness = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:  # noqa: N802 (BaseHTTPRequestHandler API)
                harness._serve(self)

            def log_message(self, *_args) -> None:
                pass  # keep test output pristine

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()

    @property
    def base_url(self) -> str:
        host, port = self._server.server_address
        return f"http://{host}:{port}"

    @property
    def request_count(self) -> int:
        with self._lock:
            return self._requests

    def program_ok(self, title: str) -> None:
        with self._lock:
            self._mode = "ok"
            self._title = title

    def program_delay(self, delay: float) -> None:
        with self._lock:
            self._mode = "delay"
            self._delay = delay

    def program_server_error(self) -> None:
        with self._lock:
            self._mode = "500"

    def reset(self) -> None:
        with self._lock:
            self._mode = "ok"
            self._title = "Example"
            self._delay = 0.0
            self._requests = 0

    def stop(self) -> None:
        if self._server is not None:
            self._server.shutdown()
            self._server.server_close()

    def _serve(self, handler: BaseHTTPRequestHandler) -> None:
        with self._lock:
            self._requests += 1
            mode, title, delay = self._mode, self._title, self._delay
        if mode == "delay":
            time.sleep(delay)
            _write_json(handler, {"title": title})
        elif mode == "500":
            handler.send_response(500)
            handler.end_headers()
        else:
            _write_json(handler, {"title": title})


def _write_json(handler: BaseHTTPRequestHandler, body: dict) -> None:
    payload = json.dumps(body).encode()
    handler.send_response(200)
    handler.send_header("Content-Type", "application/json")
    handler.send_header("Content-Length", str(len(payload)))
    handler.end_headers()
    handler.wfile.write(payload)
