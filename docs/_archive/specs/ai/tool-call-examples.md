# Tool Call Examples

Tool payloads never include `userId`. The caller must set `X-User-Id` header.

## Create Todo
```json
{
  "tool_calls": [
    {
      "name": "create_todo",
      "arguments": {
        "title": "Подготовить отчёт",
        "description": "Короткий статус по проекту",
        "dueDate": "2025-02-18T19:00:00",
        "priority": "HIGH",
        "tags": ["work", "report"]
      },
      "requires_confirmation": false
    }
  ],
  "explanation": "Пользователь попросил создать задачу; нужны параметры задачи."
}
```

## Today Focus
```json
{
  "tool_calls": [
    {
      "name": "list_todos",
      "arguments": {
        "status": "TODO",
        "from": "2025-02-18T00:00:00",
        "to": "2025-02-18T23:59:59"
      },
      "requires_confirmation": false
    }
  ],
  "explanation": "Сначала получаем активные задачи на сегодня."
}
```

## Calendar Suggestion (Needs Confirmation)
```json
{
  "tool_calls": [
    {
      "name": "create_event",
      "arguments": {
        "title": "Созвон с командой",
        "startTime": "2025-02-19T10:00:00",
        "endTime": "2025-02-19T10:30:00",
        "timezone": "Europe/Warsaw"
      },
      "requires_confirmation": true
    }
  ],
  "explanation": "Нужна встреча, но решение должно подтвердить человек."
}
```

## Finance Summary (Read-Only)
```json
{
  "tool_calls": [
    {
      "name": "summarize_month",
      "arguments": {
        "month": "2025-02"
      },
      "requires_confirmation": false
    }
  ],
  "explanation": "Сначала получаем агрегированную сводку расходов за месяц."
}
```
