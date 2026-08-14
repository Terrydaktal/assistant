import tempfile
import unittest
import wave
from pathlib import Path
from unittest.mock import patch

import numpy as np

from audio_preprocessing import analyze_audio, prepare_audio_for_transcription


def write_wav(path: Path, samples: np.ndarray) -> None:
    pcm = np.clip(samples, -1.0, 1.0)
    pcm = np.round(pcm * 32767.0).astype("<i2")
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(16_000)
        output.writeframes(pcm.tobytes())


class AudioPreprocessingTests(unittest.TestCase):
    def make_recordings(self, directory: str) -> tuple[Path, Path]:
        sample_count = 16_000 * 2
        time_axis = np.arange(sample_count, dtype=np.float64) / 16_000
        speech_window = (time_axis >= 0.5) & (time_axis < 1.5)
        clean = np.where(
            speech_window,
            0.18 * np.sin(2 * np.pi * 220 * time_axis),
            0.0,
        )
        contaminated = np.where(
            speech_window,
            0.12 * np.sin(2 * np.pi * 220 * time_axis)
            + 0.10 * np.sin(2 * np.pi * 50 * time_axis)
            + 0.02,
            0.0,
        )
        clean_path = Path(directory) / "clean.wav"
        contaminated_path = Path(directory) / "contaminated.wav"
        write_wav(clean_path, clean)
        write_wav(contaminated_path, contaminated)
        return clean_path, contaminated_path

    def test_clean_recording_is_not_filtered(self):
        with tempfile.TemporaryDirectory() as directory:
            clean_path, _ = self.make_recordings(directory)
            analysis = analyze_audio(clean_path)

            self.assertEqual(analysis.sample_count, 32_000)
            self.assertFalse(
                analysis.should_filter(
                    ratio_threshold=0.25,
                    absolute_dbfs_threshold=-35.0,
                    dc_offset_threshold=0.005,
                )[0]
            )
            with prepare_audio_for_transcription(clean_path) as result:
                self.assertFalse(result.filter_applied)
                self.assertEqual(result.transcription_path, str(clean_path))
                self.assertIsNone(result.error)

    def test_contaminated_recording_is_filtered_and_temporary_copy_is_removed(self):
        with tempfile.TemporaryDirectory() as directory:
            _, contaminated_path = self.make_recordings(directory)
            with prepare_audio_for_transcription(contaminated_path) as result:
                self.assertTrue(result.filter_applied)
                self.assertEqual(result.filter_reason, "low-frequency-energy-and-dc-offset")
                self.assertNotEqual(result.transcription_path, str(contaminated_path))
                filtered_path = Path(result.transcription_path)
                self.assertTrue(filtered_path.is_file())
                self.assertIsNotNone(result.filtered_analysis)
                self.assertLess(result.filtered_analysis.low_frequency_ratio, 0.25)
            self.assertFalse(filtered_path.exists())
            self.assertTrue(contaminated_path.is_file())

    def test_filter_failure_fails_open_to_original_audio(self):
        with tempfile.TemporaryDirectory() as directory:
            _, contaminated_path = self.make_recordings(directory)
            with patch(
                "audio_preprocessing.subprocess.run",
                side_effect=RuntimeError("ffmpeg unavailable"),
            ), prepare_audio_for_transcription(contaminated_path) as result:
                self.assertFalse(result.filter_applied)
                self.assertEqual(result.transcription_path, str(contaminated_path))
                self.assertIn("ffmpeg unavailable", result.error)


if __name__ == "__main__":
    unittest.main()
