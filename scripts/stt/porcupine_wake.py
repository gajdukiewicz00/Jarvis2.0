#!/usr/bin/env python3
"""Local Porcupine wake-word listener (offline inference).

Two modes:
  --probe                 : init Porcupine with the key+keyword and report
                            OK / FAILED (used to decide engine vs. fallback).
  (listen, default)       : stream mic audio (via `arecord` raw s16le) through
                            Porcupine; on the wake word, print "WAKE" and exit 0.

Audio never leaves the machine. NOTE: Picovoice validates the AccessKey, which
may contact Picovoice's licensing endpoint (license check only — never audio).

Args: --access-key KEY --keyword PPN [--sensitivity 0.5] [--device ALSA]
      [--timeout SECONDS (0=forever)] [--probe]
Exit: 0 wake/probe-ok · 6 mic error · 7 timeout · 8 porcupine unavailable/bad-key
"""
import argparse
import os
import shutil
import subprocess
import sys
import time


def make_porcupine(args):
    import pvporcupine
    # Prefer the env var so the key never appears in argv / `ps` output.
    key = os.environ.get("PORCUPINE_ACCESS_KEY") or args.access_key
    return pvporcupine.create(
        access_key=key,
        keyword_paths=[args.keyword],
        sensitivities=[args.sensitivity],
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--access-key", default="", help="fallback if PORCUPINE_ACCESS_KEY env unset")
    ap.add_argument("--keyword", required=True)
    ap.add_argument("--sensitivity", type=float, default=0.5)
    ap.add_argument("--device", default=None)
    ap.add_argument("--timeout", type=float, default=0.0)
    ap.add_argument("--probe", action="store_true")
    ap.add_argument("--wake-wav", default=None,
                    help="run detection over a 16k mono wav instead of the mic (deterministic test)")
    args = ap.parse_args()

    try:
        porcupine = make_porcupine(args)
    except Exception as e:  # noqa: BLE001
        msg = str(e).replace("\n", " ")
        if "different version of the library" in msg.lower():
            sys.stderr.write("PORCUPINE_VERSION_MISMATCH: keyword file needs a different "
                             "pvporcupine version\n")
        elif "accesskey" in msg.lower():
            sys.stderr.write("PORCUPINE_BAD_KEY: AccessKey rejected by Picovoice "
                             "(set a valid PORCUPINE_ACCESS_KEY)\n")
        else:
            sys.stderr.write(f"PORCUPINE_INIT_FAILED: {msg[:200]}\n")
        return 8

    if args.probe:
        sys.stdout.write(f"PROBE_OK frame_length={porcupine.frame_length} "
                         f"sample_rate={porcupine.sample_rate}\n")
        porcupine.delete()
        return 0

    if args.wake_wav:
        import struct
        import wave
        wf = wave.open(args.wake_wav, "rb")
        if (wf.getnchannels() != 1 or wf.getsampwidth() != 2
                or wf.getframerate() != porcupine.sample_rate):
            sys.stderr.write("WAVE_FORMAT: need 16kHz mono s16le\n")
            porcupine.delete()
            return 6
        hit = False
        n = porcupine.frame_length
        while True:
            raw = wf.readframes(n)
            if len(raw) < n * 2:
                break
            if porcupine.process(struct.unpack_from("<%dh" % n, raw)) >= 0:
                hit = True
                break
        porcupine.delete()
        sys.stdout.write("WAKE\n" if hit else "NO_WAKE\n")
        return 0 if hit else 7

    if not shutil.which("arecord"):
        sys.stderr.write("MIC_ERROR: arecord not found\n")
        return 6

    cmd = ["arecord", "-q", "-f", "S16_LE", "-r", str(porcupine.sample_rate),
           "-c", "1", "-t", "raw", "-"]
    if args.device:
        cmd[1:1] = ["-D", args.device]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE)
    frame_bytes = porcupine.frame_length * 2
    deadline = time.time() + args.timeout if args.timeout > 0 else None
    sys.stdout.write("LISTENING\n")
    sys.stdout.flush()
    try:
        import struct
        while True:
            if deadline and time.time() > deadline:
                sys.stdout.write("TIMEOUT\n")
                return 7
            buf = proc.stdout.read(frame_bytes)
            if not buf or len(buf) < frame_bytes:
                continue
            pcm = struct.unpack_from("<%dh" % porcupine.frame_length, buf)
            if porcupine.process(pcm) >= 0:
                sys.stdout.write("WAKE\n")
                sys.stdout.flush()
                return 0
    finally:
        proc.terminate()
        porcupine.delete()


if __name__ == "__main__":
    sys.exit(main())
