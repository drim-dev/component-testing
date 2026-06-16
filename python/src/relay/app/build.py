"""Composition root: assemble correct ``Deps`` from infrastructure handles, and
build the FastAPI app (routers + the ApiError → pinned-body handler).
"""

from __future__ import annotations

import logging

import grpc
import pika
import redis
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from relay import apierr
from relay.app import seams_impl
from relay.app.attachments_routes import router as attachments_router
from relay.app.channels_routes import router as channels_router
from relay.app.deps import Deps
from relay.app.dm_routes import router as dm_router
from relay.app.feed_routes import router as feed_router
from relay.app.identity import require_user  # noqa: F401 (ensures import wiring is exercised)
from relay.app.linkpreview_routes import router as linkpreview_router
from relay.app.presence_routes import router as presence_router
from relay.app.summary_routes import router as summary_router
from relay.app.users_routes import router as users_router
from relay.idgen import Factory
from relay.infra import MESSAGE_POSTED_TOPIC, NOTIFY_QUEUE
from relay.infra.kafka_infra import KafkaPublisher
from relay.infra.link_previewer import LinkPreviewer
from relay.infra.rabbit_infra import NotificationJobs
from relay.infra.redis_infra import Heartbeats, MembershipCache, UnreadCounters
from relay.infra.s3_infra import S3Store
from relay.presence.client import PresenceClient
from relay.store import Store

log = logging.getLogger("relay")


def build_correct_deps(
    *,
    store: Store,
    redis_client: redis.Redis,
    kafka_brokers: str,
    rabbit_params: pika.ConnectionParameters,
    s3_client: object,
    presence_channel: grpc.Channel,
    summary_model,
    unfurl_base_url: str,
    generator_id: int = 1,
    kafka_topic: str = MESSAGE_POSTED_TOPIC,
    notify_queue: str = NOTIFY_QUEUE,
) -> Deps:
    """Assemble the CORRECT dependency set. A test rebuilds Deps with exactly
    one seam overridden (via ``app.dependency_overrides``)."""
    ids = Factory(generator_id)
    cache = MembershipCache(redis_client)
    unread = UnreadCounters(redis_client)

    return Deps(
        store=store,
        ids=ids,
        dm_access=seams_impl.DmAccess(store),
        conversation_writer=seams_impl.ConversationWriter(store, ids),
        channel_read_gate=seams_impl.ChannelReadGate(store, cache),
        channel_role_gate=seams_impl.ChannelRoleGate(store),
        membership_writer=seams_impl.MembershipWriter(store, cache),
        publisher=KafkaPublisher(kafka_brokers, kafka_topic),
        notification_recorder=seams_impl.NotificationRecorder(store, ids),
        presence=PresenceClient(presence_channel),
        link_previewer=LinkPreviewer(redis_client, unfurl_base_url),
        attachment_access=seams_impl.AttachmentAccess(store),
        cache=cache,
        unread=unread,
        jobs=NotificationJobs(rabbit_params, notify_queue),
        store3=S3Store(s3_client),
        summary_model=summary_model,
        summarizer=seams_impl.Summarizer(summary_model),
        feed=seams_impl.FeedProjector(store, unread, ids),
        heartbeats=Heartbeats(redis_client),
    )


def create_app(deps: Deps) -> FastAPI:
    """Wire the FastAPI app from ``deps``. The identity dependency, the seam
    providers, and the pinned-body error handler are all attached here."""
    app = FastAPI(title="Relay", docs_url=None, redoc_url=None, openapi_url=None)
    app.state.deps = deps

    app.include_router(users_router)
    app.include_router(dm_router)
    app.include_router(channels_router)
    app.include_router(attachments_router)
    app.include_router(feed_router)
    app.include_router(presence_router)
    app.include_router(summary_router)
    app.include_router(linkpreview_router)

    @app.exception_handler(apierr.ApiError)
    async def _api_error_handler(_request: Request, exc: apierr.ApiError) -> JSONResponse:
        return JSONResponse(status_code=exc.status, content=exc.body())

    @app.exception_handler(Exception)
    async def _unexpected_handler(_request: Request, exc: Exception) -> JSONResponse:
        # Log the cause so a 500 is debuggable (the Java handoff's defect #4 lesson),
        # then return the generic pinned body — never leak internals.
        log.error("unexpected error handling request", exc_info=exc)
        return JSONResponse(
            status_code=500,
            content={
                "status": 500,
                "code": "internal:error",
                "message": "An unexpected error occurred.",
            },
        )

    return app
