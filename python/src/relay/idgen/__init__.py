"""Mints opaque, time-ordered, URL-safe string ids. The spec treats ids as
opaque non-empty strings (02-api.md §0); time-ordering keeps newest-first
pagination stable. A Factory carries a generator id so a second factory (the
naive test host) never collides with ids minted by the suite's seeding factory.
"""

from __future__ import annotations

import os
import threading
import time

# Crockford Base32 alphabet (URL-safe, unambiguous).
_CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


class Factory:
    """Generates time-ordered ids. Safe for concurrent use."""

    def __init__(self, generator_id: int) -> None:
        self._generator_id = generator_id & 0xFFFF
        self._seq = int.from_bytes(os.urandom(4), "big")
        self._lock = threading.Lock()

    def create(self) -> str:
        """A new id: 48-bit millisecond timestamp + 16-bit generator id + 32-bit
        monotonic sequence, Crockford-Base32 encoded. The leading timestamp makes
        the lexicographic order of the encoded string match creation order."""
        with self._lock:
            self._seq = (self._seq + 1) & 0xFFFFFFFF
            seq = self._seq
        ms = int(time.time() * 1000)
        raw = (
            (ms & 0xFFFFFFFFFFFF).to_bytes(6, "big")
            + self._generator_id.to_bytes(2, "big")
            + seq.to_bytes(4, "big")
        )
        return _encode(raw)


def _encode(data: bytes) -> str:
    out: list[str] = []
    buffer = 0
    bits = 0
    for byte in data:
        buffer = (buffer << 8) | byte
        bits += 8
        while bits >= 5:
            bits -= 5
            out.append(_CROCKFORD[(buffer >> bits) & 0x1F])
    if bits > 0:
        out.append(_CROCKFORD[(buffer << (5 - bits)) & 0x1F])
    return "".join(out)
