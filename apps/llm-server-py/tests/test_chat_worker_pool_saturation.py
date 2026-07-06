"""
Regression test for Finding #35 (HIGH, resource leak).

`asyncio.wait_for` only abandons the *awaiting* coroutine on timeout - it
cannot cancel a call already running in a ThreadPoolExecutor worker thread.
With CHAT_WORKERS defaulting to 1, a single stuck/slow generation used to
tie up the sole worker thread indefinitely, so every subsequent
`/api/v1/llm/chat` request would silently queue behind it (or itself time
out while never actually starting) instead of getting a fast, honest
signal that the server is busy.
"""
import asyncio
import os
import sys
import threading

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from app import main as main_module  # noqa: E402


def _make_chat_request():
    return main_module.ChatRequest(
        messages=[main_module.ChatMessage(role="user", content="hi")],
    )


async def _run_concurrent_requests(monkeypatch):
    # Force a single-slot pool regardless of the ambient CHAT_WORKERS env
    # var, so the test is deterministic.
    monkeypatch.setattr(main_module, "_chat_worker_slots", threading.Semaphore(1))
    monkeypatch.setattr(main_module.model_loader, "is_loaded", lambda: True)
    monkeypatch.setattr(main_module.config, "MAX_GENERATION_SECONDS", 5)

    started = threading.Event()
    release = threading.Event()

    def slow_process_chat(messages, max_tokens, temperature):
        started.set()
        # Simulate a stuck/slow backend call occupying the sole worker
        # thread - exactly what asyncio.wait_for cannot interrupt.
        release.wait(timeout=5)
        return {"reply": "ok", "tokens": {"input": 1, "output": 1}, "model": "test-model"}

    monkeypatch.setattr(main_module.chat_handler, "process_chat", slow_process_chat)

    first_task = asyncio.create_task(
        main_module.chat(_make_chat_request(), x_correlation_id="first")
    )

    # Wait (off the event loop) until the first request has genuinely
    # started running inside the worker thread before firing the second.
    loop = asyncio.get_event_loop()
    await loop.run_in_executor(None, started.wait, 2)

    second_response = await main_module.chat(_make_chat_request(), x_correlation_id="second")

    release.set()
    first_response = await first_task

    return first_response, second_response


def test_second_request_fails_fast_while_worker_saturated(monkeypatch):
    """
    FAILS without the fix: there is no admission control around the shared
    executor, so the second request either hangs behind the stuck worker
    or (in this test, since the first eventually completes) is delayed
    rather than immediately rejected - and `main_module._chat_worker_slots`
    does not even exist yet, so the monkeypatch target errors out.

    PASSES with the fix: the second request observes the pool saturated
    and returns HTTP 503 immediately, without waiting on the first
    request's completion.
    """
    first_response, second_response = asyncio.run(_run_concurrent_requests(monkeypatch))

    assert isinstance(second_response, main_module.JSONResponse)
    assert second_response.status_code == 503

    # The first request should have proceeded normally once it had the
    # worker slot to itself.
    assert isinstance(first_response, main_module.ChatResponse)
    assert first_response.reply == "ok"


def test_worker_slot_released_after_completion_allows_next_request(monkeypatch):
    """After the busy worker finishes, the freed slot must serve the next request."""
    monkeypatch.setattr(main_module, "_chat_worker_slots", threading.Semaphore(1))
    monkeypatch.setattr(main_module.model_loader, "is_loaded", lambda: True)
    monkeypatch.setattr(main_module.config, "MAX_GENERATION_SECONDS", 5)
    monkeypatch.setattr(
        main_module.chat_handler,
        "process_chat",
        lambda messages, max_tokens, temperature: {
            "reply": "ok",
            "tokens": {"input": 1, "output": 1},
            "model": "test-model",
        },
    )

    async def run():
        first = await main_module.chat(_make_chat_request(), x_correlation_id="first")
        second = await main_module.chat(_make_chat_request(), x_correlation_id="second")
        return first, second

    first_response, second_response = asyncio.run(run())

    assert isinstance(first_response, main_module.ChatResponse)
    assert isinstance(second_response, main_module.ChatResponse)
