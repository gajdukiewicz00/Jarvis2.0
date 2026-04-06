# AI Gaps

## Still Out Of Scope

- multiple interchangeable local model families
- external hosted LLM providers as part of the canonical local path
- Kubernetes as the primary local AI runtime path
- memory semantics beyond the current `memory-service` retrieval contract

## Risks To Watch

- `llama-cpp-python` wheel compatibility on new GPU / Python combinations
- GPU verification is now tied to `llama-cpp-python==0.3.19`; if the package spec or GGUF model changes, the old `ai-gpu-status.json` becomes stale and must be re-verified
- long first-run bootstrap time due model downloads
- orchestrator consumer path still uses its own rule-based fallback when AI is disabled or unavailable

## Non-Goals For This Iteration

- broad AI redesign
- adding every model format under the sun
- replacing the existing Java/Python split

## Honest Boundary

This layer is only “done” when:

- the canonical artifacts are actually downloaded
- the canonical runtime actually starts
- `ai-local-smoke.sh` actually passes locally
- `ai-gpu-smoke.sh` actually passes locally if GPU readiness is claimed
- the runtime truth endpoint says `fullLocalAiReadiness=true`

If any of those are false, the AI layer is still partial.
