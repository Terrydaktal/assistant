import argparse
import copy
import ctypes
import gc
import json
import logging
from logging.config import dictConfig
import os
import re
import shutil
import subprocess
import tempfile
import time
import wave
from contextlib import asynccontextmanager, contextmanager
from dataclasses import dataclass
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse


service_logger = logging.getLogger("assistant.whisper")

ANSI_RESET = "\033[0m"
ANSI_CYAN = "\033[36m"
ANSI_LIGHT_TEAL = "\033[1;96m"
ANSI_MAGENTA = "\033[1;35m"
ANSI_GREEN = "\033[1;32m"
ANSI_YELLOW = "\033[1;93m"
ANSI_BREAKDOWN_VALUE = "\033[93m"
ANSI_RED = "\033[1;31m"


class FriendlyLogSource(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        if (
            record.name == "assistant.whisper"
            and record.getMessage().startswith(("Delivery ", "Recording prepared:"))
        ):
            record.source = "Client"
        elif record.name in {"assistant.whisper", "uvicorn", "uvicorn.access", "uvicorn.error"}:
            record.source = "Server"
        else:
            record.source = "Application"
        return True


def ansi(text: str, colour: str) -> str:
    return f"{colour}{text}{ANSI_RESET}" if text else ""


def colour_timing_fields(output: str) -> str:
    coloured_fields = []
    in_breakdown = False
    for part in re.split(r"(\s+)", output):
        if not part or part.isspace():
            coloured_fields.append(part)
            continue

        leading = ""
        while part.startswith(("(", "[")):
            leading += part[0]
            part = part[1:]
            in_breakdown = True

        trailing = ""
        while part.endswith((")", "]")):
            trailing = part[-1] + trailing
            part = part[:-1]

        if part == "+":
            coloured_fields.append(f"{ansi(leading, ANSI_CYAN)}{ansi(part, ANSI_CYAN)}")
        elif "=" not in part:
            coloured_fields.append(f"{ansi(leading, ANSI_CYAN)}{ansi(part, ANSI_CYAN)}")
        else:
            key, value = part.split("=", 1)
            if key == "outcome":
                value_colour = ANSI_GREEN if value == "success" else ANSI_RED
            else:
                value_colour = ANSI_BREAKDOWN_VALUE if in_breakdown else ANSI_YELLOW
            key_colour = ANSI_CYAN if in_breakdown else ANSI_LIGHT_TEAL
            coloured_fields.append(
                f"{ansi(leading, ANSI_CYAN)}"
                f"{ansi(key, key_colour)}={ansi(value, value_colour)}"
            )

        if trailing:
            coloured_fields.append(ansi(trailing, ANSI_CYAN))
            in_breakdown = False
    return "".join(coloured_fields)


def colour_transcription_event(output: str) -> str:
    transcription_output, separator, timing_output = output.rpartition(" | ")
    if not separator or not timing_output.startswith("outcome="):
        transcription_output = output
        timing_output = ""

    match = re.match(
        r"^(Transcribed Raw) \(([^)]*)\): (.*)$",
        transcription_output,
        flags=re.DOTALL,
    )
    if not match:
        return output

    coloured = (
        f"{ansi(match.group(1), ANSI_CYAN)} "
        f"({ansi(match.group(2), ANSI_MAGENTA)}): "
        f"{ansi(match.group(3), ANSI_GREEN)}"
    )
    if timing_output:
        coloured += f" {ansi('|', ANSI_CYAN)} {colour_timing_fields(timing_output)}"
    return coloured


def service_log(*args, **kwargs) -> None:
    output = kwargs.get("sep", " ").join(str(arg) for arg in args)
    lowered = output.lower()
    if output.startswith(("ERROR", "Failed")):
        level = logging.ERROR
        color_message = ansi(output, ANSI_RED)
    elif (
        output.startswith(("WARNING", "CUDA OOM"))
        or output.startswith("Delivery refused:")
        or "refusing to type" in lowered
    ):
        level = logging.WARNING
        color_message = ansi(output, ANSI_YELLOW)
    else:
        level = logging.INFO
        if output.startswith("Transcribed Raw"):
            color_message = colour_transcription_event(output)
        elif output.startswith("Transcription timing"):
            prefix, _, timing = output.partition(" ")
            color_message = f"{ansi(prefix, ANSI_CYAN)} {colour_timing_fields(timing)}"
        elif output.startswith(
            ("Delivery complete: ", "Delivery refused: ", "Recording prepared: ")
        ):
            label, timing = output.split(": ", 1)
            color_message = (
                f"{ansi(label + ':', ANSI_CYAN)} {colour_timing_fields(timing)}"
            )
        elif output.startswith("Audio received: "):
            label, timing = output.split(": ", 1)
            color_message = (
                f"{ansi(label + ':', ANSI_GREEN)} {colour_timing_fields(timing)}"
            )
        else:
            color_message = output

    service_logger.log(level, output, extra={"color_message": color_message})


print = service_log


def preload_local_cuda_libraries() -> None:
    site_packages = next(
        Path(__file__).resolve().parent.glob(".venv/lib/python*/site-packages"),
        None,
    )
    if site_packages is None:
        return

    nvidia_root = site_packages / "nvidia"
    nvidia_libs = [
        str(path)
        for path in (nvidia_root / "cublas" / "lib", nvidia_root / "cudnn" / "lib")
        if path.is_dir()
    ]
    if not nvidia_libs:
        return
    existing_ld = os.environ.get("LD_LIBRARY_PATH", "")
    new_ld = ":".join(nvidia_libs)
    os.environ["LD_LIBRARY_PATH"] = f"{new_ld}:{existing_ld}" if existing_ld else new_ld

    for library_path in (
        nvidia_root / "cublas" / "lib" / "libcublas.so.12",
        nvidia_root / "cudnn" / "lib" / "libcudnn.so.9",
    ):
        if library_path.exists():
            try:
                ctypes.CDLL(str(library_path))
            except OSError:
                pass


preload_local_cuda_libraries()


DEFAULT_FASTER_WHISPER_MODEL = "large-v3"
DEFAULT_CANARY_MODEL = "nvidia/canary-qwen-2.5b"
DEFAULT_PARAKEET_MODEL = "nvidia/parakeet-tdt-0.6b-v2"
DEFAULT_GRANITE_SPEECH_MODEL = "ibm-granite/granite-speech-4.1-2b-GGUF:Q8_0"
DEFAULT_GRANITE_PROMPT = "transcribe the speech with proper punctuation and capitalization."
DEFAULT_PROGRAMMING_PHRASES_FILE = Path(__file__).with_name("programming_phrases.txt")
DEFAULT_GRANITE_KEYWORDS_FILE = Path(__file__).with_name("granite_programming_keywords.txt")

TECHNICAL_TEXT_REPLACEMENTS = {
    "origin slash main": "origin/main",
    "origin/slash main": "origin/main",
    "d89f d01": "D89FD01",
    "d89 fd01": "D89FD01",
    "d 89 f d 01": "D89FD01",
    "copy queue": "CopyQ",
    "copy q": "CopyQ",
    "copyq": "CopyQ",
    "as rock": "ASRock",
    "as roc": "ASRock",
    "asrock": "ASRock",
    "asroc": "ASRock",
    "x bind keys": "xbindkeys",
    "xbind keys": "xbindkeys",
    "ex bind keys": "xbindkeys",
    "expined key": "xbindkeys",
    "xbindkeys": "xbindkeys",
    "mod probe": "modprobe",
    "mob probe": "modprobe",
    "mod pro": "modprobe",
    "mob pro": "modprobe",
    "modpro": "modprobe",
    "nct 6683": "NCT6683",
    "n c t 6683": "NCT6683",
    "work trees": "worktrees",
    "work tree": "worktree",
    "stage diff": "staged diff",
    "kd": "KDE",
    "s h fmt": "shfmt",
    "ssh fmt": "shfmt",
    "sh fmt": "shfmt",
    "shell check": "ShellCheck",
    "s c two zero eight eight": "SC2088",
    "sc two zero eight eight": "SC2088",
    "s c 2088": "SC2088",
    "sc 2088": "SC2088",
    "bootstrap dot sh": "bootstrap.sh",
    "cachyos": "CachyOS",
}


def normalize_technical_text(text: str) -> str:
    result = text
    for spoken, canonical in sorted(
        TECHNICAL_TEXT_REPLACEMENTS.items(),
        key=lambda item: len(item[0]),
        reverse=True,
    ):
        result = re.sub(
            rf"\b{re.escape(spoken)}\b",
            lambda _match, replacement=canonical: replacement,
            result,
            flags=re.IGNORECASE,
        )
    return result


def preserve_keyword_casing(text: str, keywords: list[str]) -> str:
    result = text
    for canonical in sorted(keywords, key=len, reverse=True):
        result = re.sub(
            rf"(?<!\w){re.escape(canonical)}(?!\w)",
            lambda _match, replacement=canonical: replacement,
            result,
            flags=re.IGNORECASE,
        )
    return result


def technical_canonical_terms() -> list[str]:
    terms = set(TECHNICAL_TEXT_REPLACEMENTS.values())
    if DEFAULT_GRANITE_KEYWORDS_FILE.is_file():
        terms.update(
            line.strip()
            for line in DEFAULT_GRANITE_KEYWORDS_FILE.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        )
    return sorted(terms, key=len, reverse=True)


def canonicalize_technical_text(text: str) -> str:
    normalized = normalize_technical_text(text)
    return preserve_keyword_casing(normalized, technical_canonical_terms())


def protect_technical_terms(text: str) -> tuple[str, dict[str, str]]:
    protected = text
    replacements: dict[str, str] = {}
    patterns = [
        rf"(?<!\w){re.escape(term)}(?!\w)"
        for term in technical_canonical_terms()
    ]
    patterns.extend(
        (
            r"(?<!\w)[0-9A-F]{7,40}(?!\w)",
            r"(?<!\w)--?[A-Za-z0-9][A-Za-z0-9_-]*(?!\w)",
            r"(?<!\w)[A-Za-z0-9_~.-]*(?:/|\.|_)[A-Za-z0-9_~./-]+(?!\w)",
        )
    )
    combined = re.compile("|".join(f"(?:{pattern})" for pattern in patterns), re.IGNORECASE)

    def replace(match: re.Match) -> str:
        placeholder = f"ZXQTECHTOKEN{len(replacements)}QXZ"
        replacements[placeholder] = match.group(0)
        return placeholder

    return combined.sub(replace, protected), replacements


def restore_technical_terms(text: str, replacements: dict[str, str]) -> str:
    restored = text
    for placeholder, original in replacements.items():
        restored = restored.replace(placeholder, original)
    return restored


@dataclass(frozen=True)
class ParakeetPreset:
    name: str
    sample_rate: int
    channels: int
    channel_selector: int | str | None
    input_encoding: str
    compute_dtype: str
    decoder_strategy: str
    beam_size: int
    max_symbols_per_step: int
    loop_labels: bool
    use_cuda_graph_decoder: bool
    allow_cuda_graphs: bool
    batch_size: int
    max_segment_seconds: float
    forced_split_overlap_seconds: float
    preserve_internal_silence: bool
    vad_threshold: float | None
    min_speech_duration_ms: int
    min_silence_duration_ms: int
    speech_pad_ms: int
    word_timestamps: bool
    word_confidence: bool
    phrase_boosting: bool
    context_score: float
    depth_scaling: float
    boosting_tree_alpha: float


PARAKEET_PRESETS = {
    "programming": ParakeetPreset(
        name="programming",
        sample_rate=16_000,
        channels=1,
        channel_selector=0,
        input_encoding="lossless",
        compute_dtype="bf16",
        decoder_strategy="greedy_batch",
        beam_size=1,
        max_symbols_per_step=10,
        loop_labels=True,
        use_cuda_graph_decoder=True,
        allow_cuda_graphs=False,
        batch_size=1,
        max_segment_seconds=25.0,
        forced_split_overlap_seconds=0.0,
        preserve_internal_silence=True,
        vad_threshold=None,
        min_speech_duration_ms=150,
        min_silence_duration_ms=800,
        speech_pad_ms=250,
        word_timestamps=False,
        word_confidence=False,
        phrase_boosting=True,
        context_score=1.0,
        depth_scaling=2.0,
        boosting_tree_alpha=1.5,
    ),
    "rustly-pocket-conversation": ParakeetPreset(
        name="rustly-pocket-conversation",
        sample_rate=16_000,
        channels=1,
        channel_selector=0,
        input_encoding="lossless",
        compute_dtype="bf16",
        decoder_strategy="greedy_batch",
        beam_size=1,
        max_symbols_per_step=10,
        loop_labels=True,
        use_cuda_graph_decoder=True,
        allow_cuda_graphs=False,
        batch_size=1,
        max_segment_seconds=25.0,
        forced_split_overlap_seconds=2.0,
        preserve_internal_silence=False,
        vad_threshold=0.30,
        min_speech_duration_ms=250,
        min_silence_duration_ms=600,
        speech_pad_ms=450,
        word_timestamps=True,
        word_confidence=True,
        phrase_boosting=False,
        context_score=1.0,
        depth_scaling=2.0,
        boosting_tree_alpha=0.5,
    ),
    "accuracy": ParakeetPreset(
        name="accuracy",
        sample_rate=16_000,
        channels=1,
        channel_selector=0,
        input_encoding="lossless",
        compute_dtype="bf16",
        decoder_strategy="greedy_batch",
        beam_size=1,
        max_symbols_per_step=10,
        loop_labels=True,
        use_cuda_graph_decoder=True,
        allow_cuda_graphs=False,
        batch_size=1,
        max_segment_seconds=120.0,
        forced_split_overlap_seconds=0.0,
        preserve_internal_silence=True,
        vad_threshold=0.30,
        min_speech_duration_ms=150,
        min_silence_duration_ms=700,
        speech_pad_ms=400,
        word_timestamps=True,
        word_confidence=True,
        phrase_boosting=True,
        context_score=1.0,
        depth_scaling=2.0,
        boosting_tree_alpha=0.5,
    ),
    "accuracy-beam-experimental": ParakeetPreset(
        name="accuracy-beam-experimental",
        sample_rate=16_000,
        channels=1,
        channel_selector=0,
        input_encoding="lossless",
        compute_dtype="bf16",
        decoder_strategy="malsd_batch",
        beam_size=5,
        max_symbols_per_step=10,
        loop_labels=False,
        use_cuda_graph_decoder=False,
        allow_cuda_graphs=True,
        batch_size=1,
        max_segment_seconds=120.0,
        forced_split_overlap_seconds=0.0,
        preserve_internal_silence=True,
        vad_threshold=0.30,
        min_speech_duration_ms=150,
        min_silence_duration_ms=700,
        speech_pad_ms=400,
        word_timestamps=True,
        word_confidence=False,
        phrase_boosting=True,
        context_score=1.0,
        depth_scaling=2.0,
        boosting_tree_alpha=0.5,
    ),
}


@dataclass(frozen=True)
class RuntimeConfig:
    backend: str
    model_name: str
    parakeet_preset: str
    parakeet_key_phrases_file: str
    parakeet_channel_selector: int | str | None
    granite_prompt: str
    granite_max_new_tokens: int
    granite_temperature: float
    granite_server_binary: str
    granite_server_host: str
    granite_server_port: int
    granite_server_startup_timeout_seconds: float
    granite_request_timeout_seconds: float
    granite_context_size: int
    device: str
    compute_type: str
    language: str
    beam_size: int
    vad_filter: bool
    vad_threshold: float
    vad_min_silence_ms: int
    vad_speech_pad_ms: int
    log_prob_threshold: float
    no_speech_threshold: float
    compression_ratio_threshold: float
    initial_prompt: str
    batch_threshold_seconds: float
    long_audio_batch_size: int
    cpu_fallback_enabled: bool
    cpu_fallback_compute_type: str
    cpu_fallback_cpu_threads: int
    recovery_dir: str
    recovery_request_limit: int
    failed_recovery_limit: int
    variant_conversion_enabled: bool
    variant_source: str
    variant_target: str
    host: str
    port: int
    http_access_log: bool


class FasterWhisperTranscriber:
    def __init__(self, config: RuntimeConfig) -> None:
        from faster_whisper import BatchedInferencePipeline, WhisperModel

        self.config = config
        self._whisper_model_cls = WhisperModel
        self._batched_pipeline_cls = BatchedInferencePipeline
        self.model = None
        self._batched_pipeline = None
        self._cpu_fallback_model = None
        self._gpu_model_needs_reload = False
        self._load_primary_model()

    def transcribe_file(self, audio_path: str) -> str:
        primary_model = self._get_primary_model()
        duration_seconds = self._audio_duration_seconds(audio_path)
        use_batched = (
            self.config.device.startswith("cuda")
            and self.config.long_audio_batch_size > 1
            and duration_seconds is not None
            and duration_seconds >= self.config.batch_threshold_seconds
        )

        if use_batched:
            print(
                "Long audio detected: "
                f"duration_seconds={duration_seconds:.2f} "
                f"threshold_seconds={self.config.batch_threshold_seconds:g} "
                f"batch_size={self.config.long_audio_batch_size}; "
                "using batched GPU transcription."
            )
            try:
                return self._transcribe_batched(primary_model, audio_path)
            except Exception as exc:
                self._batched_pipeline = None
                batched_error = str(exc)
                print(
                    "WARNING: Batched GPU transcription failed; retrying the same audio "
                    f"with ordinary GPU transcription before CPU fallback: {batched_error}"
                )
            # Leave the exception scope before clearing cache so its traceback cannot
            # retain temporary allocations needed by the ordinary GPU retry.
            self._release_cuda_cache()

        try:
            return self._transcribe_with_model(primary_model, audio_path)
        except Exception as exc:
            if self._should_reload_primary_model(exc):
                self._invalidate_primary_model(str(exc))
            if not self._should_retry_on_cpu(exc):
                raise
            print(
                "CUDA OOM during faster-whisper transcription; retrying same audio on CPU "
                "and scheduling a fresh GPU model load for the next GPU request."
            )
            return self._transcribe_with_model(self._get_cpu_fallback_model(), audio_path)

    def _transcription_options(self) -> dict:
        return {
            "word_timestamps": False,
            "language": self.config.language,
            "beam_size": self.config.beam_size,
            "vad_filter": self.config.vad_filter,
            "vad_parameters": {
                "threshold": self.config.vad_threshold,
                "min_silence_duration_ms": self.config.vad_min_silence_ms,
                "speech_pad_ms": self.config.vad_speech_pad_ms,
            },
            "log_prob_threshold": self.config.log_prob_threshold,
            "no_speech_threshold": self.config.no_speech_threshold,
            "compression_ratio_threshold": self.config.compression_ratio_threshold,
            "initial_prompt": self.config.initial_prompt or None,
        }

    def _transcribe_with_model(self, model, audio_path: str) -> str:
        segments, _info = model.transcribe(audio_path, **self._transcription_options())
        return "".join(segment.text for segment in segments).strip()

    def _transcribe_batched(self, primary_model, audio_path: str) -> str:
        if self._batched_pipeline is None:
            self._batched_pipeline = self._batched_pipeline_cls(primary_model)
        segments, _info = self._batched_pipeline.transcribe(
            audio_path,
            batch_size=self.config.long_audio_batch_size,
            **self._transcription_options(),
        )
        return "".join(segment.text for segment in segments).strip()

    def _audio_duration_seconds(self, audio_path: str) -> float | None:
        try:
            import av

            with av.open(audio_path) as container:
                if container.duration is not None:
                    return float(container.duration / av.time_base)
                for stream in container.streams.audio:
                    if stream.duration is not None and stream.time_base is not None:
                        return float(stream.duration * stream.time_base)
        except Exception as exc:
            print(
                "WARNING: Could not inspect audio duration; using ordinary transcription: "
                f"{exc}"
            )
        return None

    def _release_cuda_cache(self) -> None:
        gc.collect()
        try:
            import torch

            if torch.cuda.is_available():
                torch.cuda.empty_cache()
        except (ImportError, RuntimeError):
            pass

    def _should_retry_on_cpu(self, exc: Exception) -> bool:
        if not self.config.cpu_fallback_enabled:
            return False
        if not self.config.device.startswith("cuda"):
            return False
        message = " ".join(str(arg) for arg in getattr(exc, "args", ()) if arg)
        message = message or str(exc)
        lowered = message.lower()
        return "out of memory" in lowered or "cuda" in lowered and "memory" in lowered

    def _get_cpu_fallback_model(self):
        if self._cpu_fallback_model is not None:
            return self._cpu_fallback_model

        print(
            f"Loading faster-whisper CPU fallback model '{self.config.model_name}' "
            f"with compute type '{self.config.cpu_fallback_compute_type}' and "
            f"cpu_threads={self.config.cpu_fallback_cpu_threads}..."
        )
        self._cpu_fallback_model = self._whisper_model_cls(
            self.config.model_name,
            device="cpu",
            compute_type=self.config.cpu_fallback_compute_type,
            cpu_threads=self.config.cpu_fallback_cpu_threads,
        )
        print("faster-whisper CPU fallback model loaded successfully.")
        return self._cpu_fallback_model

    def _load_primary_model(self) -> None:
        print(
            f"Loading faster-whisper model '{self.config.model_name}' on device '{self.config.device}' "
            f"with compute type '{self.config.compute_type}'..."
        )
        self.model = self._whisper_model_cls(
            self.config.model_name,
            device=self.config.device,
            compute_type=self.config.compute_type,
        )
        self._batched_pipeline = None
        self._gpu_model_needs_reload = False
        print("faster-whisper model loaded successfully.")

    def _get_primary_model(self):
        if self.model is None or self._gpu_model_needs_reload:
            print("Reloading faster-whisper primary model before GPU transcription attempt...")
            self._load_primary_model()
        return self.model

    def _invalidate_primary_model(self, reason: str) -> None:
        self.model = None
        self._batched_pipeline = None
        self._gpu_model_needs_reload = True
        print(f"Marked faster-whisper primary model for reload: {reason}")

    def _should_reload_primary_model(self, exc: Exception) -> bool:
        if not self.config.device.startswith("cuda"):
            return False
        message = " ".join(str(arg) for arg in getattr(exc, "args", ()) if arg)
        message = message or str(exc)
        lowered = message.lower()
        reload_markers = (
            "out of memory",
            "cudaerrorinvaliddevice",
            "invalid device ordinal",
            "cuda error",
            "cublas",
            "cudnn",
        )
        return any(marker in lowered for marker in reload_markers)


def first_nemo_transcription_text(result) -> str:
    """Normalize NeMo ASR's text, hypothesis, and nested batch return shapes."""
    if result is None:
        return ""
    if isinstance(result, str):
        return result.strip()
    if hasattr(result, "text"):
        return str(result.text).strip()
    if isinstance(result, dict):
        for key in ("text", "transcript", "transcription"):
            if key in result:
                return first_nemo_transcription_text(result[key])
        return ""
    if isinstance(result, (list, tuple)):
        if not result:
            return ""
        return first_nemo_transcription_text(result[0])
    return str(result).strip()


def first_nemo_hypothesis(result):
    if result is None:
        return None
    if hasattr(result, "text"):
        return result
    if isinstance(result, (list, tuple)):
        for item in result:
            hypothesis = first_nemo_hypothesis(item)
            if hypothesis is not None:
                return hypothesis
    return None


class ParakeetTranscriber:
    def __init__(self, config: RuntimeConfig) -> None:
        try:
            import torch
            from nemo.collections.asr.models import ASRModel
        except ImportError as exc:
            raise RuntimeError(
                "Parakeet requires NeMo ASR. Install it with "
                "`uv sync --extra parakeet` or `uv pip install 'nemo-toolkit[asr]'`."
            ) from exc

        self.config = config
        self.model_name = config.model_name
        self.device = config.device
        self.preset = PARAKEET_PRESETS[config.parakeet_preset]
        self.torch = torch
        self.vad_model = None
        requested_device = torch.device(config.device)
        print(
            f"Loading Parakeet TDT model '{self.model_name}' via NeMo ASR on device "
            f"'{requested_device}'..."
        )
        print(
            f"torch.cuda.is_available()={torch.cuda.is_available()} "
            f"torch.cuda.device_count()={torch.cuda.device_count()}"
        )
        print(
            f"Parakeet CUDA stack: torch={torch.__version__} "
            f"cuda={torch.version.cuda} cudnn={torch.backends.cudnn.version()}"
        )
        if requested_device.type == "cuda" and not torch.cuda.is_available():
            raise RuntimeError(
                "Parakeet was requested on CUDA but torch.cuda.is_available() is false."
            )

        compute_dtype = self._select_compute_dtype(requested_device)
        # Convert on CPU so CUDA only receives the final low-precision parameter copy.
        self.model = ASRModel.from_pretrained(
            model_name=self.model_name,
            map_location=torch.device("cpu"),
        )
        self.model = self.model.to(dtype=compute_dtype)
        self.model = self.model.to(requested_device)
        self.model.eval()
        self._configure_decoding()
        self._trim_cuda_cache()
        first_param = next(self.model.parameters(), None)
        param_device = str(first_param.device) if first_param is not None else "unknown"
        print(f"Parakeet first parameter device: {param_device}")
        print(
            "Parakeet preset: "
            f"{self.preset.name} sample_rate={self.preset.sample_rate} "
            f"channels={self.preset.channels} compute_dtype={compute_dtype} "
            f"batch_size={self.preset.batch_size} "
            f"channel_selector={config.parakeet_channel_selector}"
        )
        self._log_cuda_memory("after model load")
        print("Parakeet TDT model loaded successfully.")

    def transcribe_file(self, audio_path: str) -> str:
        try:
            if self.preset.vad_threshold is not None:
                text = self._transcribe_with_vad(audio_path)
            else:
                text = self._transcribe_one_file(audio_path)
            return text
        except Exception as exc:
            if "out of memory" in str(exc).lower():
                self._trim_cuda_cache()
            raise
        finally:
            self._log_cuda_memory("after request")

    def _trim_cuda_cache(self) -> None:
        if self.device.startswith("cuda") and self.torch.cuda.is_available():
            self.torch.cuda.empty_cache()

    def _log_cuda_memory(self, stage: str) -> None:
        if not self.device.startswith("cuda") or not self.torch.cuda.is_available():
            return
        device = self.torch.device(self.device)
        mib = 1024 * 1024
        allocated = self.torch.cuda.memory_allocated(device) / mib
        reserved = self.torch.cuda.memory_reserved(device) / mib
        free, total = self.torch.cuda.mem_get_info(device)
        print(
            f"Parakeet CUDA memory {stage}: allocated_mib={allocated:.1f} "
            f"reserved_mib={reserved:.1f} driver_free_mib={free / mib:.1f} "
            f"driver_total_mib={total / mib:.1f}"
        )

    def _select_compute_dtype(self, requested_device):
        if requested_device.type != "cuda":
            return self.torch.float32
        if self.preset.compute_dtype == "bf16" and self.torch.cuda.is_bf16_supported():
            return self.torch.bfloat16
        return self.torch.float16

    def _configure_decoding(self) -> None:
        from omegaconf import OmegaConf

        if not hasattr(self.model, "change_decoding_strategy"):
            raise RuntimeError("The selected Parakeet model does not expose NeMo decoding configuration.")

        boosting_tree = {
            "context_score": self.preset.context_score,
            "depth_scaling": self.preset.depth_scaling,
        }
        key_phrases_file = self.config.parakeet_key_phrases_file.strip()
        if self.preset.phrase_boosting and key_phrases_file:
            key_phrases_path = Path(key_phrases_file).expanduser()
            if not key_phrases_path.is_file():
                raise FileNotFoundError(f"Parakeet key phrases file does not exist: {key_phrases_path}")
            boosting_tree["key_phrases_file"] = str(key_phrases_path)
            print(
                f"Parakeet phrase boosting enabled: file='{key_phrases_path}' "
                f"alpha={self.preset.boosting_tree_alpha}"
            )
        elif self.preset.phrase_boosting:
            print(
                "Parakeet phrase boosting preset is enabled, but no key phrases file was supplied; "
                "decoding will run without a boosting tree."
            )
        elif key_phrases_file:
            print(
                f"Parakeet preset '{self.preset.name}' disables phrase boosting; "
                f"ignoring key phrases file '{key_phrases_file}'."
            )

        decoder_options = {
            "max_symbols_per_step": self.preset.max_symbols_per_step,
            "ngram_lm_model": None,
            "ngram_lm_alpha": 0.0,
            "boosting_tree": boosting_tree,
            "boosting_tree_alpha": self.preset.boosting_tree_alpha,
        }
        if self.preset.decoder_strategy == "greedy_batch":
            decoder_section = "greedy"
            decoder_options.update(
                {
                    "loop_labels": self.preset.loop_labels,
                    "use_cuda_graph_decoder": self.preset.use_cuda_graph_decoder,
                }
            )
        elif self.preset.decoder_strategy == "malsd_batch":
            decoder_section = "beam"
            decoder_options.update(
                {
                    "beam_size": self.preset.beam_size,
                    "allow_cuda_graphs": self.preset.allow_cuda_graphs,
                }
            )
        else:
            raise ValueError(
                f"Unsupported Parakeet decoder strategy: {self.preset.decoder_strategy}"
            )

        confidence_config = {
            "preserve_frame_confidence": self.preset.word_confidence,
            "preserve_token_confidence": self.preset.word_confidence,
            "preserve_word_confidence": self.preset.word_confidence,
            "exclude_blank": True,
            "aggregation": "mean",
            "method_cfg": {
                "name": "entropy",
                "entropy_type": "tsallis",
                "alpha": 0.33,
                "entropy_norm": "exp",
            },
        }
        decoding_config = OmegaConf.create(
            {
                "strategy": self.preset.decoder_strategy,
                "compute_timestamps": self.preset.word_timestamps,
                "rnnt_timestamp_type": "word",
                "confidence_cfg": confidence_config,
                decoder_section: decoder_options,
            }
        )
        self.model.change_decoding_strategy(decoding_config, verbose=False)
        graph_setting = (
            self.preset.use_cuda_graph_decoder
            if decoder_section == "greedy"
            else self.preset.allow_cuda_graphs
        )
        print(
            "Parakeet decoding configured: "
            f"strategy={self.preset.decoder_strategy} "
            f"beam_size={self.preset.beam_size} "
            f"max_symbols_per_step={self.preset.max_symbols_per_step} "
            f"cuda_graphs={graph_setting}"
        )

    def _transcribe_one_file(self, audio_path: str) -> str:
        with self.torch.inference_mode():
            result = self.model.transcribe(
                [audio_path],
                batch_size=self.preset.batch_size,
                return_hypotheses=self.preset.word_timestamps or self.preset.word_confidence,
                timestamps=self.preset.word_timestamps,
                channel_selector=self.config.parakeet_channel_selector,
                verbose=False,
            )
        self._log_word_diagnostics(result)
        return first_nemo_transcription_text(result)

    def _log_word_diagnostics(self, result) -> None:
        if not self.preset.word_timestamps and not self.preset.word_confidence:
            return
        hypothesis = first_nemo_hypothesis(result)
        if hypothesis is None:
            print("Parakeet word diagnostics unavailable: no hypothesis returned.")
            return

        timestamp_data = (
            getattr(hypothesis, "timestamp", None)
            or getattr(hypothesis, "timestep", None)
            or {}
        )
        words = timestamp_data.get("word", []) if isinstance(timestamp_data, dict) else []
        confidences = list(getattr(hypothesis, "word_confidence", None) or [])
        diagnostics = []
        for index, word_data in enumerate(words):
            if isinstance(word_data, dict):
                entry = {
                    key: word_data[key]
                    for key in ("word", "start", "end")
                    if key in word_data
                }
            else:
                entry = {"word": str(word_data)}
            if index < len(confidences):
                confidence = confidences[index]
                if hasattr(confidence, "item"):
                    confidence = confidence.item()
                entry["confidence"] = round(float(confidence), 4)
            diagnostics.append(entry)
        print(
            "Parakeet word diagnostics: "
            + json.dumps(diagnostics, ensure_ascii=True, separators=(",", ":"))
        )

    def _get_vad_model(self):
        if self.vad_model is None:
            from silero_vad import load_silero_vad

            print(f"Loading Silero VAD model for Parakeet '{self.preset.name}' preset...")
            self.vad_model = load_silero_vad(onnx=False)
        return self.vad_model

    def _read_audio_for_vad(self, audio_path: str):
        import soundfile
        import torchaudio

        samples, sample_rate = soundfile.read(
            audio_path,
            dtype="float32",
            always_2d=True,
        )
        waveform = self.torch.from_numpy(samples.T)
        if waveform.ndim > 1 and waveform.shape[0] > 1:
            channel_selector = self.config.parakeet_channel_selector
            if isinstance(channel_selector, int):
                if channel_selector >= waveform.shape[0]:
                    raise ValueError(
                        f"Cannot select channel {channel_selector} from audio with "
                        f"{waveform.shape[0]} channels."
                    )
                waveform = waveform[channel_selector : channel_selector + 1]
            else:
                waveform = waveform.mean(dim=0, keepdim=True)
        if sample_rate != self.preset.sample_rate:
            waveform = torchaudio.functional.resample(
                waveform,
                sample_rate,
                self.preset.sample_rate,
            )
        return waveform.squeeze(0)

    def _save_vad_segment(self, path: str, audio) -> None:
        pcm = (
            audio.detach()
            .cpu()
            .clamp(-1.0, 1.0)
            .mul(32767.0)
            .to(self.torch.int16)
            .numpy()
            .tobytes()
        )
        with wave.open(path, "wb") as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(self.preset.sample_rate)
            output.writeframes(pcm)

    def _transcribe_with_vad(self, audio_path: str) -> str:
        from silero_vad import get_speech_timestamps

        audio = self._read_audio_for_vad(audio_path)
        timestamps = get_speech_timestamps(
            audio,
            self._get_vad_model(),
            threshold=self.preset.vad_threshold,
            sampling_rate=self.preset.sample_rate,
            min_speech_duration_ms=self.preset.min_speech_duration_ms,
            max_speech_duration_s=float("inf"),
            min_silence_duration_ms=self.preset.min_silence_duration_ms,
            speech_pad_ms=self.preset.speech_pad_ms,
        )
        if not timestamps:
            print("Parakeet VAD found no speech in the request.")
            return ""

        segmentation_timestamps = self._vad_inference_timestamps(timestamps)
        segment_ranges = self._segment_vad_timestamps(
            segmentation_timestamps,
            audio.shape[-1],
        )
        transcripts = []
        for start, end, overlaps_previous in segment_ranges:
            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as segment_file:
                segment_path = segment_file.name
            try:
                self._save_vad_segment(segment_path, audio[start:end])
                text = self._transcribe_one_file(segment_path)
                if text:
                    transcripts.append((text, overlaps_previous))
            finally:
                if os.path.exists(segment_path):
                    os.remove(segment_path)

        print(
            f"Parakeet VAD segmented audio: natural_segments={len(timestamps)} "
            f"inference_segments={len(segment_ranges)} "
            f"max_segment_seconds={self.preset.max_segment_seconds:g} "
            f"preserve_internal_silence={self.preset.preserve_internal_silence} "
            "natural_overlap_seconds=0 "
            f"forced_split_overlap_seconds={self.preset.forced_split_overlap_seconds:g}"
        )
        return self._merge_segment_transcripts(transcripts)

    def _vad_inference_timestamps(
        self,
        timestamps: list[dict[str, int]],
    ) -> list[dict[str, int]]:
        if not timestamps or not self.preset.preserve_internal_silence:
            return timestamps
        return [
            {
                "start": int(timestamps[0]["start"]),
                "end": int(timestamps[-1]["end"]),
            }
        ]

    def _segment_vad_timestamps(
        self,
        timestamps: list[dict[str, int]],
        audio_length_samples: int,
    ) -> list[tuple[int, int, bool]]:
        max_samples = max(1, round(self.preset.max_segment_seconds * self.preset.sample_rate))
        overlap_samples = min(
            max_samples - 1,
            max(
                0,
                round(
                    self.preset.forced_split_overlap_seconds
                    * self.preset.sample_rate
                ),
            ),
        )
        segments = []
        for timestamp in timestamps:
            natural_start = max(0, int(timestamp["start"]))
            natural_end = min(audio_length_samples, int(timestamp["end"]))
            if natural_end <= natural_start:
                continue

            start = natural_start
            overlaps_previous = False
            while start < natural_end:
                end = min(start + max_samples, natural_end)
                segments.append((start, end, overlaps_previous))
                if end >= natural_end:
                    break
                start = end - overlap_samples
                overlaps_previous = overlap_samples > 0
        return segments

    @classmethod
    def _merge_segment_transcripts(
        cls,
        transcripts: list[tuple[str, bool]],
    ) -> str:
        merged = ""
        for transcript, overlaps_previous in transcripts:
            next_text = transcript.strip()
            if not next_text:
                continue
            if not merged:
                merged = next_text
            elif overlaps_previous:
                merged = cls._merge_overlapping_transcripts([merged, next_text])
            else:
                merged = f"{merged} {next_text}"
        return merged

    @staticmethod
    def _merge_overlapping_transcripts(transcripts: list[str]) -> str:
        if not transcripts:
            return ""
        merged = transcripts[0].strip()
        for transcript in transcripts[1:]:
            next_text = transcript.strip()
            if not next_text:
                continue
            left_words = merged.split()
            right_words = next_text.split()
            overlap = 0
            for size in range(min(12, len(left_words), len(right_words)), 0, -1):
                left = [re.sub(r"\W+", "", word).casefold() for word in left_words[-size:]]
                right = [re.sub(r"\W+", "", word).casefold() for word in right_words[:size]]
                if left == right and all(left):
                    overlap = size
                    break
            merged = f"{merged} {' '.join(right_words[overlap:])}".strip()
        return merged


class CanaryQwenTranscriber:
    def __init__(self, model_name: str, device: str) -> None:
        try:
            import torch
            from nemo.collections.speechlm2.models import SALM
        except ImportError as exc:
            raise RuntimeError(
                "Canary-Qwen requires NeMo ASR. Install it with "
                "`uv sync --extra canary` or `uv pip install 'nemo-toolkit[asr]'`."
            ) from exc

        self.model_name = model_name
        self.device = device
        self.torch = torch
        print(
            f"Loading Canary-Qwen model '{self.model_name}' via NeMo ASR on requested device "
            f"'{self.device}'..."
        )
        print(
            f"torch.cuda.is_available()={torch.cuda.is_available()} "
            f"torch.cuda.device_count()={torch.cuda.device_count()}"
        )
        if self.device.startswith("cuda") and not torch.cuda.is_available():
            raise RuntimeError(
                "Canary-Qwen was requested on CUDA but torch.cuda.is_available() is false."
            )
        self.model = SALM.from_pretrained(model_name)
        self.model = self.model.to(self.device)
        first_param = next(self.model.parameters(), None)
        param_device = str(first_param.device) if first_param is not None else "unknown"
        print(f"Canary-Qwen first parameter device: {param_device}")
        print("Canary-Qwen model loaded successfully.")

    def transcribe_file(self, audio_path: str) -> str:
        answer_ids = self.model.generate(
            prompts=[
                [
                    {
                        "role": "user",
                        "content": f"Transcribe the following: {self.model.audio_locator_tag}",
                        "audio": [audio_path],
                    }
                ]
            ],
            max_new_tokens=256,
        )
        if answer_ids is None:
            return ""

        if hasattr(answer_ids, "numel"):
            if answer_ids.numel() == 0:
                return ""
            first_answer = answer_ids[0] if answer_ids.ndim > 1 else answer_ids
        else:
            if len(answer_ids) == 0:
                return ""
            first_answer = answer_ids[0]

        if hasattr(first_answer, "cpu"):
            first_answer = first_answer.cpu()
        if hasattr(first_answer, "tolist"):
            first_answer = first_answer.tolist()

        return self.model.tokenizer.ids_to_text(first_answer).strip()


@contextmanager
def prepare_granite_audio(audio_path: str):
    """Normalize Granite input to channel 0, mono 16 kHz PCM16 WAV."""
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as output_file:
        output_path = output_file.name
    command = [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-y",
        "-i",
        audio_path,
        "-af",
        "pan=mono|c0=c0",
        "-ar",
        "16000",
        "-ac",
        "1",
        "-c:a",
        "pcm_s16le",
        output_path,
    ]
    started = time.perf_counter()
    try:
        result = subprocess.run(command, capture_output=True, text=True)
        if result.returncode != 0:
            raise RuntimeError(f"Granite audio preprocessing failed: {result.stderr.strip()}")
        print(
            "Granite audio prepared: "
            "sample_rate=16000 channels=1 selected_channel=0 encoding=pcm_s16le "
            f"preprocess_ms={round((time.perf_counter() - started) * 1000)}"
        )
        yield output_path
    finally:
        Path(output_path).unlink(missing_ok=True)


class GraniteSpeechTranscriber:
    def __init__(self, config: RuntimeConfig) -> None:
        try:
            import httpx
        except ImportError as exc:
            raise RuntimeError(
                "Granite Speech Q8 requires HTTPX. Install it with "
                "`uv sync --extra granite-speech`."
            ) from exc

        self.config = config
        self.model_name = config.model_name
        self.prompt = self._build_prompt()
        self.base_url = (
            f"http://{config.granite_server_host}:{config.granite_server_port}"
        )
        self.client = httpx.Client(
            timeout=httpx.Timeout(config.granite_request_timeout_seconds, connect=10.0)
        )
        self.process: subprocess.Popen | None = None
        self._start_server()
        print(f"Granite Speech Q8 prompt: {self.prompt}")
        print("Granite Speech Q8 llama-server is ready.")

    def _build_prompt(self) -> str:
        prompt = self.config.granite_prompt.strip() or DEFAULT_GRANITE_PROMPT
        key_phrases_file = self.config.parakeet_key_phrases_file.strip()
        self.keywords = []
        if key_phrases_file:
            phrases_path = Path(key_phrases_file).expanduser()
            if not phrases_path.is_file():
                raise FileNotFoundError(f"Granite Speech keyword file not found: {phrases_path}")
            phrases = [
                line.strip()
                for line in phrases_path.read_text(encoding="utf-8").splitlines()
                if line.strip() and not line.lstrip().startswith("#")
            ]
            if phrases:
                self.keywords = phrases
                prompt = f"{prompt.rstrip()} Keywords: {', '.join(phrases)}"
        return prompt

    def _server_command(self, binary: str) -> list[str]:
        command = [
            binary,
            "-hf",
            self.model_name,
            "--host",
            self.config.granite_server_host,
            "--port",
            str(self.config.granite_server_port),
            "--parallel",
            "1",
            "--ctx-size",
            str(self.config.granite_context_size),
        ]
        if self.config.device.startswith("cuda"):
            command.extend(("--n-gpu-layers", "auto"))
        return command

    def _is_ready(self) -> bool:
        try:
            response = self.client.get(f"{self.base_url}/health")
            return response.status_code == 200
        except Exception:
            return False

    def _start_server(self) -> None:
        if self._is_ready():
            print(f"Using existing Granite Speech llama-server at {self.base_url}.")
            return

        binary = shutil.which(self.config.granite_server_binary)
        if binary is None:
            raise RuntimeError(
                f"Granite Speech Q8 requires '{self.config.granite_server_binary}'. "
                "Install the CachyOS package with `pkexec pacman -S llama-cpp`."
            )
        command = self._server_command(binary)
        print(
            f"Starting Granite Speech Q8 llama-server: model='{self.model_name}' "
            f"url='{self.base_url}' context_size={self.config.granite_context_size}"
        )
        self.process = subprocess.Popen(command)
        deadline = time.monotonic() + self.config.granite_server_startup_timeout_seconds
        while time.monotonic() < deadline:
            return_code = self.process.poll()
            if return_code is not None:
                self.process = None
                raise RuntimeError(
                    f"Granite Speech llama-server exited during startup with code {return_code}."
                )
            if self._is_ready():
                return
            time.sleep(0.25)
        self.close()
        raise TimeoutError(
            "Granite Speech llama-server did not become ready within "
            f"{self.config.granite_server_startup_timeout_seconds:g} seconds."
        )

    def transcribe_file(self, audio_path: str) -> str:
        temperature = f"{self.config.granite_temperature:g}"
        max_tokens = str(self.config.granite_max_new_tokens)
        print(
            "Granite transcription request: "
            f"prompt={self.prompt!r} max_tokens={max_tokens!r} temperature={temperature!r}"
        )
        with prepare_granite_audio(audio_path) as prepared_path:
            with open(prepared_path, "rb") as audio_file:
                response = self.client.post(
                    f"{self.base_url}/v1/audio/transcriptions",
                    data={
                        "model": self.model_name,
                        "prompt": self.prompt,
                        "max_tokens": max_tokens,
                        "temperature": temperature,
                    },
                    files={"file": ("granite-input.wav", audio_file, "audio/wav")},
                )
        if response.is_error:
            raise RuntimeError(
                f"Granite Speech llama-server returned HTTP {response.status_code}: "
                f"{response.text[:1000]}"
            )
        payload = response.json()
        text = payload.get("text")
        if not isinstance(text, str):
            raise RuntimeError(f"Granite Speech response did not contain text: {payload}")
        return text.strip()

    def close(self) -> None:
        process = getattr(self, "process", None)
        if process is not None:
            self.process = None
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
        client = getattr(self, "client", None)
        if client is not None:
            client.close()


class EnglishVariantPostProcessor:
    def __init__(self, source: str, target: str) -> None:
        from english_variant_converter import convert

        self.source = source
        self.target = target
        self._convert = convert
        print(
            f"English variant conversion enabled: source='{self.source}' -> target='{self.target}'"
        )

    def convert_text(self, text: str) -> str:
        if not text.strip() or self.source == self.target:
            return text
        protected_text, protected_terms = protect_technical_terms(text)
        converted = self._convert(protected_text, source=self.source, target=self.target)
        return restore_technical_terms(converted, protected_terms)


def default_model_for_backend(backend: str) -> str:
    if backend == "canary-qwen":
        return DEFAULT_CANARY_MODEL
    if backend == "parakeet":
        return DEFAULT_PARAKEET_MODEL
    if backend == "granite-speech":
        return DEFAULT_GRANITE_SPEECH_MODEL
    return DEFAULT_FASTER_WHISPER_MODEL


def parse_parakeet_channel_selector(value: str | None, default: int | str | None):
    if value is None or not value.strip():
        return default
    normalized = value.strip().lower()
    if normalized in {"none", "all"}:
        return None
    if normalized == "average":
        return "average"
    try:
        selector = int(normalized)
    except ValueError as exc:
        raise ValueError(
            "Parakeet channel selector must be '0', '1', 'average', or 'none'."
        ) from exc
    if selector < 0:
        raise ValueError("Parakeet channel selector must be non-negative.")
    return selector


def build_runtime_config(args: argparse.Namespace | None = None) -> RuntimeConfig:
    backend = (args.backend if args else None) or os.environ.get("WHISPER_BACKEND", "faster-whisper")
    model_name = (args.model if args else None) or os.environ.get(
        "WHISPER_MODEL", default_model_for_backend(backend)
    )
    default_preset = "programming" if backend == "parakeet" else ""
    parakeet_preset = (args.preset if args else None) or os.environ.get(
        "WHISPER_PRESET", default_preset
    )
    if backend == "parakeet" and parakeet_preset not in PARAKEET_PRESETS:
        valid_presets = ", ".join(PARAKEET_PRESETS)
        raise ValueError(f"Unsupported Parakeet preset '{parakeet_preset}'. Choose from: {valid_presets}")
    preset_defaults = PARAKEET_PRESETS.get(parakeet_preset)
    if args is not None and args.key_phrases_file is not None:
        key_phrases_file = args.key_phrases_file
    elif "WHISPER_KEY_PHRASES_FILE" in os.environ:
        key_phrases_file = os.environ["WHISPER_KEY_PHRASES_FILE"]
    elif parakeet_preset in {"accuracy", "accuracy-beam-experimental"}:
        key_phrases_file = str(DEFAULT_PROGRAMMING_PHRASES_FILE)
    elif backend == "granite-speech":
        key_phrases_file = str(DEFAULT_GRANITE_KEYWORDS_FILE)
    else:
        key_phrases_file = ""
    channel_selector_value = (args.channel_selector if args else None) or os.environ.get(
        "WHISPER_CHANNEL_SELECTOR"
    )
    parakeet_channel_selector = parse_parakeet_channel_selector(
        channel_selector_value,
        preset_defaults.channel_selector if preset_defaults else None,
    )
    granite_prompt = (args.granite_prompt if args else None) or os.environ.get(
        "WHISPER_GRANITE_PROMPT", DEFAULT_GRANITE_PROMPT
    )
    granite_max_new_tokens_value = (
        args.granite_max_new_tokens
        if args is not None and args.granite_max_new_tokens is not None
        else os.environ.get("WHISPER_GRANITE_MAX_NEW_TOKENS", "512")
    )
    granite_temperature_value = (
        args.granite_temperature
        if args is not None and args.granite_temperature is not None
        else os.environ.get("WHISPER_GRANITE_TEMPERATURE", "0")
    )
    granite_server_binary = (args.granite_server_binary if args else None) or os.environ.get(
        "WHISPER_GRANITE_SERVER_BINARY", "llama-server"
    )
    granite_server_host = (args.granite_server_host if args else None) or os.environ.get(
        "WHISPER_GRANITE_SERVER_HOST", "127.0.0.1"
    )
    granite_server_port_value = (
        args.granite_server_port
        if args is not None and args.granite_server_port is not None
        else os.environ.get("WHISPER_GRANITE_SERVER_PORT", "9797")
    )
    granite_server_startup_timeout_value = (
        args.granite_server_startup_timeout
        if args is not None and args.granite_server_startup_timeout is not None
        else os.environ.get("WHISPER_GRANITE_SERVER_STARTUP_TIMEOUT", "300")
    )
    granite_request_timeout_value = (
        args.granite_request_timeout
        if args is not None and args.granite_request_timeout is not None
        else os.environ.get("WHISPER_GRANITE_REQUEST_TIMEOUT", "900")
    )
    granite_context_size_value = (
        args.granite_context_size
        if args is not None and args.granite_context_size is not None
        else os.environ.get("WHISPER_GRANITE_CONTEXT_SIZE", "4096")
    )
    device = (args.device if args else None) or os.environ.get("WHISPER_DEVICE", "cuda")
    compute_type = (args.compute_type if args else None) or os.environ.get(
        "WHISPER_COMPUTE_TYPE", "float16"
    )
    language = (args.language if args else None) or os.environ.get("WHISPER_LANGUAGE", "en")
    beam_size_value = (args.beam_size if args else None) or os.environ.get("WHISPER_BEAM_SIZE", "4")
    vad_filter = (
        (args.vad_filter if args else None)
        if args is not None and args.vad_filter is not None
        else os.environ.get("WHISPER_VAD_FILTER", "true").strip().lower() in {"1", "true", "yes", "on"}
    )
    vad_threshold_value = (args.vad_threshold if args else None) or os.environ.get("WHISPER_VAD_THRESHOLD", "0.30")
    vad_min_silence_ms_value = (args.vad_min_silence_ms if args else None) or os.environ.get(
        "WHISPER_VAD_MIN_SILENCE_MS", "1000"
    )
    vad_speech_pad_ms_value = (args.vad_speech_pad_ms if args else None) or os.environ.get(
        "WHISPER_VAD_SPEECH_PAD_MS", "400"
    )
    log_prob_threshold_value = (args.log_prob_threshold if args else None) or os.environ.get(
        "WHISPER_LOG_PROB_THRESHOLD", "-1.0"
    )
    no_speech_threshold_value = (args.no_speech_threshold if args else None) or os.environ.get(
        "WHISPER_NO_SPEECH_THRESHOLD", "0.3"
    )
    compression_ratio_threshold_value = (
        (args.compression_ratio_threshold if args else None)
        or os.environ.get("WHISPER_COMPRESSION_RATIO_THRESHOLD", "2.4")
    )
    initial_prompt = (args.initial_prompt if args else None) or os.environ.get("WHISPER_INITIAL_PROMPT", "")
    batch_threshold_seconds_value = (
        args.batch_threshold_seconds
        if args is not None and args.batch_threshold_seconds is not None
        else os.environ.get("WHISPER_BATCH_THRESHOLD_SECONDS", "60")
    )
    long_audio_batch_size_value = (
        args.long_audio_batch_size
        if args is not None and args.long_audio_batch_size is not None
        else os.environ.get("WHISPER_LONG_AUDIO_BATCH_SIZE", "2")
    )
    cpu_fallback_enabled = (
        (args.cpu_fallback if args else None)
        if args is not None and args.cpu_fallback is not None
        else os.environ.get("WHISPER_CPU_FALLBACK", "true").strip().lower() in {"1", "true", "yes", "on"}
    )
    cpu_fallback_compute_type = (
        (args.cpu_fallback_compute_type if args else None)
        or os.environ.get("WHISPER_CPU_FALLBACK_COMPUTE_TYPE", "int8")
    )
    cpu_fallback_cpu_threads_value = (
        (args.cpu_fallback_cpu_threads if args else None)
        or os.environ.get("WHISPER_CPU_FALLBACK_CPU_THREADS")
        or str(os.cpu_count() or 4)
    )
    recovery_dir = (
        (args.recovery_dir if args else None)
        or os.environ.get("WHISPER_RECOVERY_DIR", ".transcription_recovery")
    )
    recovery_request_limit_value = (
        args.recovery_request_limit
        if args is not None and args.recovery_request_limit is not None
        else os.environ.get("WHISPER_RECOVERY_REQUEST_LIMIT", "5")
    )
    failed_recovery_limit_value = (
        args.failed_recovery_limit
        if args is not None and args.failed_recovery_limit is not None
        else os.environ.get("WHISPER_FAILED_RECOVERY_LIMIT", "20")
    )
    variant_conversion_enabled = (
        (args.variant_conversion if args else None)
        if args is not None and args.variant_conversion is not None
        else os.environ.get("WHISPER_VARIANT_CONVERSION", "false").strip().lower() in {"1", "true", "yes", "on"}
    )
    variant_source = (args.variant_source if args else None) or os.environ.get("WHISPER_VARIANT_SOURCE", "en_US")
    variant_target = (args.variant_target if args else None) or os.environ.get("WHISPER_VARIANT_TARGET", "en_GB")
    host = (args.host if args else None) or os.environ.get("WHISPER_HOST", "0.0.0.0")
    port_value = (args.port if args else None) or os.environ.get("WHISPER_PORT", "5001")
    http_access_log = (
        args.http_access_log
        if args is not None and args.http_access_log is not None
        else os.environ.get("WHISPER_HTTP_ACCESS_LOG", "false").strip().lower()
        in {"1", "true", "yes", "on"}
    )
    return RuntimeConfig(
        backend=backend,
        model_name=model_name,
        parakeet_preset=parakeet_preset,
        parakeet_key_phrases_file=key_phrases_file,
        parakeet_channel_selector=parakeet_channel_selector,
        granite_prompt=granite_prompt,
        granite_max_new_tokens=max(1, int(granite_max_new_tokens_value)),
        granite_temperature=float(granite_temperature_value),
        granite_server_binary=granite_server_binary,
        granite_server_host=granite_server_host,
        granite_server_port=int(granite_server_port_value),
        granite_server_startup_timeout_seconds=max(
            1.0, float(granite_server_startup_timeout_value)
        ),
        granite_request_timeout_seconds=max(1.0, float(granite_request_timeout_value)),
        granite_context_size=max(512, int(granite_context_size_value)),
        device=device,
        compute_type=compute_type,
        language=language,
        beam_size=int(beam_size_value),
        vad_filter=bool(vad_filter),
        vad_threshold=float(vad_threshold_value),
        vad_min_silence_ms=int(vad_min_silence_ms_value),
        vad_speech_pad_ms=int(vad_speech_pad_ms_value),
        log_prob_threshold=float(log_prob_threshold_value),
        no_speech_threshold=float(no_speech_threshold_value),
        compression_ratio_threshold=float(compression_ratio_threshold_value),
        initial_prompt=initial_prompt,
        batch_threshold_seconds=max(0.0, float(batch_threshold_seconds_value)),
        long_audio_batch_size=max(1, int(long_audio_batch_size_value)),
        cpu_fallback_enabled=bool(cpu_fallback_enabled),
        cpu_fallback_compute_type=cpu_fallback_compute_type,
        cpu_fallback_cpu_threads=max(1, int(cpu_fallback_cpu_threads_value)),
        recovery_dir=recovery_dir,
        recovery_request_limit=max(0, int(recovery_request_limit_value)),
        failed_recovery_limit=max(0, int(failed_recovery_limit_value)),
        variant_conversion_enabled=bool(variant_conversion_enabled),
        variant_source=variant_source,
        variant_target=variant_target,
        host=host,
        port=int(port_value),
        http_access_log=bool(http_access_log),
    )


def build_transcriber(config: RuntimeConfig):
    if config.backend == "faster-whisper":
        return FasterWhisperTranscriber(config)
    if config.backend == "canary-qwen":
        return CanaryQwenTranscriber(model_name=config.model_name, device=config.device)
    if config.backend == "parakeet":
        return ParakeetTranscriber(config)
    if config.backend == "granite-speech":
        return GraniteSpeechTranscriber(config)
    raise ValueError(f"Unsupported backend: {config.backend}")


@asynccontextmanager
async def app_lifespan(application: FastAPI):
    yield
    transcriber = getattr(application.state, "transcriber", None)
    if transcriber is not None and hasattr(transcriber, "close"):
        transcriber.close()


app = FastAPI(title="Local Whisper Server", lifespan=app_lifespan)
app.state.runtime_config = None
app.state.transcriber = None
app.state.variant_post_processor = None


def recovery_dir_path() -> Path:
    config = app.state.runtime_config or build_runtime_config()
    path = Path(config.recovery_dir).expanduser()
    if not path.is_absolute():
        path = Path(__file__).resolve().parent / path
    path.mkdir(parents=True, exist_ok=True)
    return path


def _move_recovery_pair(directory: Path, source_stem: str, target_stem: str) -> None:
    for suffix in (".wav", ".meta"):
        source = directory / f"{source_stem}{suffix}"
        target = directory / f"{target_stem}{suffix}"
        if source.exists():
            source.replace(target)
        else:
            target.unlink(missing_ok=True)


def _remove_excess_recent_recovery_files(directory: Path, limit: int) -> None:
    for path in directory.glob("recent_request_*.*"):
        match = re.fullmatch(r"recent_request_(\d+)\.(?:wav|meta)", path.name)
        if match and int(match.group(1)) >= limit:
            path.unlink(missing_ok=True)


def write_recovery_copy(audio_path: str | Path) -> Path | None:
    config = app.state.runtime_config or build_runtime_config()
    limit = config.recovery_request_limit
    if limit == 0:
        return None

    tmp_audio_path: Path | None = None
    tmp_meta_path: Path | None = None
    try:
        directory = recovery_dir_path()
        latest_path = directory / "latest_request.wav"
        tmp_audio_path = directory / "latest_request.wav.tmp"
        tmp_meta_path = directory / "latest_request.meta.tmp"
        shutil.copyfile(audio_path, tmp_audio_path)
        tmp_meta_path.write_text(
            f"received_at_unix={time.time()}\nbytes={Path(audio_path).stat().st_size}\n",
            encoding="utf-8",
        )

        _remove_excess_recent_recovery_files(directory, limit)
        for slot in range(limit - 1, 0, -1):
            source_stem = "latest_request" if slot == 1 else f"recent_request_{slot - 1}"
            _move_recovery_pair(directory, source_stem, f"recent_request_{slot}")

        tmp_audio_path.replace(latest_path)
        tmp_meta_path.replace(directory / "latest_request.meta")
        return latest_path
    except Exception as recovery_error:
        print(f"Failed to write latest transcription recovery audio: {recovery_error}")
        return None
    finally:
        if tmp_audio_path is not None:
            tmp_audio_path.unlink(missing_ok=True)
        if tmp_meta_path is not None:
            tmp_meta_path.unlink(missing_ok=True)


def _prune_failed_recovery_pairs(directory: Path, limit: int) -> None:
    stems = sorted(
        {
            path.stem
            for path in directory.glob("failed_request_*.*")
            if path.suffix in {".wav", ".txt"}
        },
        reverse=True,
    )
    for stem in stems[limit:]:
        for suffix in (".wav", ".txt"):
            (directory / f"{stem}{suffix}").unlink(missing_ok=True)


def preserve_failed_request(tmp_path: str, reason: Exception) -> None:
    config = app.state.runtime_config or build_runtime_config()
    limit = config.failed_recovery_limit
    if limit == 0:
        return

    try:
        directory = recovery_dir_path()
        timestamp = f"{time.strftime('%Y%m%d-%H%M%S')}-{time.time_ns() % 1_000_000_000:09d}"
        failed_path = directory / f"failed_request_{timestamp}.wav"
        shutil.copy2(tmp_path, failed_path)
        (directory / f"failed_request_{timestamp}.txt").write_text(
            f"{type(reason).__name__}: {reason}\n",
            encoding="utf-8",
        )
        _prune_failed_recovery_pairs(directory, limit)
        print(f"Preserved failed transcription audio for recovery: {failed_path}")
    except Exception as preserve_error:
        print(f"Failed to preserve failed transcription audio: {preserve_error}")


def configure_app(config: RuntimeConfig) -> None:
    current_config = getattr(app.state, "runtime_config", None)
    if current_config == config and getattr(app.state, "transcriber", None) is not None:
        return
    current_transcriber = getattr(app.state, "transcriber", None)
    if current_transcriber is not None and hasattr(current_transcriber, "close"):
        current_transcriber.close()
    app.state.runtime_config = config
    app.state.transcriber = build_transcriber(config)
    app.state.variant_post_processor = (
        EnglishVariantPostProcessor(config.variant_source, config.variant_target)
        if config.variant_conversion_enabled
        else None
    )


def ensure_transcriber_loaded() -> None:
    if getattr(app.state, "transcriber", None) is None:
        configure_app(build_runtime_config())


def format_timing_fields(outcome: str, timings: dict[str, int]) -> str:
    server_components = (
        "transcriber_ready_ms",
        "temp_file_open_ms",
        "temp_file_close_ms",
        "latest_recovery_copy_ms",
        "server_transcribe_ms",
        "postprocess_ms",
        "temp_file_cleanup_ms",
    )
    server_breakdown = format_timing_breakdown(
        timings,
        "server_total_ms",
        server_components,
        "server_overhead_ms",
    )
    outcome_line = f"outcome={outcome}"
    if timings["failed_recovery_copy_ms"] >= 0:
        outcome_line += f" failed_recovery_copy_ms={timings['failed_recovery_copy_ms']}"
    return " ".join(
        (
            outcome_line,
            f"server_total_ms={timings['server_total_ms']}{server_breakdown}",
        )
    )


def format_client_preparation(timings: dict[str, int]) -> str:
    client_components = (
        "client_stop_finalize_ms",
        "client_queue_wait_ms",
        "client_file_read_ms",
    )
    client_breakdown = format_timing_breakdown(
        timings,
        "client_stop_to_upload_ms",
        client_components,
        "client_pre_upload_overhead_ms",
        opening="[",
        closing="]",
    )
    return (
        f"client_recording_duration_ms={timings['client_recording_duration_ms']} "
        f"client_stop_to_upload_ms={timings['client_stop_to_upload_ms']}"
        f"{client_breakdown}"
    )


def format_audio_size(audio_bytes: int) -> str:
    return (
        f"audio_bytes={audio_bytes} "
        f"({audio_bytes / 1024:.2f} KiB, "
        f"{audio_bytes / (1024**2):.3f} MiB, "
        f"{audio_bytes / (1024**3):.6f} GiB)"
    )


def format_timing_breakdown(
    timings: dict[str, int],
    aggregate_key: str,
    component_keys: tuple[str, ...],
    overhead_key: str,
    opening: str = "(",
    closing: str = ")",
) -> str:
    values = [timings.get(key, -1) for key in component_keys]
    aggregate = timings[aggregate_key]
    if aggregate < 0 or any(value < 0 for value in values):
        return ""

    overhead = aggregate - sum(values)
    if overhead < 0:
        return ""

    components = [f"{key}={timings[key]}" for key in component_keys]
    if overhead:
        components.append(f"{overhead_key}={overhead}")
    return f" {opening}{' + '.join(components)}{closing}"


def client_timing_value(request: Request, header_name: str) -> int:
    try:
        value = int(request.headers.get(header_name, "-1"))
    except (TypeError, ValueError):
        return -1
    return value if value >= 0 else -1


@app.post("/transcribe_raw")
async def transcribe_raw(request: Request):
    request_started = time.perf_counter()
    tmp_path: str | None = None
    transcription_event: str | None = None
    outcome = "failed"
    timings = {
        "client_recording_duration_ms": client_timing_value(
            request, "x-client-recording-duration-ms"
        ),
        "client_stop_finalize_ms": client_timing_value(
            request, "x-client-stop-finalize-ms"
        ),
        "client_queue_wait_ms": client_timing_value(request, "x-client-queue-wait-ms"),
        "client_file_read_ms": client_timing_value(request, "x-client-file-read-ms"),
        "client_stop_to_upload_ms": client_timing_value(
            request, "x-client-stop-to-upload-ms"
        ),
        "transcriber_ready_ms": -1,
        "temp_file_open_ms": -1,
        "upload_body_read_ms": -1,
        "temp_file_close_ms": -1,
        "latest_recovery_copy_ms": -1,
        "server_transcribe_ms": -1,
        "postprocess_ms": -1,
        "temp_file_cleanup_ms": -1,
        "server_total_ms": -1,
        "audio_bytes": 0,
        "failed_recovery_copy_ms": -1,
    }
    try:
        transcriber_started = time.perf_counter()
        ensure_transcriber_loaded()
        timings["transcriber_ready_ms"] = round(
            (time.perf_counter() - transcriber_started) * 1000
        )

        temp_file_open_started = time.perf_counter()
        temp_file_close_started: float | None = None
        # Clients send WAV, FLAC, M4A, or MP3; let PyAV probe the actual bytes.
        with tempfile.NamedTemporaryFile(suffix=".audio", delete=False) as tmp:
            tmp_path = tmp.name
            timings["temp_file_open_ms"] = round(
                (time.perf_counter() - temp_file_open_started) * 1000
            )
            body_read_started = time.perf_counter()
            async for chunk in request.stream():
                if not chunk:
                    continue
                tmp.write(chunk)
                timings["audio_bytes"] += len(chunk)
            timings["upload_body_read_ms"] = round(
                (time.perf_counter() - body_read_started) * 1000
            )
            client_host = request.client.host if request.client is not None else "unknown"
            print(f"Recording prepared: {format_client_preparation(timings)}")
            print(
                "Audio received: "
                f"client={client_host} "
                f"client_recording_duration_ms={timings['client_recording_duration_ms']} "
                f"{format_audio_size(timings['audio_bytes'])} "
                f"upload_body_read_ms={timings['upload_body_read_ms']}"
            )
            temp_file_close_started = time.perf_counter()

        if temp_file_close_started is not None:
            timings["temp_file_close_ms"] = round(
                (time.perf_counter() - temp_file_close_started) * 1000
            )
        if timings["audio_bytes"] == 0:
            return JSONResponse(
                status_code=400,
                content={"error": "Empty request body.", "timings": timings},
            )

        recovery_started = time.perf_counter()
        write_recovery_copy(tmp_path)
        timings["latest_recovery_copy_ms"] = round(
            (time.perf_counter() - recovery_started) * 1000
        )

        transcribe_started = time.perf_counter()
        original_text = app.state.transcriber.transcribe_file(tmp_path)
        timings["server_transcribe_ms"] = round((time.perf_counter() - transcribe_started) * 1000)

        postprocess_started = time.perf_counter()
        canonicalized_text = canonicalize_technical_text(original_text)
        converted_text = canonicalized_text
        if app.state.variant_post_processor is not None:
            converted_text = app.state.variant_post_processor.convert_text(converted_text)
        timings["postprocess_ms"] = round((time.perf_counter() - postprocess_started) * 1000)
        transformations = []
        if canonicalized_text != original_text:
            transformations.append("technical canonicalization")
        if converted_text != canonicalized_text:
            transformations.append(
                f"{app.state.runtime_config.variant_source}->{app.state.runtime_config.variant_target}"
            )
        if transformations:
            transcription_event = (
                f"Transcribed Raw ({app.state.runtime_config.backend}, "
                f"{', '.join(transformations)}): "
                f"'{converted_text}'"
            )
        else:
            transcription_event = f"Transcribed Raw ({app.state.runtime_config.backend}): '{converted_text}'"
        outcome = "success"
        return {
            "text": converted_text,
            "original_text": original_text,
            "backend": app.state.runtime_config.backend,
            "model": app.state.runtime_config.model_name,
            "variant_conversion_enabled": app.state.runtime_config.variant_conversion_enabled,
            "variant_source": app.state.runtime_config.variant_source,
            "variant_target": app.state.runtime_config.variant_target,
            "timings": timings,
        }
    except Exception as exc:
        if tmp_path is not None:
            failed_recovery_started = time.perf_counter()
            preserve_failed_request(tmp_path, exc)
            timings["failed_recovery_copy_ms"] = round(
                (time.perf_counter() - failed_recovery_started) * 1000
            )
        service_logger.exception("Transcription request failed")
        return JSONResponse(
            status_code=500,
            content={
                "error": f"{type(exc).__name__}: {exc}",
                "backend": getattr(app.state.runtime_config, "backend", ""),
                "model": getattr(app.state.runtime_config, "model_name", ""),
                "timings": timings,
            },
        )
    finally:
        cleanup_started = time.perf_counter()
        if tmp_path is not None and os.path.exists(tmp_path):
            os.remove(tmp_path)
        timings["temp_file_cleanup_ms"] = round(
            (time.perf_counter() - cleanup_started) * 1000
        )
        timings["server_total_ms"] = round((time.perf_counter() - request_started) * 1000)
        timing_fields = format_timing_fields(outcome, timings)
        if transcription_event is not None:
            print(f"{transcription_event} | {timing_fields}")
        else:
            print(f"Transcription timing | {timing_fields}")


@app.post("/client-event", include_in_schema=False)
@app.post("/desktop-delivery-report")
async def desktop_delivery_report(request: Request):
    payload = await request.json()
    status = str(payload.get("status", "")).strip()
    if not status:
        legacy_event = str(payload.get("event", "")).strip()
        status = legacy_event.removeprefix("desktop-delivery-") or "unknown"
    details = str(payload.get("details", "")).strip()
    label = {
        "complete": "Delivery complete",
        "refused": "Delivery refused",
    }.get(status, f"Delivery report received [{status}]")
    if details:
        print(f"{label}: {details}")
    else:
        print(label)
    return {"ok": True}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run the local transcription service.")
    parser.add_argument(
        "--backend",
        choices=["faster-whisper", "canary-qwen", "parakeet", "granite-speech"],
        default=None,
        help="Transcription backend. Defaults to faster-whisper.",
    )
    parser.add_argument(
        "--model",
        default=None,
        help=(
            "Model name to load. Defaults to 'large-v3' for faster-whisper or "
            f"'{DEFAULT_CANARY_MODEL}' for canary-qwen or "
            f"'{DEFAULT_PARAKEET_MODEL}' for parakeet or "
            f"'{DEFAULT_GRANITE_SPEECH_MODEL}' for granite-speech."
        ),
    )
    parser.add_argument(
        "--preset",
        choices=list(PARAKEET_PRESETS),
        default=None,
        help="Parakeet preset. Defaults to programming when --backend parakeet is selected.",
    )
    parser.add_argument(
        "--key-phrases-file",
        default=None,
        help=(
            "One phrase per line for Parakeet phrase boosting or Granite Speech "
            "prompt-based keyword biasing."
        ),
    )
    parser.add_argument(
        "--no-key-phrases",
        dest="key_phrases_file",
        action="store_const",
        const="",
        help="Disable the default phrase file for an unboosted A/B test.",
    )
    parser.add_argument(
        "--channel-selector",
        default=None,
        help="Parakeet channel: 0, 1, average, or none. Presets default to channel 0.",
    )
    parser.add_argument(
        "--granite-prompt",
        default=None,
        help=(
            "Granite Speech transcription instruction. Defaults to punctuated and "
            "capitalized transcription."
        ),
    )
    parser.add_argument(
        "--granite-max-new-tokens",
        type=int,
        default=None,
        help="Maximum Granite Speech output tokens per recording. Defaults to 512.",
    )
    parser.add_argument(
        "--granite-temperature",
        type=float,
        default=None,
        help="Granite Speech generation temperature. Defaults to deterministic 0.",
    )
    parser.add_argument(
        "--granite-server-binary",
        default=None,
        help="llama-server executable for Granite Speech Q8. Defaults to llama-server.",
    )
    parser.add_argument(
        "--granite-server-host",
        default=None,
        help="Private Granite Speech llama-server host. Defaults to 127.0.0.1.",
    )
    parser.add_argument(
        "--granite-server-port",
        type=int,
        default=None,
        help="Private Granite Speech llama-server port. Defaults to 9797.",
    )
    parser.add_argument(
        "--granite-server-startup-timeout",
        type=float,
        default=None,
        help="Seconds to wait for the Q8 model to load. Defaults to 300.",
    )
    parser.add_argument(
        "--granite-request-timeout",
        type=float,
        default=None,
        help="Seconds allowed for one Granite Speech transcription. Defaults to 900.",
    )
    parser.add_argument(
        "--granite-context-size",
        type=int,
        default=None,
        help="Granite Speech llama.cpp context size. Defaults to its trained 4096 tokens.",
    )
    parser.add_argument(
        "--device",
        default=None,
        help="Inference device, for example 'cuda' or 'cpu'.",
    )
    parser.add_argument(
        "--compute-type",
        default=None,
        help="Compute type for faster-whisper, for example 'float16' or 'int8'.",
    )
    parser.add_argument("--language", default=None, help="Language code for faster-whisper. Defaults to en.")
    parser.add_argument("--beam-size", type=int, default=None, help="Beam size for faster-whisper decoding.")
    parser.add_argument(
        "--vad-filter",
        dest="vad_filter",
        action="store_true",
        default=None,
        help="Enable faster-whisper VAD filtering.",
    )
    parser.add_argument(
        "--no-vad-filter",
        dest="vad_filter",
        action="store_false",
        help="Disable faster-whisper VAD filtering.",
    )
    parser.add_argument("--vad-threshold", type=float, default=None, help="VAD threshold.")
    parser.add_argument(
        "--vad-min-silence-ms",
        type=int,
        default=None,
        help="Minimum silence duration in milliseconds for VAD segmentation.",
    )
    parser.add_argument(
        "--vad-speech-pad-ms",
        type=int,
        default=None,
        help="Speech padding in milliseconds to preserve edges after VAD.",
    )
    parser.add_argument(
        "--log-prob-threshold",
        type=float,
        default=None,
        help="Low-confidence rejection threshold for faster-whisper.",
    )
    parser.add_argument(
        "--no-speech-threshold",
        type=float,
        default=None,
        help="No-speech threshold for faster-whisper.",
    )
    parser.add_argument(
        "--compression-ratio-threshold",
        type=float,
        default=None,
        help="Compression ratio threshold to guard against degenerate transcripts.",
    )
    parser.add_argument(
        "--initial-prompt",
        default=None,
        help="Optional initial prompt for names, jargon, acronyms, and spelling hints.",
    )
    parser.add_argument(
        "--batch-threshold-seconds",
        type=float,
        default=None,
        help="Use batched GPU transcription at or above this audio duration. Defaults to 60 seconds.",
    )
    parser.add_argument(
        "--long-audio-batch-size",
        type=int,
        default=None,
        help="Batch size for long-audio GPU transcription. Defaults to 2; 1 disables batching.",
    )
    parser.add_argument(
        "--cpu-fallback",
        dest="cpu_fallback",
        action="store_true",
        default=None,
        help="Retry faster-whisper requests on CPU if CUDA transcription hits an out-of-memory error.",
    )
    parser.add_argument(
        "--no-cpu-fallback",
        dest="cpu_fallback",
        action="store_false",
        help="Disable CPU retry after CUDA out-of-memory errors.",
    )
    parser.add_argument(
        "--cpu-fallback-compute-type",
        default=None,
        help="Compute type for the lazy-loaded CPU fallback model. Defaults to int8.",
    )
    parser.add_argument(
        "--cpu-fallback-cpu-threads",
        type=int,
        default=None,
        help="CPU thread count for the lazy-loaded CPU fallback model. Defaults to os.cpu_count().",
    )
    parser.add_argument(
        "--recovery-dir",
        default=None,
        help="Directory for latest and failed request audio recovery files.",
    )
    parser.add_argument(
        "--recovery-request-limit",
        type=int,
        default=None,
        help="Number of recent request recordings to retain. Defaults to 5; 0 disables it.",
    )
    parser.add_argument(
        "--failed-recovery-limit",
        type=int,
        default=None,
        help="Number of failed request recordings to retain. Defaults to 20; 0 disables it.",
    )
    parser.add_argument(
        "--variant-conversion",
        dest="variant_conversion",
        action="store_true",
        default=None,
        help="Enable deterministic post-processing to convert transcript spelling variants.",
    )
    parser.add_argument(
        "--no-variant-conversion",
        dest="variant_conversion",
        action="store_false",
        help="Disable deterministic English variant post-processing.",
    )
    parser.add_argument(
        "--variant-source",
        default=None,
        help="Source English variant code for post-processing, e.g. en_US.",
    )
    parser.add_argument(
        "--variant-target",
        default=None,
        help="Target English variant code for post-processing, e.g. en_GB.",
    )
    parser.add_argument("--host", default=None, help="Bind host. Defaults to 0.0.0.0.")
    parser.add_argument("--port", type=int, default=None, help="Bind port. Defaults to 5001.")
    parser.add_argument(
        "--http-access-log",
        dest="http_access_log",
        action="store_true",
        default=None,
        help="Show one generic HTTP completion line per request. Disabled by default.",
    )
    parser.add_argument(
        "--no-http-access-log",
        dest="http_access_log",
        action="store_false",
        help="Hide generic HTTP completion lines.",
    )
    return parser.parse_args()


def build_log_config() -> dict:
    from uvicorn.config import LOGGING_CONFIG

    log_config = copy.deepcopy(LOGGING_CONFIG)
    log_config["formatters"]["default"]["fmt"] = (
        "%(asctime)s.%(msecs)03d %(source)s: %(message)s"
    )
    log_config["formatters"]["access"]["fmt"] = (
        '%(asctime)s.%(msecs)03d %(source)s: %(client_addr)s - '
        '"%(request_line)s" %(status_code)s'
    )
    log_config["formatters"]["default"]["datefmt"] = "%Y-%m-%d %H:%M:%S"
    log_config["formatters"]["access"]["datefmt"] = "%Y-%m-%d %H:%M:%S"
    log_config.setdefault("filters", {})["friendly_source"] = {"()": FriendlyLogSource}
    for handler_name in ("default", "access"):
        log_config["handlers"][handler_name]["filters"] = ["friendly_source"]
    log_config["loggers"]["assistant.whisper"] = {
        "handlers": ["default"],
        "level": "INFO",
        "propagate": False,
    }
    return log_config


if __name__ == "__main__":
    import uvicorn

    log_config = build_log_config()
    dictConfig(log_config)

    runtime_config = build_runtime_config(parse_args())
    configure_app(runtime_config)
    uvicorn.run(
        app,
        host=runtime_config.host,
        port=runtime_config.port,
        log_config=log_config,
        access_log=runtime_config.http_access_log,
    )
