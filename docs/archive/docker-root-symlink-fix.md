# Archived Docker Root Symlink Fix

This archived note preserves the removed `scripts/product/jarvis-fix-docker-root.sh`
helper for migration history only. Jarvis no longer ships an active helper for
repairing Docker's root directory because the supported runtime path is
native host plus MicroK8s with daemonless image tooling.

The removed helper used to:

```bash
systemctl stop docker
rm /var/lib/docker
mkdir -p /var/lib/docker
chmod 711 /var/lib/docker
systemctl start docker
```

Keep Docker-specific migration notes under `docs/archive/` only.
