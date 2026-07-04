# Hook Examples

These scripts are safe examples for native Codex hooks.

## What Codex supports natively

Official Codex docs support hooks through:

- `.codex/hooks.json`
- inline `[hooks]` tables in `.codex/config.toml`

Codex does not enable these scripts automatically just because they exist in `.codex/hooks/`.

## What these scripts do

### `pre-command.sh`

- validates that the current working directory is inside the Jarvis git repo
- refuses to do anything destructive
- makes no network calls
- exits successfully when checks are inconclusive

### `post-command.sh`

- prints a short git-status summary to stderr
- makes no mutations
- makes no network calls

## Safe enablement guidance

1. Start from `.codex/config.toml.example`.
2. Enable hooks only in a trusted repo.
3. Keep timeouts short.
4. Review the scripts before wiring them to `PreToolUse` or `PostToolUse`.
5. Do not treat these examples as a security boundary.

## Native inline example

```toml
[features]
codex_hooks = true

[[hooks.PreToolUse]]
matcher = "^Bash$"
[[hooks.PreToolUse.hooks]]
type = "command"
command = 'bash "$(git rev-parse --show-toplevel)/.codex/hooks/pre-command.sh"'
timeout = 10
statusMessage = "Running Jarvis pre-command check"

[[hooks.PostToolUse]]
matcher = "^Bash$"
[[hooks.PostToolUse.hooks]]
type = "command"
command = 'bash "$(git rev-parse --show-toplevel)/.codex/hooks/post-command.sh"'
timeout = 10
statusMessage = "Running Jarvis post-command summary"
```

## Notes

- `PreToolUse` and `PostToolUse` are native Codex events.
- These example scripts do not block commands or modify command input.
- If you later need stronger policy behavior, implement it deliberately and document the risk tradeoffs.
