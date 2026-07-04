#!/usr/bin/env python3
"""Local Vosk STT — transcribe a 16kHz mono 16-bit PCM WAV to text on stdout.

100% offline; no cloud STT. The WAV must be 16kHz mono s16le (the caller,
jarvis-voice-smoke.sh, converts arbitrary input with ffmpeg first).

Usage: vosk_transcribe.py <model_dir> <wav_path>
Exit:  0 ok (transcript on stdout, may be empty) · 2 bad audio · 3 model error
"""
import json
import sys
import wave

try:
    from vosk import Model, KaldiRecognizer, SetLogLevel
except Exception as e:  # pragma: no cover
    sys.stderr.write(f"vosk not installed: {e}\n")
    sys.exit(3)


def main() -> int:
    if len(sys.argv) != 3:
        sys.stderr.write("usage: vosk_transcribe.py <model_dir> <wav_path>\n")
        return 2
    model_dir, wav_path = sys.argv[1], sys.argv[2]
    SetLogLevel(-1)
    try:
        wf = wave.open(wav_path, "rb")
    except Exception as e:
        sys.stderr.write(f"cannot open wav: {e}\n")
        return 2
    if wf.getnchannels() != 1 or wf.getsampwidth() != 2 or wf.getcomptype() != "NONE":
        sys.stderr.write("wav must be mono 16-bit PCM (convert with ffmpeg)\n")
        return 2
    try:
        model = Model(model_dir)
    except Exception as e:
        sys.stderr.write(f"cannot load model {model_dir}: {e}\n")
        return 3
    rec = KaldiRecognizer(model, wf.getframerate())
    rec.SetWords(True)
    parts = []
    while True:
        data = wf.readframes(4000)
        if not data:
            break
        if rec.AcceptWaveform(data):
            r = json.loads(rec.Result())
            if r.get("text"):
                parts.append(r["text"])
    final = json.loads(rec.FinalResult())
    if final.get("text"):
        parts.append(final["text"])
    sys.stdout.write(" ".join(parts).strip())
    return 0


if __name__ == "__main__":
    sys.exit(main())
