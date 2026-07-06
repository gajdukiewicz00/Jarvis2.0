"""
Regression test for Finding #55 (MEDIUM, logic error).

`ChatHandler.process_chat` trims conversation history with:

    other_msgs[-(config.MAX_HISTORY_LENGTH - len(system_msgs)):]

When `len(system_msgs) == config.MAX_HISTORY_LENGTH`, the computed slice
index is `-0`, and Python's `list[-0:]` is identical to `list[0:]` - i.e.
the *entire* list, not an empty one. So instead of trimming `other_msgs`
down to zero remaining slots, the trim silently does nothing at all.
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from app import chat_handler as chat_handler_module  # noqa: E402
from app.chat_handler import chat_handler  # noqa: E402
from app.config import config  # noqa: E402


def test_history_trim_when_system_count_equals_limit(monkeypatch):
    """
    FAILS without the fix: with MAX_HISTORY_LENGTH=1 and exactly one
    system message, `-(1 - 1) == -0 == 0`, so `other_msgs[-0:]` returns
    all 10 other messages unmodified (trimmed list has 11 entries).

    PASSES with the fix: `keep = 1 - 1 = 0`, so no room remains for any
    other message and only the system message survives (1 entry).
    """
    monkeypatch.setattr(config, "MAX_HISTORY_LENGTH", 1)

    captured = {}

    def fake_chat(messages, max_tokens, temperature, top_p):
        captured["messages"] = messages
        return "reply", 1, 1

    monkeypatch.setattr(chat_handler_module.model_loader, "supports_chat_messages", lambda: True)
    monkeypatch.setattr(chat_handler_module.model_loader, "chat", fake_chat)

    messages = [{"role": "system", "content": "sys"}] + [
        {"role": "user" if i % 2 == 0 else "assistant", "content": f"msg{i}"}
        for i in range(10)
    ]

    chat_handler.process_chat(messages=messages)

    trimmed = captured["messages"]
    system_count = sum(1 for m in trimmed if m["role"] == "system")

    assert system_count == 1
    assert len(trimmed) == 1, (
        f"expected history trimmed down to just the system message (1 "
        f"entry), got {len(trimmed)}: {trimmed}"
    )


def test_history_trim_still_works_below_limit(monkeypatch):
    """Sanity check: the fix must not disturb the normal trimming case."""
    monkeypatch.setattr(config, "MAX_HISTORY_LENGTH", 5)

    captured = {}

    def fake_chat(messages, max_tokens, temperature, top_p):
        captured["messages"] = messages
        return "reply", 1, 1

    monkeypatch.setattr(chat_handler_module.model_loader, "supports_chat_messages", lambda: True)
    monkeypatch.setattr(chat_handler_module.model_loader, "chat", fake_chat)

    messages = [{"role": "system", "content": "sys"}] + [
        {"role": "user" if i % 2 == 0 else "assistant", "content": f"msg{i}"}
        for i in range(10)
    ]

    chat_handler.process_chat(messages=messages)

    trimmed = captured["messages"]
    system_count = sum(1 for m in trimmed if m["role"] == "system")

    assert system_count == 1
    # 5 total slots - 1 system message = 4 other messages kept.
    assert len(trimmed) == 5
    assert trimmed[0]["role"] == "system"
    assert [m["content"] for m in trimmed[1:]] == ["msg6", "msg7", "msg8", "msg9"]
