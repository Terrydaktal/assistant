import logging
import tempfile
import unittest
import wave
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

from whisper_service import (
    ANSI_BREAKDOWN_VALUE,
    FriendlyLogSource,
    FasterWhisperTranscriber,
    build_log_config,
    colour_timing_fields,
    format_audio_size,
    format_client_preparation,
    format_timing_fields,
)


class FasterWhisperBatchingTests(unittest.TestCase):
    def make_transcriber(self, duration_seconds: float | None = 61.0):
        transcriber = FasterWhisperTranscriber.__new__(FasterWhisperTranscriber)
        transcriber.config = SimpleNamespace(
            device="cuda",
            long_audio_batch_size=2,
            batch_threshold_seconds=60.0,
            language="en",
            beam_size=4,
            vad_filter=True,
            vad_threshold=0.3,
            vad_min_silence_ms=1000,
            vad_speech_pad_ms=400,
            log_prob_threshold=-1.0,
            no_speech_threshold=0.3,
            compression_ratio_threshold=2.4,
            initial_prompt="",
            cpu_fallback_enabled=True,
        )
        transcriber.model = object()
        transcriber._batched_pipeline = None
        transcriber._gpu_model_needs_reload = False
        transcriber._audio_duration_seconds = Mock(return_value=duration_seconds)
        transcriber._get_primary_model = Mock(return_value=transcriber.model)
        transcriber._release_cuda_cache = Mock()
        transcriber._invalidate_primary_model = Mock()
        transcriber._get_cpu_fallback_model = Mock(return_value="cpu-model")
        return transcriber

    def test_long_audio_uses_batched_gpu(self):
        transcriber = self.make_transcriber()
        transcriber._transcribe_batched = Mock(return_value="batched")
        transcriber._transcribe_with_model = Mock(return_value="ordinary")

        self.assertEqual(transcriber.transcribe_file("audio.wav"), "batched")
        transcriber._transcribe_batched.assert_called_once_with(transcriber.model, "audio.wav")
        transcriber._transcribe_with_model.assert_not_called()

    def test_audio_at_threshold_uses_batched_gpu(self):
        transcriber = self.make_transcriber(duration_seconds=60.0)
        transcriber._transcribe_batched = Mock(return_value="batched")
        transcriber._transcribe_with_model = Mock(return_value="ordinary")

        self.assertEqual(transcriber.transcribe_file("audio.wav"), "batched")
        transcriber._transcribe_batched.assert_called_once_with(transcriber.model, "audio.wav")
        transcriber._transcribe_with_model.assert_not_called()

    def test_audio_below_threshold_uses_ordinary_gpu(self):
        transcriber = self.make_transcriber(duration_seconds=59.99)
        transcriber._transcribe_batched = Mock(return_value="batched")
        transcriber._transcribe_with_model = Mock(return_value="ordinary")

        self.assertEqual(transcriber.transcribe_file("audio.wav"), "ordinary")
        transcriber._transcribe_batched.assert_not_called()
        transcriber._transcribe_with_model.assert_called_once_with(transcriber.model, "audio.wav")

    def test_batched_failure_retries_ordinary_gpu_without_unloading_model(self):
        transcriber = self.make_transcriber()
        transcriber._transcribe_batched = Mock(side_effect=RuntimeError("CUDA out of memory"))
        transcriber._transcribe_with_model = Mock(return_value="ordinary")

        self.assertEqual(transcriber.transcribe_file("audio.wav"), "ordinary")
        transcriber._release_cuda_cache.assert_called_once_with()
        transcriber._invalidate_primary_model.assert_not_called()
        transcriber._get_cpu_fallback_model.assert_not_called()

    def test_batched_and_ordinary_oom_then_retry_cpu(self):
        transcriber = self.make_transcriber()
        transcriber._transcribe_batched = Mock(side_effect=RuntimeError("CUDA out of memory"))
        transcriber._transcribe_with_model = Mock(
            side_effect=[RuntimeError("CUDA out of memory"), "cpu result"]
        )

        self.assertEqual(transcriber.transcribe_file("audio.wav"), "cpu result")
        transcriber._invalidate_primary_model.assert_called_once()
        transcriber._get_cpu_fallback_model.assert_called_once_with()
        self.assertEqual(
            transcriber._transcribe_with_model.call_args_list[1].args,
            ("cpu-model", "audio.wav"),
        )

    def test_duration_probe_reads_wav_container_metadata(self):
        transcriber = FasterWhisperTranscriber.__new__(FasterWhisperTranscriber)
        with tempfile.TemporaryDirectory() as directory:
            audio_path = Path(directory) / "sample.wav"
            with wave.open(str(audio_path), "wb") as output:
                output.setnchannels(1)
                output.setsampwidth(2)
                output.setframerate(16_000)
                output.writeframes(b"\0\0" * 24_000)

            self.assertAlmostEqual(
                transcriber._audio_duration_seconds(str(audio_path)),
                1.5,
                places=2,
            )


class TimingFormatTests(unittest.TestCase):
    def test_audio_size_is_shown_in_binary_units(self):
        self.assertEqual(
            format_audio_size(37_536),
            "audio_bytes=37536 (36.66 KiB, 0.036 MiB, 0.000035 GiB)",
        )

    def test_parenthesized_total_constituents_use_breakdown_colour(self):
        coloured = colour_timing_fields(
            "total_ms=694 (client_stop_to_upload_ms=92 + "
            "client_network_and_delivery_overhead_ms=6 + server_total_ms=531 + "
            "client_paste_ms=65)"
        )

        for value in ("92", "6", "531", "65"):
            self.assertIn(f"{ANSI_BREAKDOWN_VALUE}{value}", coloured)

    def test_transcription_timing_is_grouped_in_pipeline_order(self):
        timings = {
            "client_recording_duration_ms": 2789,
            "client_stop_finalize_ms": 62,
            "client_queue_wait_ms": 149,
            "client_file_read_ms": 0,
            "client_stop_to_upload_ms": 211,
            "transcriber_ready_ms": 0,
            "temp_file_open_ms": 0,
            "upload_body_read_ms": 0,
            "temp_file_close_ms": 0,
            "latest_recovery_copy_ms": 1,
            "server_transcribe_ms": 692,
            "postprocess_ms": 0,
            "temp_file_cleanup_ms": 0,
            "server_total_ms": 693,
            "audio_bytes": 88736,
            "failed_recovery_copy_ms": -1,
        }

        self.assertEqual(
            format_timing_fields("success", timings),
            "outcome=success server_total_ms=693 (transcriber_ready_ms=0 + "
            "temp_file_open_ms=0 + temp_file_close_ms=0 + latest_recovery_copy_ms=1 + "
            "server_transcribe_ms=692 + postprocess_ms=0 + temp_file_cleanup_ms=0)",
        )
        self.assertEqual(
            format_client_preparation(timings),
            "client_recording_duration_ms=2789 client_stop_to_upload_ms=211 "
            "[client_stop_finalize_ms=62 + client_queue_wait_ms=149 + "
            "client_file_read_ms=0]",
        )

    def test_failed_recovery_copy_only_appears_for_failed_event(self):
        timings = {
            "client_stop_finalize_ms": -1,
            "client_queue_wait_ms": -1,
            "client_file_read_ms": -1,
            "client_stop_to_upload_ms": -1,
            "transcriber_ready_ms": 0,
            "temp_file_open_ms": 0,
            "temp_file_close_ms": 0,
            "latest_recovery_copy_ms": 1,
            "server_transcribe_ms": -1,
            "postprocess_ms": -1,
            "temp_file_cleanup_ms": 0,
            "server_total_ms": 30,
            "failed_recovery_copy_ms": 4,
        }

        fields = format_timing_fields("failed", timings)

        self.assertTrue(fields.startswith("outcome=failed failed_recovery_copy_ms=4 "))


class FriendlyLogSourceTests(unittest.TestCase):
    def test_log_config_registers_source_filter_on_both_handlers(self):
        config = build_log_config()

        self.assertIn("friendly_source", config["filters"])
        self.assertEqual(config["handlers"]["default"]["filters"], ["friendly_source"])
        self.assertEqual(config["handlers"]["access"]["filters"], ["friendly_source"])

    def test_server_records_are_labelled_server(self):
        source_filter = FriendlyLogSource()
        logger_names = ("assistant.whisper", "uvicorn.access", "uvicorn.error")

        for logger_name in logger_names:
            with self.subTest(logger_name=logger_name):
                record = logging.LogRecord(
                    logger_name,
                    logging.INFO,
                    __file__,
                    1,
                    "message",
                    (),
                    None,
                )
                self.assertTrue(source_filter.filter(record))
                self.assertEqual(record.source, "Server")

    def test_delivery_report_is_labelled_client(self):
        record = logging.LogRecord(
            "assistant.whisper",
            logging.INFO,
            __file__,
            1,
            "Delivery complete: total_ms=653",
            (),
            None,
        )

        self.assertTrue(FriendlyLogSource().filter(record))
        self.assertEqual(record.source, "Client")

    def test_recording_preparation_is_labelled_client(self):
        record = logging.LogRecord(
            "assistant.whisper",
            logging.INFO,
            __file__,
            1,
            "Recording prepared: client_stop_to_upload_ms=92",
            (),
            None,
        )

        self.assertTrue(FriendlyLogSource().filter(record))
        self.assertEqual(record.source, "Client")


if __name__ == "__main__":
    unittest.main()
