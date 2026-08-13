from __future__ import annotations

import argparse
import json
import os
from dataclasses import replace
from pathlib import Path

from whisper_service import PARAKEET_PRESETS, build_runtime_config, build_transcriber


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--backend", choices=("faster-whisper", "parakeet"), required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    os.environ["WHISPER_BACKEND"] = args.backend
    if args.backend == "parakeet":
        preset_name = os.environ["WHISPER_PRESET"]
        PARAKEET_PRESETS[preset_name] = replace(
            PARAKEET_PRESETS[preset_name],
            word_confidence=False,
        )

    transcriber = build_transcriber(build_runtime_config())
    text = transcriber.transcribe_file(str(args.input))
    payload: dict[str, object] = {"text": text}
    if args.backend == "faster-whisper":
        payload["segments"] = transcriber.last_segment_metadata
    args.output.write_text(json.dumps(payload) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
