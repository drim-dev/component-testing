"""A single boot smoke test — confirms the Fixture brings up all 8 deps, the
correct app answers over the real HTTP boundary, and the round trip persists.
Deleted-or-kept once the full catalog is green; it is the harness's heartbeat.
"""

from __future__ import annotations

from harness.fixture import Fixture
from tests.helpers import client_for, db_count, seed_user


def test_smoke_user_round_trip(fixture: Fixture) -> None:
    user = seed_user(fixture, "smokeuser")
    assert user.id

    fetched = client_for(fixture, user.id).get(f"/users/{user.id}")
    fetched.expect(200)
    assert fetched.json()["handle"] == "smokeuser"

    assert db_count(fixture, "users", "id = :id", {"id": user.id}) == 1
