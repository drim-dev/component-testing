"""The NAIVE gallery variants (05-gallery §0.4). Each implements the SAME seam
Protocol as its correct counterpart with the default-shaped bug an agent ships
(missing wiring, missing invalidation, fire-and-forget, no transaction, no
idempotency). These live ONLY under ``tests/`` — never in ``src/`` — and are
injected through ``app.dependency_overrides`` scoped to one test (the §0.4
mechanism). The expect-failure wrapper proves the catching test goes red
against each.
"""
