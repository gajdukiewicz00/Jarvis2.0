# Codex Configuration Map

This reference documents the Codex configuration structure that is supported by the current OpenAI Codex docs and distinguishes that from what currently exists in this repository.

As of 2026-04-28, this repo contains:

- `AGENTS.md`
- `docs/`

It does not currently contain:

- `AGENTS.override.md`
- `.codex/`
- `.agents/skills/`
- `hooks.json`

That means the tree below is a supported Codex map, not a claim that every path already exists in this repo.

## Important corrections from the requested sketch

- Official Codex docs place repo skills under `.agents/skills/`, not a top-level `skills/` folder.
- Official Codex docs discover hooks from `.codex/hooks.json` or inline `[hooks]` tables in `.codex/config.toml`, not from a top-level `hooks/` folder by folder name alone.
- The requested `~/.codex/prompts/` path is not documented in the current official Codex pages used for this map. It is listed below only as an unverified legacy item.

## Verified tree

```text
global/
~/.codex/
├── AGENTS.md
├── AGENTS.override.md
├── config.toml
├── agents/
│   └── <agent-name>.toml
└── log/
    ├── codex-tui.log
    └── session-*.jsonl        # only if session logging is enabled

$HOME/.agents/
└── skills/
    └── <skill-name>/
        ├── SKILL.md
        ├── scripts/
        ├── references/
        ├── assets/
        └── agents/
            └── openai.yaml

project/
├── AGENTS.md
├── <subdir>/
│   └── AGENTS.override.md
├── .codex/
│   ├── config.toml
│   ├── hooks.json            # optional; or use inline [hooks] in config.toml
│   ├── hooks/                # optional script location by convention
│   └── agents/
│       └── <agent-name>.toml
├── .agents/
│   └── skills/
│       └── <skill-name>/
│           ├── SKILL.md
│           ├── scripts/
│           ├── references/
│           ├── assets/
│           └── agents/
│               └── openai.yaml
└── docs/

admin/
/etc/codex/
├── config.toml
├── requirements.toml
└── managed_config.toml

unverified/
~/.codex/prompts/             # verify in official docs before treating as supported
```

## Explanation Boxes

### Global

> `~/.codex/AGENTS.md`  
> Global persistent guidance. Codex reads this before project instructions unless `~/.codex/AGENTS.override.md` is present and non-empty.

> `~/.codex/AGENTS.override.md`  
> Temporary global override. At the global layer, Codex reads `AGENTS.override.md` first; otherwise it falls back to `AGENTS.md`.

> `~/.codex/config.toml`  
> User-level Codex configuration shared by the CLI and IDE extension. Typical concerns include model/provider selection, approval policy, sandbox mode, MCP servers, hooks, log location, and feature flags.

> `~/.codex/agents/<agent-name>.toml`  
> Optional custom agent definitions for spawned workflows. These are project- or user-scoped configuration layers for specialized agents.

> `~/.codex/log/`  
> Verified default log directory via `log_dir = $CODEX_HOME/log`. Official docs mention `codex-tui.log` and also mention `session-*.jsonl` when session logging is enabled.

> `$HOME/.agents/skills/<skill-name>/SKILL.md`  
> Official user-scope skill location. This is separate from `~/.codex/`. `SKILL.md` is the required entrypoint for a skill.

> `$HOME/.agents/skills/<skill-name>/scripts/`  
> Optional executable helpers used by the skill workflow.

> `$HOME/.agents/skills/<skill-name>/references/`  
> Optional documentation loaded only when the skill needs it.

> `$HOME/.agents/skills/<skill-name>/assets/`  
> Optional templates and resources used by the skill.

> `$HOME/.agents/skills/<skill-name>/agents/openai.yaml`  
> Optional skill metadata for the Codex app, including appearance, invocation policy, and tool dependency declarations.

### Project

> `AGENTS.md`  
> Repo-level persistent guidance. Good place for setup commands, build/test/lint expectations, review rules, repo conventions, and "do not" rules.

> `<subdir>/AGENTS.override.md`  
> Directory-local override. In each directory Codex checks `AGENTS.override.md` before `AGENTS.md`, and only includes at most one instruction file per directory.

> `.codex/config.toml`  
> Project-scoped Codex config. Loaded only for trusted projects. Multiple project config layers can stack from repo root down to the current working directory.

> `.codex/hooks.json` or inline `[hooks]`  
> Verified hook configuration forms. Hooks are discovered next to active config layers, not by a standalone folder convention alone.

> `.codex/hooks/`  
> Optional script storage convention shown in official examples. This path is not auto-discovered just because the folder exists; the script still has to be referenced from `hooks.json` or `[hooks]`.

> `.codex/agents/<agent-name>.toml`  
> Optional project-scoped custom agents for parallel or specialized workflows.

> `.agents/skills/<skill-name>/SKILL.md`  
> Official repo-scope skill entrypoint. Codex scans `.agents/skills` directories from the current working directory upward to the repo root.

> `.agents/skills/<skill-name>/scripts/`  
> Optional skill-local executables.

> `.agents/skills/<skill-name>/references/`  
> Optional supporting docs.

> `.agents/skills/<skill-name>/assets/`  
> Optional reusable resources.

> `.agents/skills/<skill-name>/agents/openai.yaml`  
> Optional UI metadata, invocation policy, and tool dependency declarations for the skill.

> `docs/`  
> Regular project documentation. This is not a special Codex discovery directory, but it is a sensible place for Codex-maintained onboarding docs like this map.

### Config Behavior

Configuration precedence is verified from the official Config Basics page:

1. CLI flags and `--config` overrides
2. Profile values from `--profile <name>`
3. Project `.codex/config.toml` files from project root down to the current directory
4. User `~/.codex/config.toml`
5. System `/etc/codex/config.toml` if present
6. Built-in defaults

Important trust rule:

- If the project is untrusted, Codex skips project-scoped `.codex/` layers, including project config, hooks, and rules.
- User and system config still load in untrusted projects.

### AGENTS Loading Order

Instruction loading is separate from config precedence.

1. Global scope: Codex reads `~/.codex/AGENTS.override.md` if present and non-empty; otherwise `~/.codex/AGENTS.md`.
2. Project scope: Codex walks from project root to the current directory. In each directory it checks `AGENTS.override.md`, then `AGENTS.md`, then configured fallback filenames.
3. Merge order: files are concatenated from root to current directory, so closer files win because they appear later.

Important implication:

- A nested `AGENTS.override.md` can suppress a same-directory `AGENTS.md`.
- A deeper `AGENTS.md` can refine a broader repo-level `AGENTS.md`.

### MCP

MCP is configured through `config.toml`, not through Claude-style `.mcp.json`.

Verified MCP server fields from official docs include:

- Server id via `[mcp_servers.<server-name>]`
- `command` or `url`
- `args`
- `env`
- `env_vars`
- `cwd`
- `bearer_token_env_var`
- `http_headers`
- `env_http_headers`
- `startup_timeout_sec`
- `tool_timeout_sec`
- `enabled`
- `required`
- `enabled_tools`
- `disabled_tools`

Operational note:

- `/mcp` is the built-in session command for listing active MCP tools.

### Slash Commands

Built-in slash commands are session controls, not repo files.

Useful examples verified in the CLI slash-command docs:

- `/init` creates an `AGENTS.md` scaffold in the current directory
- `/model` switches the active model
- `/permissions` changes approval behavior mid-session
- `/mcp` lists configured MCP tools
- `/agent` switches active agent threads
- `/diff` shows the current Git diff
- `/plan` switches into plan mode
- `/compact` summarizes long conversations to free context

### Subagents

Subagents are explicit parallel or specialized workflows.

Verified behavior:

- Codex only spawns subagents when you explicitly ask it to.
- They are useful for parallel codebase exploration, multi-step feature work, audits, and reviews.
- They consume more tokens than comparable single-agent runs.
- Built-in agent roles include `default`, `worker`, and `explorer`.
- Custom agent files can live under `~/.codex/agents/` or `.codex/agents/`.

### Hooks

Hooks are deterministic lifecycle/event scripts configured through `.codex/config.toml` or `.codex/hooks.json`.

Verified examples and uses:

- Pre-tool validation
- Permission review
- Post-tool validation
- Prompt scanning
- Logging and analytics
- Policy enforcement

Verified hook events:

- `SessionStart`
- `PreToolUse`
- `PermissionRequest`
- `PostToolUse`
- `UserPromptSubmit`
- `Stop`

Feature-flag note:

- Current docs say hooks are behind `[features] codex_hooks = true`.
- Current docs also list `codex_hooks` as enabled by default in supported feature tables.
- In practice, keep the feature key explicit if your team depends on hooks.

### Enterprise / Admin Layer

> `/etc/codex/requirements.toml`  
> Admin-enforced constraints for security-sensitive settings. This is not a normal team repo config.

> `/etc/codex/managed_config.toml`  
> Managed defaults applied at startup. Users can still change settings during a session, but managed defaults reapply on next launch.

Verified admin controls include:

- Allowed approval policies
- Allowed sandbox modes
- Allowed approvals reviewers
- Web search mode restrictions
- Managed hooks
- Restrictive command rules
- Optional MCP allowlists
- Feature pins

Cloud note:

- ChatGPT Business and Enterprise can also apply cloud-managed `requirements.toml`-compatible rules.

## Unverified or Deliberately Omitted Items

- `~/.codex/prompts/` is not documented in the current official Codex pages used for this map. Treat it as "verify in official docs" before adopting it.
- A top-level project `hooks/` directory is not an official discovery path. Use `.codex/hooks.json` or inline `[hooks]`, and point those configs at scripts explicitly.
- A plain top-level project `skills/` directory is not the documented repo skill location. Use `.agents/skills/`.

## Official Sources

- Config Basics: <https://developers.openai.com/codex/config-basic>
- Configuration Reference: <https://developers.openai.com/codex/config-reference>
- Sample Config: <https://developers.openai.com/codex/config-sample>
- AGENTS.md: <https://developers.openai.com/codex/guides/agents-md>
- Hooks: <https://developers.openai.com/codex/hooks>
- MCP: <https://developers.openai.com/codex/mcp>
- Skills: <https://developers.openai.com/codex/skills>
- Customization: <https://developers.openai.com/codex/concepts/customization>
- Subagents: <https://developers.openai.com/codex/subagents>
- Slash commands: <https://developers.openai.com/codex/cli/slash-commands>
- Managed configuration: <https://developers.openai.com/codex/enterprise/managed-configuration>
