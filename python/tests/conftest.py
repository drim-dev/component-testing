"""Pytest plumbing for the Relay component suite.

One shared Fixture (one Docker host, one suite), started once per session; each
test resets all dependencies first (the suite is shared and serial — never run
two Testcontainers suites on one Docker host). The suite covers the
06-acceptance catalog 1:1 through the REAL HTTP boundary against REAL deps, plus
the paired lying/naive exhibits from 05-gallery.
"""

from __future__ import annotations

from collections.abc import Iterator

import pytest

from harness.fixture import Fixture


@pytest.fixture(scope="session")
def fixture() -> Iterator[Fixture]:
    composed = Fixture()
    composed.start()
    try:
        yield composed
    finally:
        composed.stop()


@pytest.fixture(autouse=True)
def _reset(fixture: Fixture) -> None:
    fixture.reset()
