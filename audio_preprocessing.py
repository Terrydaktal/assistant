"""Lightweight source-quality checks and conditional audio preprocessing."""

from __future__ import annotations

import math
import subprocess
import tempfile
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path

import numpy as np

ANALYSIS_SAMPLE_RATE = 16_000
FRAME_SIZE = 320
HOP_SIZE = 160
LOW_FREQUENCY_LIMIT_HZ = 60.0
ACTIVE_FRAME_MARGIN_DB = 12.0
MIN_ACTIVE_FRAMES = 5
LOW_FREQUENCY_MASK = (
    np.fft.rfftfreq(FRAME_SIZE, 1.0 / ANALYSIS_SAMPLE_RATE)
    < LOW_FREQUENCY_LIMIT_HZ
)


def _dbfs(value: float) -> float:
    return 20.0 * math.log10(max(float(value), 1e-12))


def _resampled_frames(audio_path: str | Path) -> Iterator[np.ndarray]:
    import av

    with av.open(str(audio_path)) as container:
        stream = next(iter(container.streams.audio), None)
        if stream is None:
            raise ValueError(f"Audio file has no audio stream: {audio_path}")

        resampler = av.audio.resampler.AudioResampler(
            format="flt",
            layout="mono",
            rate=ANALYSIS_SAMPLE_RATE,
        )

        def emit(value) -> Iterator[np.ndarray]:
            if value is None:
                return
            frames = value if isinstance(value, list) else [value]
            for frame in frames:
                samples = frame.to_ndarray()
                if samples.ndim == 2:
                    samples = samples[0]
                if samples.size:
                    yield np.asarray(samples, dtype=np.float32)

        for frame in container.decode(stream):
            yield from emit(resampler.resample(frame))
        yield from emit(resampler.resample(None))


@dataclass(frozen=True)
class AudioAnalysis:
    duration_seconds: float
    sample_count: int
    peak_dbfs: float
    dc_offset: float
    active_frame_count: int
    active_frame_ratio: float
    active_frame_threshold_dbfs: float
    noise_floor_dbfs: float
    speech_frame_median_dbfs: float
    low_frequency_ratio: float
    low_frequency_dbfs: float

    def as_dict(self) -> dict[str, float | int]:
        return {
            "duration_seconds": round(self.duration_seconds, 3),
            "sample_count": self.sample_count,
            "peak_dbfs": round(self.peak_dbfs, 2),
            "dc_offset": round(self.dc_offset, 8),
            "active_frame_count": self.active_frame_count,
            "active_frame_ratio": round(self.active_frame_ratio, 4),
            "active_frame_threshold_dbfs": round(self.active_frame_threshold_dbfs, 2),
            "noise_floor_dbfs": round(self.noise_floor_dbfs, 2),
            "speech_frame_median_dbfs": round(self.speech_frame_median_dbfs, 2),
            "low_frequency_ratio": round(self.low_frequency_ratio, 4),
            "low_frequency_dbfs": round(self.low_frequency_dbfs, 2),
        }

    def should_filter(
        self,
        *,
        ratio_threshold: float,
        absolute_dbfs_threshold: float,
        dc_offset_threshold: float,
    ) -> tuple[bool, str]:
        if self.active_frame_count < MIN_ACTIVE_FRAMES:
            return False, "insufficient-speech-frames"

        low_frequency_energy = (
            self.low_frequency_ratio >= ratio_threshold
            and self.low_frequency_dbfs >= absolute_dbfs_threshold
        )
        dc_and_low_frequency_energy = (
            abs(self.dc_offset) >= dc_offset_threshold
            and self.low_frequency_dbfs >= absolute_dbfs_threshold
        )
        if low_frequency_energy and dc_and_low_frequency_energy:
            return True, "low-frequency-energy-and-dc-offset"
        if low_frequency_energy:
            return True, "low-frequency-energy"
        if dc_and_low_frequency_energy:
            return True, "dc-offset-and-low-frequency-energy"
        return False, "within-thresholds"


def analyze_audio(audio_path: str | Path) -> AudioAnalysis:
    frame_levels: list[float] = []
    frame_low_ratios: list[float] = []
    frame_low_dbfs: list[float] = []
    sample_count = 0
    sample_sum = 0.0
    peak = 0.0
    buffer = np.empty(0, dtype=np.float32)

    def analyze_frame(frame: np.ndarray) -> None:
        frame64 = frame.astype(np.float64, copy=False)
        frame_rms = float(np.sqrt(np.mean(np.square(frame64)) + 1e-12))
        frame_levels.append(_dbfs(frame_rms))

        spectrum = np.fft.rfft(frame64)
        low_spectrum = np.where(LOW_FREQUENCY_MASK, spectrum, 0.0)
        low_signal = np.fft.irfft(low_spectrum, n=len(frame64))
        total_power = float(np.mean(np.square(frame64))) + 1e-12
        low_power = float(np.mean(np.square(low_signal)))
        frame_low_ratios.append(low_power / total_power)
        frame_low_dbfs.append(_dbfs(math.sqrt(low_power)))

    for samples in _resampled_frames(audio_path):
        sample_count += len(samples)
        samples64 = samples.astype(np.float64, copy=False)
        sample_sum += float(np.sum(samples64))
        if samples.size:
            peak = max(peak, float(np.max(np.abs(samples64))))
        buffer = np.concatenate((buffer, samples))
        while len(buffer) >= FRAME_SIZE:
            analyze_frame(buffer[:FRAME_SIZE])
            buffer = buffer[HOP_SIZE:]

    if not frame_levels or sample_count == 0:
        raise ValueError(f"Audio file contains no decodable samples: {audio_path}")

    levels = np.asarray(frame_levels)
    noise_floor_dbfs = float(np.percentile(levels, 10))
    active_threshold = max(noise_floor_dbfs + ACTIVE_FRAME_MARGIN_DB, -45.0)
    active_mask = levels >= active_threshold
    if np.any(active_mask):
        active_low_ratios = np.asarray(frame_low_ratios)[active_mask]
        active_low_dbfs = np.asarray(frame_low_dbfs)[active_mask]
        active_levels = levels[active_mask]
    else:
        active_low_ratios = np.asarray([], dtype=np.float64)
        active_low_dbfs = np.asarray([], dtype=np.float64)
        active_levels = np.asarray([], dtype=np.float64)

    return AudioAnalysis(
        duration_seconds=sample_count / ANALYSIS_SAMPLE_RATE,
        sample_count=sample_count,
        peak_dbfs=_dbfs(peak),
        dc_offset=sample_sum / sample_count,
        active_frame_count=int(active_mask.sum()),
        active_frame_ratio=float(active_mask.mean()),
        active_frame_threshold_dbfs=active_threshold,
        noise_floor_dbfs=noise_floor_dbfs,
        speech_frame_median_dbfs=(
            float(np.median(active_levels)) if active_levels.size else noise_floor_dbfs
        ),
        low_frequency_ratio=(
            float(np.median(active_low_ratios)) if active_low_ratios.size else 0.0
        ),
        low_frequency_dbfs=(
            float(np.median(active_low_dbfs)) if active_low_dbfs.size else -120.0
        ),
    )


@dataclass
class AudioPreprocessingResult:
    transcription_path: str
    analysis: AudioAnalysis | None
    filter_applied: bool = False
    filter_reason: str = "not-run"
    filtered_analysis: AudioAnalysis | None = None
    error: str | None = None

    def as_dict(self) -> dict[str, object]:
        return {
            "filter_applied": self.filter_applied,
            "filter_reason": self.filter_reason,
            "analysis": self.analysis.as_dict() if self.analysis else None,
            "filtered_analysis": (
                self.filtered_analysis.as_dict() if self.filtered_analysis else None
            ),
            "error": self.error,
        }


@contextmanager
def prepare_audio_for_transcription(
    audio_path: str | Path,
    *,
    enabled: bool = True,
    cutoff_hz: float = 80.0,
    ratio_threshold: float = 0.25,
    absolute_dbfs_threshold: float = -35.0,
    dc_offset_threshold: float = 0.005,
) -> Iterator[AudioPreprocessingResult]:
    source_path = Path(audio_path)
    result = AudioPreprocessingResult(transcription_path=str(source_path), analysis=None)
    temporary_path: Path | None = None

    try:
        result.analysis = analyze_audio(source_path)
        if not enabled:
            result.filter_reason = "disabled"
        else:
            should_filter, reason = result.analysis.should_filter(
                ratio_threshold=ratio_threshold,
                absolute_dbfs_threshold=absolute_dbfs_threshold,
                dc_offset_threshold=dc_offset_threshold,
            )
            result.filter_reason = reason
            if should_filter:
                with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as output:
                    temporary_path = Path(output.name)
                command = [
                    "ffmpeg",
                    "-hide_banner",
                    "-loglevel",
                    "error",
                    "-y",
                    "-i",
                    str(source_path),
                    "-af",
                    f"highpass=f={cutoff_hz:g}:p=2",
                    "-c:a",
                    "pcm_s16le",
                    str(temporary_path),
                ]
                subprocess.run(command, check=True, capture_output=True, text=True)
                result.filtered_analysis = analyze_audio(temporary_path)
                if result.filtered_analysis.peak_dbfs >= -0.1:
                    raise RuntimeError(
                        "high-pass output has insufficient peak headroom "
                        f"({result.filtered_analysis.peak_dbfs:.2f} dBFS)"
                    )
                result.transcription_path = str(temporary_path)
                result.filter_applied = True
    except Exception as exc:  # noqa: BLE001 - preprocessing must fail open
        result.error = f"{type(exc).__name__}: {exc}"
        result.transcription_path = str(source_path)
        result.filter_applied = False
        if result.filter_reason == "not-run":
            result.filter_reason = "analysis-failed"
    try:
        yield result
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
