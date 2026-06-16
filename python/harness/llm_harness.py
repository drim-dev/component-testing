"""The canonical FAKE (04-dependencies.md §6): nondeterministic, paid, external,
so the boundary is a deliberate in-process double, not a container.

Seed = program the next response (canned / empty / oversized). Assert =
interaction verification — the captured request is where the prompt-injection
catch lives. Reset = clear responses + captured calls. Hand-rolled on purpose
(no mocking framework) so the pattern reads cross-language.
"""

from __future__ import annotations

import threading

from relay import domain


class LlmHarness:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._programmed: list[str] = []
        self._captured: list[domain.SummaryRequest] = []

    def start(self) -> None:
        pass

    def reset(self) -> None:
        self.clear()

    def stop(self) -> None:
        pass

    @property
    def model(self) -> _FakeSummaryModel:
        """The fake as the app's SummaryModel seam."""
        return _FakeSummaryModel(self)

    def program_response(self, response: str) -> None:
        """Seed the next response (FIFO). Unprogrammed → a canned summary."""
        with self._lock:
            self._programmed.append(response)

    def captured_requests(self) -> list[domain.SummaryRequest]:
        """The requests the app made — for interaction verification."""
        with self._lock:
            return list(self._captured)

    def clear(self) -> None:
        with self._lock:
            self._programmed.clear()
            self._captured.clear()

    def _complete(self, request: domain.SummaryRequest) -> str:
        with self._lock:
            self._captured.append(request)
            if self._programmed:
                return self._programmed.pop(0)
        return "(canned summary)"


class _FakeSummaryModel:
    def __init__(self, harness: LlmHarness) -> None:
        self._harness = harness

    def complete(self, request: domain.SummaryRequest) -> str:
        return self._harness._complete(request)
