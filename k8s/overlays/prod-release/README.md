# Prod Release Overlay

`scripts/product/jarvis-promote-images.sh` generates `kustomization.yaml` in this directory.

That generated file is the digest-pinned deployment artifact for real cluster rollouts. It references `../prod` and replaces mutable backend image tags with immutable `image@sha256` refs.

Policy:

- `kustomization.yaml` is generated, environment-specific, and must not be committed.
- Git ignores the generated file in this directory on purpose.
- `README.md` and `.gitignore` stay committed so the release artifact contract is explicit.

Do not hand-edit the generated `kustomization.yaml`. Regenerate it from promoted image digests.
