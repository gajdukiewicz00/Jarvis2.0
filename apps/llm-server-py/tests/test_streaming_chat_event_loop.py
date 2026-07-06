"""
Regression test for Finding #12 (CRITICAL, event-loop freeze).

The streaming chat endpoint (`_handle_streaming_chat` in app/main.py) used to
iterate the backend's blocking token generator directly inside an
`async def` function:

    for token in stream:
        yield f"data: {token}\n\n"

`stream.__next__()` performs synchronous, CPU/GPU-bound model inference.
Calling it directly on the event-loop thread blocks the entire asyncio
event loop for as long as generation takes - freezing every other
concurrent request (including /health) for the whole streamed response.

This test proves the event loop keeps making progress (a concurrent
"heartbeat" coroutine keeps ticking at ~its scheduled interval) while a
slow, blocking token generator is being streamed. Without the fix, the
heartbeat starves for the entire blocking-generation window and the max
observed gap between ticks blows way past the assertion threshold.
"""
import asyncio
import os
import sys
import time

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from app import main as main_module  # noqa: E402


TOKEN_COUNT = 6
PER_TOKEN_BLOCKING_DELAY = 0.05  # simulated blocking model inference per token
HEARTBEAT_INTERVAL = 0.01
HEARTBEAT_TICKS = 40  # ~0.4s of monitoring, comfortably longer than the stream


class _BlockingTokenGenerator:
    """
    Stands in for a real backend generator (llama.cpp / transformers).
    `__next__` performs blocking, synchronous "work" via time.sleep, exactly
    like real token generation blocks on CPU/GPU compute rather than doing
    any awaiting.
    """

    def __init__(self, n_tokens: int, delay: float):
        self._remaining = n_tokens
        self._delay = delay

    def __iter__(self):
        return self

    def __next__(self):
        if self._remaining <= 0:
            raise StopIteration
        time.sleep(self._delay)  # blocking call - never awaited
        self._remaining -= 1
        return f"tok{self._remaining}"


def _make_chat_request():
    return main_module.ChatRequest(
        messages=[main_module.ChatMessage(role="user", content="hi")],
        stream=True,
    )


async def _run_streaming_with_heartbeat(monkeypatch):
    monkeypatch.setattr(
        main_module.model_loader, "supports_chat_messages", lambda: False
    )
    monkeypatch.setattr(
        main_module.model_loader,
        "generate_stream",
        lambda **kwargs: _BlockingTokenGenerator(TOKEN_COUNT, PER_TOKEN_BLOCKING_DELAY),
    )
    monkeypatch.setattr(
        main_module.chat_handler, "format_prompt", lambda messages: "prompt"
    )

    request = _make_chat_request()
    response = await main_module._handle_streaming_chat(request, "test-corr-id")

    heartbeat_gaps = []

    async def heartbeat():
        last = time.monotonic()
        for _ in range(HEARTBEAT_TICKS):
            await asyncio.sleep(HEARTBEAT_INTERVAL)
            now = time.monotonic()
            heartbeat_gaps.append(now - last)
            last = now

    async def consume_stream():
        chunks = []
        async for chunk in response.body_iterator:
            chunks.append(chunk)
        return chunks

    # Both must be independent Tasks (not a bare `await consume_stream()`
    # inline call) so the loop actually gets a chance to interleave them.
    # If `consume_stream()` were awaited directly instead of scheduled as
    # its own Task, driving it wouldn't hand control back to the loop
    # until a genuine suspension point is hit inside it - which is exactly
    # the bug under test - making it impossible to observe the starvation
    # of a task that hasn't been scheduled yet.
    heartbeat_task = asyncio.create_task(heartbeat())
    consume_task = asyncio.create_task(consume_stream())
    chunks, _ = await asyncio.gather(consume_task, heartbeat_task)

    return chunks, heartbeat_gaps


def test_streaming_chat_keeps_event_loop_responsive(monkeypatch):
    """
    FAILS without the fix: iterating the blocking generator directly inside
    the async generator starves the heartbeat coroutine for the whole
    TOKEN_COUNT * PER_TOKEN_BLOCKING_DELAY window, producing a heartbeat gap
    close to that full duration (~0.3s) instead of ~HEARTBEAT_INTERVAL.

    PASSES with the fix: each blocking `next()` call runs in the thread-pool
    executor, so the event loop keeps servicing the heartbeat coroutine
    (and, in production, /health and other requests) between tokens.
    """
    chunks, heartbeat_gaps = asyncio.run(_run_streaming_with_heartbeat(monkeypatch))

    # Sanity: the stream still produced all tokens plus a completion event.
    assert any("tok0" in c for c in chunks)
    assert sum(1 for c in chunks if c.startswith("data: tok")) == TOKEN_COUNT
    assert any("[DONE]" in c for c in chunks)

    assert heartbeat_gaps, "heartbeat never ran concurrently with the stream"
    max_gap = max(heartbeat_gaps)
    total_blocking_window = TOKEN_COUNT * PER_TOKEN_BLOCKING_DELAY

    # A healthy event loop keeps heartbeat gaps near HEARTBEAT_INTERVAL.
    # A frozen loop shows a gap close to the full blocking window because
    # the whole stream runs to completion before the loop can service
    # anything else.
    assert max_gap < total_blocking_window / 2, (
        f"event loop was blocked for {max_gap:.3f}s while streaming "
        f"(blocking window was {total_blocking_window:.3f}s) - the token "
        "generator is running on the event-loop thread instead of a "
        "worker thread"
    )


def test_streaming_chat_sse_contract_preserved(monkeypatch):
    """The fix must not change the SSE response contract."""
    chunks, _ = asyncio.run(_run_streaming_with_heartbeat(monkeypatch))

    assert all(chunk.startswith("data: ") and chunk.endswith("\n\n") for chunk in chunks)
    assert chunks[-1] == "data: [DONE]\n\n"
