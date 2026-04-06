# MVP Versus Vision: Jarvis

## Current Product Reality In One Sentence

The current repo already contains a real assistant backend MVP, but the broader "offline-first AI platform" story is still larger than the set of features that are default-on and fully mature.

Confidence: **high**

## What Counts As The Real MVP Today

There are two useful ways to define "MVP" here.

## 1. Repo-Supported Runtime MVP

This is the safest and most evidence-based definition, because it matches the actual default runtime scripts and CI expectations.

Required components:

- PostgreSQL
- `security-service`
- `user-profile`
- `nlp-service`
- `orchestrator`
- `voice-gateway`
- `pc-control`
- `smart-home-service`
- `life-tracker`
- `analytics-service`
- `api-gateway`
- `planner-service`

Why this is the real repo MVP:

- these are the services started by the default local runtime
- these are the services assumed by the core smoke path
- these are the services that make the assistant feel like a system rather than a single command router

Evidence:

- `scripts/runtime-up.sh`
- `scripts/runtime/common.sh`
- `.github/workflows/backend-readiness.yml`

Confidence: **high**

## 2. Strict Functional Assistant Minimum

If the question is "what is the smallest set that still feels like Jarvis at all?", then the practical minimum is smaller:

- PostgreSQL
- `security-service`
- `api-gateway`
- `nlp-service`
- `orchestrator`
- at least one executor: `pc-control` or `smart-home-service`

This would still give you:

- auth
- a public API
- command interpretation
- deterministic action routing
- actual execution

But it would be a narrower assistant shell, not the broader Jarvis product described across the repo.

Confidence: **medium**

Why confidence is only medium:

- this is a reasoned reduction of the current design, not the exact default runtime contract

## What Can Be Disabled Without Losing The Identity Of The Project

| Component | Can Be Disabled? | Does Jarvis Still Remain Jarvis? | Why | Confidence |
| --- | --- | --- | --- | --- |
| `llm-service` | yes | yes | orchestrator and domain services still function without it | high |
| `llm-server` | yes | yes | only needed for local LLM path | high |
| `memory-service` | yes | yes | default runtime does not require long-term memory | high |
| `embedding-service` | yes | yes | only needed for memory stack | high |
| Android client | yes | yes | mobile is not core today | high |
| computer-vision monitoring | yes | yes | optional subfeature inside `pc-control` | high |
| real MQTT device transport | yes, if mock mode is used | yes | smart-home defaults can run in mock mode | high |
| `voice-gateway` | maybe | mostly yes for a text-first deployment, but you lose a major product surface | repo runtime treats it as core, but the concept of Jarvis can still survive as text + desktop control | medium |
| `smart-home-service` | yes | yes | Jarvis still works as planning + desktop assistant | medium-high |

## What Already Belongs To The MVP

| Area | Current State | Evidence | Confidence |
| --- | --- | --- | --- |
| auth and identity | real | `security-service`, gateway JWT validation | high |
| HTTP API gateway | real | `api-gateway` controllers and clients | high |
| rule-based command understanding | real | `nlp-service` | high |
| command routing | real | `orchestrator` | high |
| desktop action execution | real | `pc-control` | high |
| voice ingress and notifications | real | `voice-gateway` | high |
| reminders/tasks | real core, imperfect intelligence layer | `planner-service` | high |
| finance/calendar/time data backend | real | `life-tracker` | high |
| analytics summaries | real | `analytics-service` | high |
| desktop UI / launcher | real | JavaFX apps | high |

## Real But Optional Add-Ons

| Area | Current State | Why It Is Optional | Evidence | Confidence |
| --- | --- | --- | --- | --- |
| local LLM path | real, optional | disabled by default in runtime and prod overlay | runtime flags + k8s replicas `0` | high |
| long-term memory | real, optional | disabled by default in runtime and prod overlay | memory stack wiring + smoke path | high |
| real smart-home MQTT transport | real, optional | local runtime defaults to mock provider | `scripts/runtime/common.sh`, smart-home config | high |
| computer-vision workstation monitoring | real MVP feature, optional | not part of normal runtime identity | `pc-control` securitymonitoring, dedicated doc | medium-high |
| wake-word desktop path | optional | not part of CI/runtime core | desktop POM and client code | medium |

## Vision Items That Sound Bigger Than Current Reality

| Vision Item | Current State | What Already Exists | What Is Missing Before It Is Truly Real | Confidence |
| --- | --- | --- | --- | --- |
| Local LLM assistant as a default experience | partial | real `llm-service`, real `llm-server`, optional runtime path | always-on deployment, proven model UX, stronger product integration, broader smoke proof | high |
| Long-term memory as a default assistant feature | partial | real memory ingestion, vector storage, embedding service, search endpoints | default-on deployment, better ranking quality, stronger end-user workflows | high |
| Smart home as a real hardware platform | partial | device registry, action model, MQTT transport, mock mode | real device fleet contracts, richer state sync, proof beyond mock/default transport | high |
| Real-time voice assistant | partial but meaningful | voice websocket, STT/TTS, orchestration, notifications | broader robustness, full end-to-end product hardening, better model/provider handling | medium-high |
| Android client | partial | Android module, main activity, streaming direction | auth, navigation, settings wiring, real user flows, parity with desktop/backend features | high |
| Computer vision security monitoring | partial | OpenCV webcam capture, Haar cascade detection, baseline verifier, evidence, email alerts | stronger verification model, enrollment, persistence, better UX, production-grade reliability | high |
| Advanced planner intelligence | partial | daily plan generator, recommendation engine, LLM hooks | replace placeholders with real contracts and real model-driven logic | high |
| Kafka / RabbitMQ event backbone | mostly aspiration | deps/config/hooks only | real producers, listeners, runtime flows, CI/smoke proof, architecture ownership | high |
| Bank sync | not found in current repo reality | finance tracking exists in `life-tracker` | any real bank integration code, contract, or docs-based runtime wiring | high |
| Smartwatch sync | not found in current repo reality | none found | any real client/integration implementation | high |
| Weather station integration | not found in current repo reality | none found | any real hardware/integration implementation | high |

## The Honest Product Classification

If forced to pick one label, the most honest one is:

**a mix of a working assistant/service core and a large set of architectural ambitions**

Why this beats the other candidate labels:

- more than a thesis or concept, because the backend runtime and CI paths are real
- less than a finished production AI platform, because several "smart" layers are optional or placeholder-heavy
- more accurate than "AI platform" alone, because the durable heart of the repo is still the Java service core

Confidence: **high**

## The Most Honest Short Answer

Jarvis already has a real MVP.

That MVP is **backend-first, service-heavy, and optionally AI-enhanced**, not "AI-first with some services around it."

Confidence: **high**
