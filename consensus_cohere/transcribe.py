from __future__ import annotations

import argparse
import json
from pathlib import Path

import librosa
import numpy as np
import soundfile
import torch
from huggingface_hub import get_token
from silero_vad import get_speech_timestamps, load_silero_vad
from transformers import AutoProcessor, CohereAsrForConditionalGeneration

MODEL_ID = "CohereLabs/cohere-transcribe-03-2026"
SAMPLE_RATE = 16_000


def decode_audio(path: Path) -> np.ndarray:
    audio, sample_rate = soundfile.read(path, dtype="float32", always_2d=True)
    if not audio.size:
        raise RuntimeError(f"Decoded no audio samples from {path}")
    mono = audio.mean(axis=1)
    if sample_rate != SAMPLE_RATE:
        mono = librosa.resample(
            mono,
            orig_sr=sample_rate,
            target_sr=SAMPLE_RATE,
        )
    return np.asarray(mono, dtype=np.float32)


def transcribe_audio(audio: np.ndarray, processor, model) -> str:
    if not audio.size:
        return ""
    inputs = processor(
        audio=audio,
        sampling_rate=SAMPLE_RATE,
        return_tensors="pt",
        language="en",
        punctuation=True,
    )
    chunk_indices = inputs["audio_chunk_index"]
    row_count = inputs["input_features"].shape[0]
    expected_indices = (
        [(0, None)]
        if row_count == 1 and chunk_indices == [(0, None)]
        else [(0, index) for index in range(row_count)]
    )
    if chunk_indices != expected_indices:
        raise RuntimeError(f"Unexpected Cohere chunk ordering: {chunk_indices!r}")

    texts: dict[tuple[int, int], str] = {}
    for row, chunk_index in enumerate(chunk_indices):
        row_inputs = {
            key: value[row : row + 1]
            for key, value in inputs.items()
            if torch.is_tensor(value)
        }
        row_inputs["audio_chunk_index"] = [chunk_index]
        row_inputs = {
            key: value.to(model.device, dtype=model.dtype)
            if torch.is_tensor(value) and value.is_floating_point()
            else value.to(model.device)
            if torch.is_tensor(value)
            else value
            for key, value in row_inputs.items()
        }
        with torch.inference_mode():
            generated = model.generate(
                **row_inputs,
                max_new_tokens=256,
                num_beams=1,
                do_sample=False,
            )
        try:
            decoded = processor.decode(
                generated,
                skip_special_tokens=True,
                audio_chunk_index=row_inputs["audio_chunk_index"],
                language="en",
            )[0].strip()
        except IndexError:
            decoded = processor.tokenizer.batch_decode(
                generated,
                skip_special_tokens=True,
            )[0].strip()
        texts[chunk_index] = decoded

    if sorted(texts) != expected_indices:
        raise RuntimeError("Cohere omitted or duplicated a long-form chunk.")
    return " ".join(texts[index] for index in expected_indices if texts[index]).strip()


def load_regions(path: Path) -> list[dict[str, float]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, list):
        raise TypeError("Adaptive Cohere regions must be a JSON list.")
    regions: list[dict[str, float]] = []
    for item in payload:
        if not isinstance(item, dict):
            raise TypeError("Each adaptive Cohere region must be an object.")
        start = float(item["start"])
        end = float(item["end"])
        if start < 0 or end <= start:
            raise ValueError(f"Invalid adaptive Cohere region: {item!r}")
        regions.append({"start": start, "end": end})
    return regions


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--regions-file", type=Path)
    args = parser.parse_args()

    audio = decode_audio(args.input)
    regions = load_regions(args.regions_file) if args.regions_file else None
    if regions is None:
        vad_model = load_silero_vad(onnx=False)
        speech = get_speech_timestamps(
            torch.from_numpy(audio),
            vad_model,
            threshold=0.30,
            sampling_rate=SAMPLE_RATE,
            min_speech_duration_ms=150,
            min_silence_duration_ms=700,
            speech_pad_ms=400,
        )
        if not speech:
            args.output.write_text(json.dumps({"text": ""}) + "\n", encoding="utf-8")
            return
        trim_padding = round(0.4 * SAMPLE_RATE)
        trim_start = max(0, int(speech[0]["start"]) - trim_padding)
        trim_end = min(len(audio), int(speech[-1]["end"]) + trim_padding)
        audio = audio[trim_start:trim_end]

    token = get_token()
    processor = AutoProcessor.from_pretrained(MODEL_ID, token=token)
    model = CohereAsrForConditionalGeneration.from_pretrained(
        MODEL_ID,
        dtype=torch.bfloat16,
        token=token,
        device_map="auto",
        max_memory={0: "5GiB", "cpu": "80GiB"},
    ).eval()
    if regions is None:
        text = transcribe_audio(audio, processor, model)
        args.output.write_text(json.dumps({"text": text}) + "\n", encoding="utf-8")
        return

    duration_seconds = len(audio) / SAMPLE_RATE
    results = []
    for region in regions:
        start = min(region["start"], duration_seconds)
        end = min(region["end"], duration_seconds)
        start_sample = round(start * SAMPLE_RATE)
        end_sample = round(end * SAMPLE_RATE)
        text = transcribe_audio(audio[start_sample:end_sample], processor, model)
        results.append({"start": start, "end": end, "text": text})
    args.output.write_text(
        json.dumps(
            {
                "text": " ".join(result["text"] for result in results).strip(),
                "regions": results,
            }
        )
        + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
