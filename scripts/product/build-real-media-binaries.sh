#!/usr/bin/env bash
# Fetch/build the real media binaries + models for the media-service
# `real-media-image` Jib profile. These are THIRD-PARTY, network-downloaded,
# executable artifacts — run this yourself (reviewed), then let the agent do the
# local Jib build + deploy. Sources:
#   - ffmpeg/ffprobe: https://johnvansickle.com/ffmpeg/ (static)
#   - whisper.cpp:    https://github.com/ggerganov/whisper.cpp (built here)
#   - whisper model:  https://huggingface.co/ggerganov/whisper.cpp (ggml-base.bin)
#   - piper voice:    https://huggingface.co/rhasspy/piper-voices (ru_RU)
#   - piper CLI:      https://github.com/rhasspy/piper/releases
# Requires: cmake make g++ git curl tar xz.
set -uo pipefail
R="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RM="$R/apps/media-service/docker/real-media-binaries"
W="$(mktemp -d)"
mkdir -p "$RM/bin" "$RM/models" "$RM/voices"; cd "$W"

echo "== 1/5 static ffmpeg + ffprobe =="
if [ ! -x "$RM/bin/ffmpeg" ]; then
  curl -fSL --retry 3 -o ffmpeg.tar.xz https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz
  tar xf ffmpeg.tar.xz
  d=$(find . -maxdepth 1 -type d -name 'ffmpeg-*-amd64-static' | head -1)
  cp "$d/ffmpeg" "$RM/bin/ffmpeg"; cp "$d/ffprobe" "$RM/bin/ffprobe"
fi
"$RM/bin/ffmpeg" -version | head -1

echo "== 2/5 build whisper.cpp -> whisper-cli =="
if [ ! -x "$RM/bin/whisper-cli" ]; then
  git clone --depth 1 https://github.com/ggerganov/whisper.cpp.git
  cd whisper.cpp
  cmake -B build -DCMAKE_BUILD_TYPE=Release -DWHISPER_BUILD_TESTS=OFF
  cmake --build build -j"$(nproc)" --config Release
  bin=$(find build -type f \( -name 'whisper-cli' -o -name 'main' \) -perm -u+x | head -1)
  cp "$bin" "$RM/bin/whisper-cli"; cd "$W"
fi

echo "== 3/5 whisper GGML model (base) =="
[ -f "$RM/models/ggml-base.bin" ] || \
  curl -fSL --retry 3 -o "$RM/models/ggml-base.bin" https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin

echo "== 4/5 Piper RU voice (best-effort) =="
V="$RM/voices/ru-neutral"
if [ ! -f "$V.onnx" ]; then
  b=https://huggingface.co/rhasspy/piper-voices/resolve/main/ru/ru_RU/dmitri/medium/ru_RU-dmitri-medium
  curl -fSL --retry 3 -o "$V.onnx" "$b.onnx" && curl -fSL -o "$V.onnx.json" "$b.onnx.json" || echo "  (piper voice failed; TTS can stay mock)"
fi

echo "== 5/5 piper CLI (best-effort) =="
if [ ! -x "$RM/bin/piper" ]; then
  curl -fSL -o piper.tgz https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_linux_x86_64.tar.gz \
    && tar xf piper.tgz && cp piper/piper "$RM/bin/piper" && cp -r piper/*.so* piper/espeak-ng-data "$RM/bin/" 2>/dev/null \
    || echo "  (piper CLI failed; TTS can stay mock)"
fi

chmod +x "$RM/bin/"* 2>/dev/null || true
echo "== inventory =="; ls -la "$RM/bin" "$RM/models" "$RM/voices"
echo "DONE — now tell the agent to build+deploy the real-media image."
