"""The MinIO-backed attachment object store (S3 over boto3).

Bytes live behind an opaque storage key; the API NEVER derives authorization
from key possession (that lives in the AttachmentAccess seam).
"""

from __future__ import annotations

from relay.infra import BUCKET


class S3Store:
    def __init__(self, client: object) -> None:
        self._client = client

    def put(self, key: str, data: bytes) -> None:
        self._client.put_object(
            Bucket=BUCKET, Key=key, Body=data, ContentType="application/octet-stream"
        )

    def get(self, key: str) -> bytes:
        response = self._client.get_object(Bucket=BUCKET, Key=key)
        return response["Body"].read()

    def delete_all(self) -> None:
        paginator = self._client.get_paginator("list_objects_v2")
        for page in paginator.paginate(Bucket=BUCKET):
            for obj in page.get("Contents", []):
                self._client.delete_object(Bucket=BUCKET, Key=obj["Key"])
