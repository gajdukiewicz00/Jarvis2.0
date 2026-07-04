# Gradle wrapper bootstrap

This directory carries `gradle-wrapper.properties` (text) but **not**
`gradle-wrapper.jar` — the jar is a small binary (~60 KB) that the
upstream Gradle CLI generates idempotently from the property file.

## One-time bootstrap (operator step)

```bash
# Requires `gradle` on PATH (any Gradle 7.6+ works — the wrapper will
# pin to the version in gradle-wrapper.properties on first invocation).
cd apps/android-app
gradle wrapper --gradle-version 8.7
```

After that, `./gradlew assembleDebug` builds the Android module.

If you have Android Studio installed, opening `apps/android-app/` as a
project will trigger the same bootstrap automatically.

## Why the jar isn't checked in here

This module is a Phase 12 Pass 1 scaffold. The Maven reactor at the
repo root deliberately excludes it because Android isn't Maven. We
intentionally avoid committing a binary blob that the next person
on the project would have to trust without provenance — running
`gradle wrapper` produces the same jar from a known Gradle release.
