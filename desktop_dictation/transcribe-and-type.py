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


def read_recording_metadata(audio_path: Path) -> dict:
    metadata_path = audio_path.with_suffix(".json")
    try:
        with metadata_path.open(encoding="utf-8") as metadata_file:
            payload = json.load(metadata_file)
        return payload if isinstance(payload, dict) else {}
    except FileNotFoundError:
        return {}
    except (OSError, json.JSONDecodeError) as exc:
        log(f"Could not read recording metadata {metadata_path}: {exc}", error=True)
        return {}


def metadata_integer(metadata: dict, key: str) -> int:
    try:
        value = int(metadata.get(key, -1))
    except (TypeError, ValueError):
        return -1
    return value if value >= 0 else -1


def transcribe(audio_path: Path) -> tuple[str, dict[str, int], int]:
    metadata = read_recording_metadata(audio_path)
    worker_started_ms = round(time.time() * 1000)
    stop_requested_ms = metadata_integer(metadata, "stop_requested_at_ms")
    recording_ready_ms = metadata_integer(metadata, "recording_ready_at_ms")
    queue_wait_ms = (
        max(0, worker_started_ms - recording_ready_ms)
        if recording_ready_ms >= 0
        else -1
    )
    file_read_started = time.monotonic()
    audio_data = audio_path.read_bytes()
    file_read_ms = round((time.monotonic() - file_read_started) * 1000)
    upload_started_ms = round(time.time() * 1000)
    stop_to_upload_ms = (
        max(0, upload_started_ms - stop_requested_ms)
        if stop_requested_ms >= 0
        else -1
    )
    request = urllib.request.Request(
        WHISPER_SERVER_URL,
        data=audio_data,
        headers={
            "Content-Type": "application/octet-stream",
            "X-Client-Recording-Duration-Ms": str(
                metadata_integer(metadata, "recording_duration_ms")
            ),
            "X-Client-Stop-Finalize-Ms": str(
                metadata_integer(metadata, "stop_finalize_ms")
            ),
            "X-Client-Queue-Wait-Ms": str(queue_wait_ms),
            "X-Client-File-Read-Ms": str(file_read_ms),
            "X-Client-Stop-To-Upload-Ms": str(stop_to_upload_ms),
        },
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
    timings = {
        "client_recording_duration_ms": metadata_integer(
            metadata, "recording_duration_ms"
        ),
        "client_stop_finalize_ms": metadata_integer(metadata, "stop_finalize_ms"),
        "client_queue_wait_ms": queue_wait_ms,
        "client_file_read_ms": file_read_ms,
        "client_stop_to_upload_ms": stop_to_upload_ms,
        "whisper_round_trip_ms": elapsed_ms,
    }
    server_timings = payload.get("timings", {})
    if isinstance(server_timings, dict):
        timings.update(
            {
                key: int(value)
                for key, value in server_timings.items()
                if isinstance(value, (int, float))
            }
        )
    log(
        "Desktop transcription timing "
        f"client_recording_duration_ms={timings['client_recording_duration_ms']} "
        f"client_stop_finalize_ms={timings['client_stop_finalize_ms']} "
        f"client_queue_wait_ms={queue_wait_ms} "
        f"client_file_read_ms={file_read_ms} "
        f"client_stop_to_upload_ms={stop_to_upload_ms} "
        f"whisper_round_trip_ms={elapsed_ms} text={text!r}"
    )
    return text, timings, stop_requested_ms


def type_text(text: str) -> tuple[int, bool, str]:
    started = time.monotonic()
    result = subprocess.run(
        [str(TYPE_HELPER)], input=text, text=True, capture_output=True, check=False
    )
    elapsed_ms = round((time.monotonic() - started) * 1000)
    if result.returncode == 0:
        log(f"Desktop transcription typed with ydotool type_ms={elapsed_ms}")
        return elapsed_ms, True, ""

    details = (result.stderr or result.stdout).strip()
    if result.returncode == 5:
        log(f"{details} type_ms={elapsed_ms}", error=True)
        reason = (
            "modifier_pressed_during_typing"
            if "during typing" in details
            else "modifier_key_held_before_typing"
        )
        return elapsed_ms, False, reason
    raise RuntimeError(details or f"Typing helper exited with status {result.returncode}")


def format_delivery_timing(
    timings: dict[str, int], stop_requested_ms: int, type_ms: int
) -> str:
    stop_to_type_ms = (
        max(0, round(time.time() * 1000) - stop_requested_ms)
        if stop_requested_ms >= 0
        else -1
    )
    components = [
        f"client_stop_to_upload_ms={timings.get('client_stop_to_upload_ms', -1)}",
        f"whisper_round_trip_ms={timings.get('whisper_round_trip_ms', -1)}",
        f"client_paste_ms={type_ms}",
    ]
    component_values = [
        timings.get("client_stop_to_upload_ms", -1),
        timings.get("whisper_round_trip_ms", -1),
        type_ms,
    ]
    breakdown = ""
    if stop_to_type_ms >= 0 and all(value >= 0 for value in component_values):
        timing_delta = stop_to_type_ms - sum(component_values)
        if timing_delta:
            components.append(f"client_delivery_delta_ms={timing_delta}")
        breakdown = f" ({' + '.join(components)})"
    if breakdown:
        return f"client_total_ms={stop_to_type_ms}{breakdown}"
    return f"client_paste_ms={type_ms} client_total_ms={stop_to_type_ms}"


def main() -> int:
    if len(sys.argv) != 2:
        log(f"Usage: {Path(sys.argv[0]).name} AUDIO_FILE", error=True)
        return 2
    audio_path = Path(sys.argv[1])
    try:
        text, _timings, stop_requested_ms = transcribe(audio_path)
        if text:
            type_ms, inserted, refusal_reason = type_text(text)
            delivery_details = format_delivery_timing(
                _timings, stop_requested_ms, type_ms
            )
            if refusal_reason:
                delivery_details += f" refusal_reason={refusal_reason}"
            log(f"Desktop delivery timing {delivery_details}")
            report_client_event(
                "desktop-delivery-complete" if inserted else "desktop-delivery-refused",
                delivery_details,
            )
        else:
            log("Whisper returned an empty transcription")
        return 0
    except Exception as exc:
        log(f"Desktop dictation failed: {exc}", error=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
