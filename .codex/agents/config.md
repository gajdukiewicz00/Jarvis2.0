# Agent Role Configuration

These files are Jarvis project conventions.

## What They Are

- markdown role briefs for Codex sessions and human operators
- reusable specialist lenses for review, audit, debugging, and documentation work

## What They Are Not

- they are not native custom agent definitions by themselves
- Codex-native custom agents use `.toml` files under `.codex/agents/`

## Native Custom Agent Note

If the team later wants true native custom agents, add `.toml` files beside these briefs, for example:

```toml
name = "jarvis-reviewer"
description = "Review Jarvis changes for correctness and runtime risk."
developer_instructions = """
Read AGENTS.md, README.md, and the matching role brief in .codex/agents/code-reviewer.md.
"""
```

## Current Recommendation

- use the markdown role briefs as stable role definitions
- use `.agents/skills/` for native Codex discovery
- keep role docs short, opinionated, and evidence-focused
