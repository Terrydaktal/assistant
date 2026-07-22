import argparse
import copy
import ctypes
import glob
import logging
from logging.config import dictConfig
import os
import re
import shutil
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse


service_logger = logging.getLogger("uvicorn.error")

ANSI_RESET = "\033[0m"
ANSI_CYAN = "\033[36m"
ANSI_LIGHT_TEAL = "\033[1;96m"
ANSI_MAGENTA = "\033[1;35m"
ANSI_GREEN = "\033[1;32m"
ANSI_YELLOW = "\033[1;93m"
ANSI_BREAKDOWN_VALUE = "\033[93m"
ANSI_RED = "\033[1;31m"


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
        while part.startswith("("):
            leading += "("
            part = part[1:]
            in_breakdown = True

        trailing = ""
        while part.endswith(")"):
            trailing = ")" + trailing
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
        or "delivery-refused" in lowered
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
        elif output.startswith("Client Event [") and "]: " in output:
            label, timing = output.split("]: ", 1)
            color_message = (
                f"{ansi(label + ']:', ANSI_CYAN)} {colour_timing_fields(timing)}"
            )
        else:
            color_message = output

    service_logger.log(level, output, extra={"color_message": color_message})


print = service_log


def preload_local_cuda_libraries() -> None:
    venv_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".venv")
    nvidia_libs = glob.glob(
        os.path.join(venv_path, "lib", "python*", "site-packages", "nvidia", "*", "lib")
    )
    if not nvidia_libs:
        return

    existing_ld = os.environ.get("LD_LIBRARY_PATH", "")
    new_ld = ":".join(nvidia_libs)
    os.environ["LD_LIBRARY_PATH"] = f"{new_ld}:{existing_ld}" if existing_ld else new_ld

    for path in nvidia_libs:
        if not os.path.exists(path):
            continue
        for file_name in os.listdir(path):
            if file_name.startswith("libcublas.so") or file_name.startswith("libcudnn.so"):
                try:
                    ctypes.CDLL(os.path.join(path, file_name))
                except Exception:
                    pass


preload_local_cuda_libraries()


DEFAULT_FASTER_WHISPER_MODEL = "large-v3"
DEFAULT_CANARY_MODEL = "nvidia/canary-qwen-2.5b"


@dataclass(frozen=True)
class RuntimeConfig:
    backend: str
    model_name: str
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
    cpu_fallback_enabled: bool
    cpu_fallback_compute_type: str
    cpu_fallback_cpu_threads: int
    recovery_dir: str
    variant_conversion_enabled: bool
    variant_source: str
    variant_target: str
    host: str
    port: int


class FasterWhisperTranscriber:
    def __init__(self, config: RuntimeConfig) -> None:
        from faster_whisper import WhisperModel

        self.config = config
        self._whisper_model_cls = WhisperModel
        self.model = None
        self._cpu_fallback_model = None
        self._gpu_model_needs_reload = False
        self._load_primary_model()

    def transcribe_file(self, audio_path: str) -> str:
        primary_model = self._get_primary_model()
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

    def _transcribe_with_model(self, model, audio_path: str) -> str:
        segments, _info = model.transcribe(
            audio_path,
            word_timestamps=False,
            language=self.config.language,
            beam_size=self.config.beam_size,
            vad_filter=self.config.vad_filter,
            vad_parameters={
                "threshold": self.config.vad_threshold,
                "min_silence_duration_ms": self.config.vad_min_silence_ms,
                "speech_pad_ms": self.config.vad_speech_pad_ms,
            },
            log_prob_threshold=self.config.log_prob_threshold,
            no_speech_threshold=self.config.no_speech_threshold,
            compression_ratio_threshold=self.config.compression_ratio_threshold,
            initial_prompt=self.config.initial_prompt or None,
        )
        return "".join(segment.text for segment in segments).strip()

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
        self._gpu_model_needs_reload = False
        print("faster-whisper model loaded successfully.")

    def _get_primary_model(self):
        if self.model is None or self._gpu_model_needs_reload:
            print("Reloading faster-whisper primary model before GPU transcription attempt...")
            self._load_primary_model()
        return self.model

    def _invalidate_primary_model(self, reason: str) -> None:
        self.model = None
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
        return self._convert(text, source=self.source, target=self.target)


def default_model_for_backend(backend: str) -> str:
    if backend == "canary-qwen":
        return DEFAULT_CANARY_MODEL
    return DEFAULT_FASTER_WHISPER_MODEL


def build_runtime_config(args: argparse.Namespace | None = None) -> RuntimeConfig:
    backend = (args.backend if args else None) or os.environ.get("WHISPER_BACKEND", "faster-whisper")
    model_name = (args.model if args else None) or os.environ.get(
        "WHISPER_MODEL", default_model_for_backend(backend)
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
    variant_conversion_enabled = (
        (args.variant_conversion if args else None)
        if args is not None and args.variant_conversion is not None
        else os.environ.get("WHISPER_VARIANT_CONVERSION", "false").strip().lower() in {"1", "true", "yes", "on"}
    )
    variant_source = (args.variant_source if args else None) or os.environ.get("WHISPER_VARIANT_SOURCE", "en_US")
    variant_target = (args.variant_target if args else None) or os.environ.get("WHISPER_VARIANT_TARGET", "en_GB")
    host = (args.host if args else None) or os.environ.get("WHISPER_HOST", "0.0.0.0")
    port_value = (args.port if args else None) or os.environ.get("WHISPER_PORT", "5001")
    return RuntimeConfig(
        backend=backend,
        model_name=model_name,
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
        cpu_fallback_enabled=bool(cpu_fallback_enabled),
        cpu_fallback_compute_type=cpu_fallback_compute_type,
        cpu_fallback_cpu_threads=max(1, int(cpu_fallback_cpu_threads_value)),
        recovery_dir=recovery_dir,
        variant_conversion_enabled=bool(variant_conversion_enabled),
        variant_source=variant_source,
        variant_target=variant_target,
        host=host,
        port=int(port_value),
    )


def build_transcriber(config: RuntimeConfig):
    if config.backend == "faster-whisper":
        return FasterWhisperTranscriber(config)
    if config.backend == "canary-qwen":
        return CanaryQwenTranscriber(model_name=config.model_name, device=config.device)
    raise ValueError(f"Unsupported backend: {config.backend}")


app = FastAPI(title="Local Whisper Server")
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


def write_recovery_copy(audio_path: str | Path) -> Path | None:
    try:
        directory = recovery_dir_path()
        latest_path = directory / "latest_request.wav"
        tmp_path = directory / "latest_request.wav.tmp"
        shutil.copyfile(audio_path, tmp_path)
        tmp_path.replace(latest_path)
        (directory / "latest_request.meta").write_text(
            f"received_at_unix={time.time()}\nbytes={Path(audio_path).stat().st_size}\n",
            encoding="utf-8",
        )
        return latest_path
    except Exception as recovery_error:
        print(f"Failed to write latest transcription recovery audio: {recovery_error}")
        return None


def preserve_failed_request(tmp_path: str, reason: Exception) -> None:
    try:
        directory = recovery_dir_path()
        timestamp = time.strftime("%Y%m%d-%H%M%S")
        failed_path = directory / f"failed_request_{timestamp}.wav"
        shutil.copy2(tmp_path, failed_path)
        (directory / f"failed_request_{timestamp}.txt").write_text(
            f"{type(reason).__name__}: {reason}\n",
            encoding="utf-8",
        )
        print(f"Preserved failed transcription audio for recovery: {failed_path}")
    except Exception as preserve_error:
        print(f"Failed to preserve failed transcription audio: {preserve_error}")


def configure_app(config: RuntimeConfig) -> None:
    current_config = getattr(app.state, "runtime_config", None)
    if current_config == config and getattr(app.state, "transcriber", None) is not None:
        return
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
    )
    server_components = (
        "transcriber_ready_ms",
        "temp_file_open_ms",
        "upload_body_read_ms",
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
    hidden_keys = set(client_components if client_breakdown else ())
    if server_breakdown:
        hidden_keys.update(server_components)

    fields = [f"outcome={outcome}"]
    for key, value in timings.items():
        if key in hidden_keys:
            continue
        field = f"{key}={value}"
        if key == "client_stop_to_upload_ms":
            field += client_breakdown
        elif key == "server_total_ms":
            field += server_breakdown
        fields.append(field)
    return " ".join(fields)


def format_timing_breakdown(
    timings: dict[str, int],
    aggregate_key: str,
    component_keys: tuple[str, ...],
    overhead_key: str,
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
    return f" ({' + '.join(components)})"


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
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
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
        converted_text = original_text
        if app.state.variant_post_processor is not None:
            converted_text = app.state.variant_post_processor.convert_text(original_text)
        timings["postprocess_ms"] = round((time.perf_counter() - postprocess_started) * 1000)
        if converted_text != original_text:
            transcription_event = (
                f"Transcribed Raw ({app.state.runtime_config.backend}, converted "
                f"{app.state.runtime_config.variant_source}->{app.state.runtime_config.variant_target}): "
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
        if transcription_event is not None:
            print(f"{transcription_event} | {format_timing_fields(outcome, timings)}")
        else:
            print(f"Transcription timing {format_timing_fields(outcome, timings)}")


@app.post("/client-event")
async def client_event(request: Request):
    payload = await request.json()
    event = str(payload.get("event", "")).strip() or "unknown"
    details = str(payload.get("details", "")).strip()
    if details:
        print(f"Client Event [{event}]: {details}")
    else:
        print(f"Client Event [{event}]")
    return {"ok": True}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run the local transcription service.")
    parser.add_argument(
        "--backend",
        choices=["faster-whisper", "canary-qwen"],
        default=None,
        help="Transcription backend. Defaults to faster-whisper.",
    )
    parser.add_argument(
        "--model",
        default=None,
        help=(
            "Model name to load. Defaults to 'large-v3' for faster-whisper or "
            f"'{DEFAULT_CANARY_MODEL}' for canary-qwen."
        ),
    )
    parser.add_argument(
        "--device",
        default=None,
        help="Device for faster-whisper, for example 'cuda' or 'cpu'.",
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
    return parser.parse_args()


if __name__ == "__main__":
    import uvicorn
    from uvicorn.config import LOGGING_CONFIG

    log_config = copy.deepcopy(LOGGING_CONFIG)
    log_config["formatters"]["default"]["fmt"] = "%(asctime)s.%(msecs)03d %(levelprefix)s %(message)s"
    log_config["formatters"]["access"]["fmt"] = '%(asctime)s.%(msecs)03d %(levelprefix)s %(client_addr)s - "%(request_line)s" %(status_code)s'
    log_config["formatters"]["default"]["datefmt"] = "%Y-%m-%d %H:%M:%S"
    log_config["formatters"]["access"]["datefmt"] = "%Y-%m-%d %H:%M:%S"
    dictConfig(log_config)

    runtime_config = build_runtime_config(parse_args())
    configure_app(runtime_config)
    uvicorn.run(app, host=runtime_config.host, port=runtime_config.port, log_config=log_config)
