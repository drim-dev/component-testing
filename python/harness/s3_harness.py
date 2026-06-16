"""The MinIO harness (object storage over real HTTP). Real container; the bucket
is created at boot. Seed = put objects directly. Assert = get/list + compare
bytes. Reset = delete all objects (the bucket survives). S3 *could* be faked,
but real HTTP+auth+streaming is where bugs live (the container-vs-fake decision).

The minio testcontainers module is not installed (we use boto3 as the client),
so MinIO is stood up via a generic DockerContainer with the digest-pinned image.
"""

from __future__ import annotations

import time

import boto3
import httpx
from botocore.client import Config
from testcontainers.core.container import DockerContainer

from harness.images import MINIO_IMAGE
from relay.infra import BUCKET

_ACCESS_KEY = "relayadmin"
_SECRET_KEY = "relaysecret"


class S3Harness:
    def __init__(self) -> None:
        self._container: DockerContainer | None = None
        self._client = None
        self._endpoint = ""

    def start(self) -> None:
        container = (
            DockerContainer(MINIO_IMAGE)
            .with_exposed_ports(9000)
            .with_env("MINIO_ROOT_USER", _ACCESS_KEY)
            .with_env("MINIO_ROOT_PASSWORD", _SECRET_KEY)
            .with_command('server /data')
        )
        container.start()
        self._container = container
        host = container.get_container_host_ip()
        port = int(container.get_exposed_port(9000))
        self._endpoint = f"http://{host}:{port}"
        self._await_ready()

        self._client = boto3.client(
            "s3",
            endpoint_url=self._endpoint,
            aws_access_key_id=_ACCESS_KEY,
            aws_secret_access_key=_SECRET_KEY,
            config=Config(signature_version="s3v4"),
            region_name="us-east-1",
        )
        self._client.create_bucket(Bucket=BUCKET)

    @property
    def client(self):
        return self._client

    def _await_ready(self, deadline: float = 60.0) -> None:
        stop = time.monotonic() + deadline
        while time.monotonic() < stop:
            try:
                response = httpx.get(self._endpoint + "/minio/health/ready", timeout=2)
                if response.status_code == 200:
                    return
            except httpx.HTTPError:
                pass
            time.sleep(0.25)
        raise RuntimeError("MinIO did not become ready")

    def object_bytes(self, key: str) -> bytes:
        """Read a stored object — an Assert that bytes landed under the storage key."""
        return self._client.get_object(Bucket=BUCKET, Key=key)["Body"].read()

    def object_exists(self, key: str) -> bool:
        from botocore.exceptions import ClientError

        try:
            self._client.head_object(Bucket=BUCKET, Key=key)
            return True
        except ClientError:
            return False

    def reset(self) -> None:
        paginator = self._client.get_paginator("list_objects_v2")
        for page in paginator.paginate(Bucket=BUCKET):
            for obj in page.get("Contents", []):
                self._client.delete_object(Bucket=BUCKET, Key=obj["Key"])

    def stop(self) -> None:
        if self._container is not None:
            self._container.stop()
