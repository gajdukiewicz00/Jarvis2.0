# AI Todo Flows

## 1) “Создай задачу…”
1. LLM Orchestrator → `create_todo`.
2. Tool API validates + idempotency.
3. planner-service creates task with `source=AI`.

## 2) “Что мне делать сегодня?”
1. LLM Orchestrator → `list_todos` with today range.
2. Tool executor returns list.
3. AI builds ranked summary (no direct edits).

## 3) “Разбей задачу на подзадачи”
1. LLM Orchestrator proposes multiple `create_todo` tool calls.
2. Executor runs each call with unique idempotency key.

## 4) “Я забыл что-то важное?”
1. LLM Orchestrator → `list_todos` for overdue + near dueDate.
2. AI summarizes only from tool results (no hallucination).
