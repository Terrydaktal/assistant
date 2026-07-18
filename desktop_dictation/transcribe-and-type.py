#!/usr/bin/env python3
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.request


WHISPER_SERVER_URL = os.environ.get(
    "WHISPER_SERVER_URL", "http://127.0.0.1:5001/transcribe_raw"
)
CLIENT_EVENT_URL = os.environ.get(
    "WHISPER_CLIENT_EVENT_URL", WHISPER_SERVER_URL.rsplit("/", 1)[0] + "/client-event"
)
TYPE_HELPER = Path(__file__).with_name("wayland-type-helper.sh")


def log(message: str, *, error: bool = False) -> None:
    timestamp = time.strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{timestamp}] {message}", file=sys.stderr if error else sys.stdout, flush=True)


def report_client_event(event: str, details: str) -> None:
    body = json.dumps({"event": event, "details": details}).encode()
    request = urllib.request.Request(
        CLIENT_EVENT_URL,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=5):
            pass
    except Exception as exc:
        log(f"Could not report client event {event!r}: {exc}", error=True)


def transcribe(audio_path: Path) -> str:
    request = urllib.request.Request(
        WHISPER_SERVER_URL,
        data=audio_path.read_bytes(),
        headers={"Content-Type": "application/octet-stream"},
        method="POST",
    )
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            payload = json.load(response)
    except urllib.error.HTTPError as exc:
        details = exc.read().decode(errors="replace")
        raise RuntimeError(f"Whisper server returned HTTP {exc.code}: {details}") from exc
    elapsed_ms = round((time.monotonic() - started) * 1000)
    text = str(payload.get("text", "")).strip()
    log(f"Desktop transcription received in {elapsed_ms} ms: {text!r}")
    return text


def type_text(text: str) -> None:
    result = subprocess.run(
        [str(TYPE_HELPER)], input=text, text=True, capture_output=True, check=False
    )
    if result.returncode == 0:
        log("Desktop transcription typed with ydotool")
        return

    details = (result.stderr or result.stdout).strip()
    if result.returncode == 5:
        report_client_event("delivery-refused", details)
        log(details, error=True)
        return
    raise RuntimeError(details or f"Typing helper exited with status {result.returncode}")


def main() -> int:
    if len(sys.argv) != 2:
        log(f"Usage: {Path(sys.argv[0]).name} AUDIO_FILE", error=True)
        return 2
    audio_path = Path(sys.argv[1])
    try:
        text = transcribe(audio_path)
        if text:
            type_text(text)
        else:
            log("Whisper returned an empty transcription")
        return 0
    except Exception as exc:
        log(f"Desktop dictation failed: {exc}", error=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
