"""
Regression test for Finding #34 (HIGH, logic error).

The `/api/v1/llm/chat` docstring promises "Timeout: MAX_GENERATION_SECONDS"
applies to the endpoint as a whole. The non-streaming branch enforces this
via `asyncio.wait_for`, but the streaming branch (`_handle_streaming_chat` /
`generate_stream`) never applied any deadline to the token loop, so a
stuck/slow backend generator would stream forever with no server-side
cutoff.
"""
import asyncio
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from app import main as main_module  # noqa: E402


PER_TOKEN_DELAY = 0.02
# Large enough that, without a deadline, the full stream would take far
# longer than the configured MAX_GENERATION_SECONDS below (20 * 0.02s = 0.4s
# vs a 0.05s deadline) - proving the cutoff actually stopped the stream
# early rather than it just happening to finish quickly on its own.
TOKEN_COUNT = 20
MAX_GENERATION_SECONDS = 0.05


class _SlowTokenGenerator:
    """Stands in for a stuck/slow backend token generator."""

    def __init__(self, n_tokens: int, delay: float):
        self._remaining = n_tokens
        self._delay = delay

    def __iter__(self):
        return self

    def __next__(self):
        if self._remaining <= 0:
            raise StopIteration
        time.sleep(self._delay)
        self._remaining -= 1
        return f"tok{self._remaining}"


def _make_chat_request():
    return main_module.ChatRequest(
        messages=[main_module.ChatMessage(role="user", content="hi")],
        stream=True,
    )


async def _run_streaming(monkeypatch):
    monkeypatch.setattr(main_module.config, "MAX_GENERATION_SECONDS", MAX_GENERATION_SECONDS)
    monkeypatch.setattr(main_module.model_loader, "supports_chat_messages", lambda: False)
    monkeypatch.setattr(
        main_module.model_loader,
        "generate_stream",
        lambda **kwargs: _SlowTokenGenerator(TOKEN_COUNT, PER_TOKEN_DELAY),
    )
    monkeypatch.setattr(main_module.chat_handler, "format_prompt", lambda messages: "prompt")

    response = await main_module._handle_streaming_chat(_make_chat_request(), "test-corr-id")

    chunks = []
    async for chunk in response.body_iterator:
        chunks.append(chunk)
    return chunks


def test_streaming_chat_enforces_max_generation_seconds(monkeypatch):
    """
    FAILS without the fix: the token loop has no deadline check, so the
    stream runs all TOKEN_COUNT tokens to completion (~0.4s) regardless of
    the tiny configured MAX_GENERATION_SECONDS, and ends with `[DONE]`
    instead of a timeout error.

    PASSES with the fix: the deadline is checked before each token fetch,
    so the stream is cut short well before all tokens are produced and
    well before the full (unbounded) generation window elapses.
    """
    start = time.monotonic()
    chunks = asyncio.run(_run_streaming(monkeypatch))
    elapsed = time.monotonic() - start

    total_unbounded_window = TOKEN_COUNT * PER_TOKEN_DELAY

    # The fixed behavior must cut the stream off close to
    # MAX_GENERATION_SECONDS, well before the full unbounded window.
    assert elapsed < total_unbounded_window / 2, (
        f"stream ran for {elapsed:.3f}s (unbounded window would have been "
        f"{total_unbounded_window:.3f}s) - the MAX_GENERATION_SECONDS "
        "deadline was not enforced on the streaming path"
    )

    token_chunks = [c for c in chunks if c.startswith("data: tok")]
    assert len(token_chunks) < TOKEN_COUNT, (
        "expected the stream to be cut short before producing every token"
    )

    assert not any("[DONE]" in c for c in chunks), (
        "stream reached normal completion instead of being cut off by the "
        "generation deadline"
    )
    assert any("[ERROR]" in c for c in chunks), (
        "expected a terminal SSE error event reporting the timeout"
    )
