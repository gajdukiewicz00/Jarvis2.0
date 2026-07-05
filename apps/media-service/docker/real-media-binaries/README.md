# Real media binaries for the `real-media-image` Jib profile

This directory is empty (except `.gitkeep` markers) by default. The standard build

```bash
mvn -pl apps/media-service -am -DskipTests jib:build
```

ships the normal mock-only media-service image: no ffmpeg, no ffprobe, no
whisper.cpp, no Piper. That is intentional — see the module README's "Provider
modes" section.

To build an image that can actually run `media.ffprobe.mode=real`,
`media.ffmpeg.mode=real`, `media.asr.mode=whisper`, and `media.tts.mode=real`,
drop statically-linked binaries, a whisper.cpp model, and a Piper voice model
here, then build with the `real-media-image` Maven profile:

```
docker/real-media-binaries/
├── bin/
│   ├── ffmpeg        # statically-linked, e.g. https://johnvansickle.com/ffmpeg/
│   ├── ffprobe        # ships in the same johnvansickle.com release tarball
│   ├── whisper-cli    # whisper.cpp CLI build, https://github.com/ggerganov/whisper.cpp
│   └── piper          # Piper TTS CLI build, https://github.com/rhasspy/piper
├── models/
│   └── ggml-base.bin  # a whisper.cpp GGML model (base/small/medium — pick per node RAM)
└── voices/
    ├── ru-neutral.onnx       # a Piper Russian voice model
    └── ru-neutral.onnx.json  # its matching Piper config (required alongside the .onnx)
```

No Dockerfile is needed: these are static, self-contained executables with no
shared-library dependencies beyond glibc, so they run unmodified on the
`eclipse-temurin:21-jre-jammy` base image this repo already uses for every
service (see the root `pom.xml`'s `jib.from.image` property). Jib copies this
directory tree straight into the image at build time via `extraDirectories`
(no separate container build step, no registry pull of an ffmpeg-flavored
base image) and marks everything under `bin/` executable.

## Build

```bash
mvn -pl apps/media-service -am -DskipTests clean install \
  -Preal-media-image jib:build -Djib.image.tag=<tag>
```

## Deploy

Set on the container (see `apps/media-service/README.md` for the full property
table):

```
MEDIA_FFPROBE_MODE=real
MEDIA_FFMPEG_MODE=real
MEDIA_ASR_MODE=whisper
MEDIA_ASR_BINARY=/usr/local/bin/whisper-cli
MEDIA_ASR_MODEL_PATH=/opt/whisper/models/ggml-base.bin
MEDIA_TTS_MODE=real
MEDIA_TTS_BINARY=/usr/local/bin/piper
MEDIA_TTS_VOICE_MODEL_PATH=/opt/piper/voices/ru-neutral.onnx
```

`media.translation.mode=llm` is independent of this image variant — it only
needs a NetworkPolicy allowlist entry to `llm-service`, no binaries — see the
"LLM-backed translation" section of the module README.

## Why this lives outside the default build

- The binaries are large (tens to hundreds of MB) and are not source code;
  committing real copies here would bloat the repo and this module's git
  history for every clone, whether or not anyone ever runs the real pipeline.
- Every other Jarvis service builds through Jib with zero Dockerfiles; this
  keeps media-service consistent with that convention instead of introducing
  a one-off Dockerfile-based build path.
- Keeping it profile-gated means the default `mvn ... jib:build` and the
  default `mvn test` never need these binaries to exist, so CI and this
  module's normal contributors are unaffected.
