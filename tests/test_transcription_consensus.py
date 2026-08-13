import os
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, call, patch

from transcription_consensus import (
    AdjudicationRegion,
    find_disagreement_regions,
    merge_adaptive_transcripts,
    merge_transcripts,
)
from whisper_service import (
    AdaptiveConsensusTranscriber,
    ConsensusTranscriber,
    default_model_for_backend,
)


class RoverConsensusTests(unittest.TestCase):
    def test_majority_selects_words_and_punctuation(self):
        result = merge_transcripts(
            [
                "The cat sat there.",
                "The cat sits there?",
                "The cat sat there?",
            ]
        )

        self.assertEqual(result, "The cat sat there?")

    def test_majority_keeps_shared_insertion(self):
        result = merge_transcripts(
            [
                "Alpha gamma.",
                "Alpha beta gamma.",
                "Alpha beta gamma.",
            ]
        )

        self.assertEqual(result, "Alpha beta gamma.")

    def test_majority_removes_anchor_only_word(self):
        result = merge_transcripts(
            [
                "Alpha unwanted gamma.",
                "Alpha gamma.",
                "Alpha gamma.",
            ]
        )

        self.assertEqual(result, "Alpha gamma.")

    def test_requires_odd_number_of_voters(self):
        with self.assertRaisesRegex(ValueError, "odd number"):
            merge_transcripts(["one", "two"])

    def test_disagreement_regions_include_words_and_punctuation(self):
        regions = find_disagreement_regions(
            "One two three four.",
            "One too three four?",
            [{"start": 0.0, "end": 4.0, "text": "One two three four."}],
            context_seconds=0,
        )

        self.assertEqual(
            regions,
            [
                AdjudicationRegion(1.0, 2.0, 1, 2),
                AdjudicationRegion(3.0, 4.0, 3, 4),
            ],
        )

    def test_disagreement_context_merges_nearby_regions(self):
        regions = find_disagreement_regions(
            "One two three four.",
            "One too three four?",
            [{"start": 0.0, "end": 4.0, "text": "One two three four."}],
            context_seconds=0.6,
        )

        self.assertEqual(
            regions,
            [AdjudicationRegion(0.4, 4.6, 0, 4)],
        )

    def test_disagreement_regions_merge_overlapping_anchor_ranges(self):
        regions = find_disagreement_regions(
            "One two three four.",
            "Won two tree four.",
            [{"start": 0.0, "end": 80.0, "text": "One two three four."}],
            context_seconds=9.0,
        )

        self.assertEqual(
            regions,
            [AdjudicationRegion(0.0, 69.0, 0, 4)],
        )

    def test_adaptive_merge_uses_third_vote_only_inside_region(self):
        result = merge_adaptive_transcripts(
            "The cat sat there.",
            "The cat sits there?",
            [AdjudicationRegion(1.0, 4.0, 1, 4)],
            ["cat sat there?"],
        )

        self.assertEqual(result, "The cat sat there?")


class ConsensusBackendTests(unittest.TestCase):
    def make_transcriber(self):
        transcriber = ConsensusTranscriber.__new__(ConsensusTranscriber)
        transcriber.config = SimpleNamespace(
            cpu_fallback_enabled=True,
            cpu_fallback_compute_type="int8",
            cpu_fallback_cpu_threads=16,
            hotwords="",
            long_audio_batch_size=2,
        )
        transcriber.worker_path = "consensus_worker.py"
        transcriber.cohere_python = "cohere-python"
        transcriber.cohere_worker_path = "cohere-worker.py"
        transcriber.merge_transcripts = Mock(return_value="merged")
        return transcriber

    def test_consensus_has_descriptive_default_model(self):
        self.assertEqual(
            default_model_for_backend("consensus"),
            "large-v3+cohere-transcribe-03-2026+parakeet-tdt-0.6b-v2",
        )

    def test_adaptive_consensus_has_descriptive_default_model(self):
        self.assertEqual(
            default_model_for_backend("adaptive-consensus"),
            "large-v3+cohere-transcribe-03-2026+parakeet-tdt-0.6b-v2-adaptive",
        )

    def test_voters_run_in_validated_order(self):
        transcriber = self.make_transcriber()
        transcriber._run_project_worker = Mock(side_effect=["whisper", "parakeet"])
        transcriber._run_cohere_worker = Mock(return_value="cohere")

        self.assertEqual(transcriber.transcribe_file("audio.wav"), "merged")
        self.assertEqual(
            transcriber._run_project_worker.call_args_list,
            [
                call("faster-whisper", "audio.wav"),
                call("parakeet", "audio.wav"),
            ],
        )
        transcriber._run_cohere_worker.assert_called_once_with("audio.wav")
        transcriber.merge_transcripts.assert_called_once_with(
            ["whisper", "cohere", "parakeet"]
        )

    def test_whisper_worker_uses_validated_parameters(self):
        transcriber = self.make_transcriber()
        transcriber._run_worker_payload = Mock(return_value={"text": "text"})

        with patch.dict(os.environ, {}, clear=True):
            transcriber._run_project_worker("faster-whisper", "audio.wav")

        environment = transcriber._run_worker_payload.call_args.args[2]
        self.assertEqual(environment["WHISPER_BEAM_SIZE"], "4")
        self.assertEqual(environment["WHISPER_NO_SPEECH_THRESHOLD"], "0.3")
        self.assertEqual(environment["WHISPER_LONG_AUDIO_BATCH_SIZE"], "2")
        self.assertEqual(environment["WHISPER_VAD_THRESHOLD"], "0.30")

    def test_parakeet_worker_uses_unboosted_accuracy_preset(self):
        transcriber = self.make_transcriber()
        transcriber._run_worker_payload = Mock(return_value={"text": "text"})

        with patch.dict(os.environ, {}, clear=True):
            transcriber._run_project_worker("parakeet", "audio.wav")

        environment = transcriber._run_worker_payload.call_args.args[2]
        self.assertEqual(environment["WHISPER_PRESET"], "accuracy")
        self.assertEqual(environment["WHISPER_KEY_PHRASES_FILE"], "")


class AdaptiveConsensusBackendTests(unittest.TestCase):
    def make_transcriber(self):
        transcriber = AdaptiveConsensusTranscriber.__new__(
            AdaptiveConsensusTranscriber
        )
        transcriber._run_project_worker_payload = Mock(
            return_value={
                "text": "The cat sat there.",
                "segments": [
                    {
                        "start": 0.0,
                        "end": 4.0,
                        "text": "The cat sat there.",
                    }
                ],
            }
        )
        transcriber._run_project_worker = Mock(return_value="The cat sits there?")
        transcriber._run_cohere_regions_worker = Mock(return_value=["sat there?"])
        transcriber.find_disagreement_regions = find_disagreement_regions
        transcriber.merge_adaptive_transcripts = merge_adaptive_transcripts
        transcriber.context_seconds = 0.0
        return transcriber

    def test_adaptive_backend_adjudicates_only_disagreement_regions(self):
        transcriber = self.make_transcriber()

        self.assertEqual(transcriber.transcribe_file("audio.wav"), "The cat sat there?")
        regions = transcriber._run_cohere_regions_worker.call_args.args[1]
        self.assertEqual(
            regions,
            [AdjudicationRegion(2.0, 4.0, 2, 4)],
        )

    def test_adaptive_backend_skips_cohere_when_first_voters_agree(self):
        transcriber = self.make_transcriber()
        transcriber._run_project_worker.return_value = "The cat sat there."

        self.assertEqual(transcriber.transcribe_file("audio.wav"), "The cat sat there.")
        transcriber._run_cohere_regions_worker.assert_not_called()


if __name__ == "__main__":
    unittest.main()
