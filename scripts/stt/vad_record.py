#!/usr/bin/env python3
"""Local VAD (voice-activity) recorder — 100% offline, energy-threshold based.

Records 16 kHz mono s16le from the mic (via `arecord`) and stops after a period
of silence, instead of a fixed duration. Calibrates ambient noise briefly, then:
  wait for speech (RMS > threshold) up to --start-timeout, then record until
  --silence-ms of continuous silence OR --max-seconds, whichever comes first.

Deterministic test mode: --in-wav FILE trims leading/trailing silence from a
file using the same energy gate (no mic needed).

Uses only Python stdlib (audioop, wave) + arecord. No cloud.

Exit: 0 wrote audio · 6 mic error · 7 no speech detected (start-timeout)
"""
import argparse
import shutil
import subprocess
import sys
import time
import warnings
import wave

RATE = 16000
CHUNK = 480  # 30 ms @ 16 kHz
CHUNK_BYTES = CHUNK * 2

# audioop is fast but removed in Python 3.13 — fall back to a pure-stdlib RMS.
warnings.filterwarnings("ignore", category=DeprecationWarning)
try:
    import audioop

    def _rms(buf: bytes) -> int:
        return audioop.rms(buf, 2)
except Exception:  # pragma: no cover - py3.13+
    import array
    import math

    def _rms(buf: bytes) -> int:
        a = array.array("h")
        a.frombytes(buf)
        if not a:
            return 0
        return int(math.sqrt(sum(s * s for s in a) / len(a)))


def write_wav(path, frames: bytes):
    wf = wave.open(path, "wb")
    wf.setnchannels(1)
    wf.setsampwidth(2)
    wf.setframerate(RATE)
    wf.writeframes(frames)
    wf.close()


def trim_file(in_wav, out, silence_ms, threshold):
    wf = wave.open(in_wav, "rb")
    if wf.getnchannels() != 1 or wf.getsampwidth() != 2 or wf.getframerate() != RATE:
        sys.stderr.write("WAVE_FORMAT: need 16kHz mono s16le\n")
        return 6
    pcm = wf.readframes(wf.getnframes())
    voiced = bytearray()
    started = False
    trailing_sil = 0
    sil_chunks = max(1, silence_ms // 30)
    for i in range(0, len(pcm) - CHUNK_BYTES, CHUNK_BYTES):
        ch = pcm[i:i + CHUNK_BYTES]
        rms = _rms(ch)
        if rms >= threshold:
            started = True
            trailing_sil = 0
            voiced += ch
        elif started:
            trailing_sil += 1
            voiced += ch
            if trailing_sil >= sil_chunks:
                break
    if not started:
        sys.stderr.write("NO_SPEECH\n")
        return 7
    write_wav(out, bytes(voiced))
    sys.stdout.write(f"VAD_OK frames={len(voiced)//2} threshold={threshold}\n")
    return 0


def record_mic(out, device, silence_ms, max_seconds, start_timeout, threshold,
               threshold_multiplier=3.0, pre_roll_ms=300):
    if not shutil.which("arecord"):
        sys.stderr.write("MIC_ERROR: arecord not found\n")
        return 6
    cmd = ["arecord", "-q", "-f", "S16_LE", "-r", str(RATE), "-c", "1", "-t", "raw", "-"]
    if device:
        cmd[1:1] = ["-D", device]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE)
    try:
        import collections
        # calibrate ambient for ~300 ms
        amb = []
        for _ in range(10):
            b = proc.stdout.read(CHUNK_BYTES)
            if len(b) == CHUNK_BYTES:
                amb.append(_rms(b))
        ambient = (sum(amb) // len(amb)) if amb else 100
        thr = threshold if threshold > 0 else max(int(ambient * threshold_multiplier), 350)
        sys.stdout.write(f"LISTENING ambient={ambient} threshold={thr} mult={threshold_multiplier}\n")
        sys.stdout.flush()

        sil_chunks = max(1, silence_ms // 30)
        # pre-roll: keep the last N ms of audio so we don't clip the word onset
        pre_roll = collections.deque(maxlen=max(0, pre_roll_ms // 30))
        voiced = bytearray()
        started = False
        trailing = 0
        t0 = time.time()
        while True:
            b = proc.stdout.read(CHUNK_BYTES)
            if len(b) < CHUNK_BYTES:
                continue
            rms = _rms(b)
            now = time.time()
            if not started:
                pre_roll.append(b)
                if rms >= thr:
                    started = True
                    for pr in pre_roll:
                        voiced += pr
                elif now - t0 > start_timeout:
                    sys.stderr.write("NO_SPEECH\n")
                    return 7
            else:
                voiced += b
                trailing = trailing + 1 if rms < thr else 0
                if trailing >= sil_chunks:
                    break
                if now - t0 > max_seconds:
                    break
        write_wav(out, bytes(voiced))
        sys.stdout.write(f"VAD_OK frames={len(voiced)//2} threshold={thr}\n")
        return 0
    finally:
        proc.terminate()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--device", default=None)
    ap.add_argument("--silence-ms", type=int, default=800)
    ap.add_argument("--max-seconds", type=float, default=10.0)
    ap.add_argument("--start-timeout", type=float, default=5.0)
    ap.add_argument("--threshold", type=int, default=0)  # 0 = auto-calibrate
    ap.add_argument("--threshold-multiplier", type=float, default=3.0)
    ap.add_argument("--pre-roll-ms", type=int, default=300)
    ap.add_argument("--in-wav", default=None)
    args = ap.parse_args()
    if args.in_wav:
        return trim_file(args.in_wav, args.out, args.silence_ms,
                         args.threshold or 350)
    return record_mic(args.out, args.device, args.silence_ms, args.max_seconds,
                      args.start_timeout, args.threshold,
                      args.threshold_multiplier, args.pre_roll_ms)


if __name__ == "__main__":
    sys.exit(main())
