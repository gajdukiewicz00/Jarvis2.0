---
name: "jarvis-review"
description: "Review Jarvis changes for correctness, security, architecture boundaries, tests, docs drift, and runtime risk."
---

# Jarvis Review

1. Read `AGENTS.md`, `README.md`, and `.codex/README.md`.
2. Read `.codex/prompts/review.md`.
3. If the area touches runtime or security behavior, also read:
   - `.codex/workflows/security-review.md`
   - `.codex/workflows/smoke-test.md`
4. Default to findings-first review output unless the user explicitly asks for fixes.
