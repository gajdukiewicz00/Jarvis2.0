# AI Calendar Flows

## 1) “Запланируй встречу”
1. `find_free_slot` to suggest options.
2. AI asks for confirmation.
3. On approval → `create_event` with `requires_confirmation=true`.

## 2) “Перенеси событие”
1. `list_events` to identify event.
2. `move_event` with new time.
3. If conflict → show options and ask for choice.

## 3) “Есть ли конфликты?”
1. `list_events` in time range.
2. AI explains detected overlaps (from tool data only).

## 4) “Найди слот на завтра”
1. `find_free_slot` with work hours.
2. AI proposes 2-3 options, waits for confirmation.
