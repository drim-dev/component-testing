"""Attachments (S-AT) acceptance scenarios, including the catching halves of the
G-S3 case (download authorization derives from channel membership).
"""

from __future__ import annotations

from harness.fixture import Fixture
from tests.helpers import client_for, seed_channel, seed_user


def _upload(
    fixture: Fixture, user_id: str, channel_id: str, content: bytes, filename: str = "f.bin"
):
    return client_for(fixture, user_id).post_multipart(
        f"/channels/{channel_id}/attachments",
        {"file": (filename, content, "application/octet-stream")},
    )


def test_s_at_01_member_uploads(fixture: Fixture) -> None:
    owner = seed_user(fixture, "atowner")
    public_id = seed_channel(fixture, owner, "pub", private=False)
    content = b"x" * (10 * 1024)
    response = _upload(fixture, owner.id, public_id, content, "doc.bin")
    response.expect(201)
    body = response.json()
    assert body["sizeBytes"] == len(content)
    assert body["filename"] == "doc.bin"
    # Bytes landed in MinIO under the storage key (channel/id).
    assert fixture.s3.object_bytes(f"{public_id}/{body['id']}") == content


def test_s_at_02_non_member_upload(fixture: Fixture) -> None:
    owner = seed_user(fixture, "atowner")
    outsider = seed_user(fixture, "atout")
    public_id = seed_channel(fixture, owner, "pub", private=False)
    private_id = seed_channel(fixture, owner, "priv", private=True)
    _upload(fixture, outsider.id, public_id, b"data").expect(403)
    _upload(fixture, outsider.id, private_id, b"data").expect(404)


def test_s_at_03_size_limits(fixture: Fixture) -> None:
    owner = seed_user(fixture, "atowner")
    public_id = seed_channel(fixture, owner, "pub", private=False)
    _upload(fixture, owner.id, public_id, b"x" * (1024 * 1024 + 1)).expect(413).expect_code(
        "attachment:too_large"
    )
    _upload(fixture, owner.id, public_id, b"").expect(422).expect_code("attachment:empty")


def test_s_at_04_reference_attachment_in_message(fixture: Fixture) -> None:
    owner = seed_user(fixture, "atowner")
    other = seed_user(fixture, "atother")
    public_id = seed_channel(fixture, owner, "pub", private=False)
    other_channel = seed_channel(fixture, owner, "pub2", private=False)
    client_for(fixture, other.id).post(f"/channels/{public_id}/join").expect(201)

    mine = _upload(fixture, owner.id, public_id, b"mine").json()["id"]
    response = client_for(fixture, owner.id).post(
        f"/channels/{public_id}/messages", {"text": "see attached", "attachmentIds": [mine]}
    )
    response.expect(201)
    assert response.json()["attachmentIds"] == [mine]

    # Referencing another user's attachment → 422.
    theirs = _upload(fixture, other.id, public_id, b"theirs").json()["id"]
    client_for(fixture, owner.id).post(
        f"/channels/{public_id}/messages", {"text": "x", "attachmentIds": [theirs]}
    ).expect(422).expect_code("message:attachment:invalid")

    # Referencing an attachment from another channel → 422.
    elsewhere = _upload(fixture, owner.id, other_channel, b"elsewhere").json()["id"]
    client_for(fixture, owner.id).post(
        f"/channels/{public_id}/messages", {"text": "x", "attachmentIds": [elsewhere]}
    ).expect(422).expect_code("message:attachment:invalid")


def test_s_at_05_member_downloads(fixture: Fixture) -> None:
    owner = seed_user(fixture, "atowner")
    public_id = seed_channel(fixture, owner, "pub", private=False)
    content = b"download-me"
    attachment_id = _upload(fixture, owner.id, public_id, content, "report.pdf").json()["id"]

    response = client_for(fixture, owner.id).get(f"/attachments/{attachment_id}")
    response.expect(200)
    assert response.raw == content
    assert "report.pdf" in response.headers["content-disposition"]


def test_s_at_06_s3_private_non_member_download_404(fixture: Fixture) -> None:
    """[G-S3] non-member downloads private-channel attachment by id → 404, zero
    bytes; body identical to unknown-id 404."""
    owner = seed_user(fixture, "atowner")
    outsider = seed_user(fixture, "atout")
    private_id = seed_channel(fixture, owner, "priv", private=True)
    attachment_id = _upload(fixture, owner.id, private_id, b"secret-bytes").json()["id"]

    forbidden = client_for(fixture, outsider.id).get(f"/attachments/{attachment_id}")
    forbidden.expect(404).expect_code("attachment:not_found")
    assert b"secret-bytes" not in forbidden.raw
    unknown = client_for(fixture, outsider.id).get("/attachments/does-not-exist")
    unknown.expect(404)
    assert forbidden.raw == unknown.raw


def test_s_at_07_s3_public_non_member_download_403(fixture: Fixture) -> None:
    """[G-S3] non-member downloads public-channel attachment → 403, zero bytes."""
    owner = seed_user(fixture, "atowner")
    outsider = seed_user(fixture, "atout")
    public_id = seed_channel(fixture, owner, "pub", private=False)
    attachment_id = _upload(fixture, owner.id, public_id, b"public-bytes").json()["id"]

    response = client_for(fixture, outsider.id).get(f"/attachments/{attachment_id}")
    response.expect(403).expect_code("channel:membership_required")
    assert b"public-bytes" not in response.raw
