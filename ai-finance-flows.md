# AI Finance Flows

## 1) “Куда уходят деньги?”
1. `analyze_spending` (groupBy=CATEGORY).
2. AI summarizes top categories and trends.

## 2) “Где перерасход?”
1. `budget_status` for текущий месяц.
2. AI highlights categories with status=OVER.

## 3) “Сделай план на месяц”
1. `summarize_month` for предыдущий период.
2. AI предлагает цели и лимиты (без изменений в данных).

## 4) “Покажи транзакции”
1. `list_transactions` with date range.
2. AI формирует обзор и вопросы.
