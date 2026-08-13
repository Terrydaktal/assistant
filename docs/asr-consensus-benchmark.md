# ASR Consensus Benchmark

## Result

The `consensus` backend is the first tested local configuration that improves both word error rate and punctuation over this project's current faster-whisper large-v3 configuration while remaining below 5 GiB peak VRAM on the RTX 5060.

| Pipeline | Word errors | WER | Punctuation micro-F1 | Sentence-boundary F1 | Peak VRAM |
| --- | ---: | ---: | ---: | ---: | ---: |
| Current faster-whisper large-v3 | 2,138 | 5.1518% | 0.7872 | 0.8191 | 4,380 MiB |
| Whisper+Cohere+Parakeet consensus | 1,866 | 4.4964% | 0.8254 | 0.8662 | 4,380 MiB |

Across 41,500 reference words, consensus avoided 272 errors, a 12.72% relative error reduction. It also improved both punctuation measures on every recording, including the held-out recording.

## Corpus Results

The references are publisher-provided transcripts rather than manually adjudicated ground truth. The measurements are therefore reference-relative and should be used for production selection, not as publication-quality model WER claims.

| Recording | Duration | Reference words | Whisper WER | Consensus WER | Whisper punctuation F1 | Consensus punctuation F1 | Whisper sentence F1 | Consensus sentence F1 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Talk Python #332, Robust Python excerpt | 10:02 | 2,060 | 3.0097% | 2.9612% | 0.8387 | 0.8688 | 0.8529 | 0.9036 |
| Talk Python #344, SQLAlchemy 2.0 | 66:19 | 13,739 | 5.3788% | 5.3279% | 0.8109 | 0.8115 | 0.8374 | 0.8463 |
| Talk Python #400, Ruff | 63:45 | 13,316 | 6.0754% | 4.8588% | 0.7779 | 0.8200 | 0.8178 | 0.8706 |
| Talk Python #487, Rust Extensions (held out) | 62:24 | 12,385 | 4.2632% | 3.4396% | 0.7585 | 0.8423 | 0.7853 | 0.8834 |

Source pages:

- <https://talkpython.fm/episodes/show/332/robust-python>
- <https://talkpython.fm/episodes/show/344/sqlalchemy-2.0>
- <https://talkpython.fm/episodes/transcript/400/ruff-the-fast-rust-based-python-linter>
- <https://talkpython.fm/episodes/show/487/building-rust-extensions-for-python>

## Fixed Configuration

The voters run sequentially in isolated processes. Their memory is not additive.

### Whisper Anchor

- Model: `large-v3`
- Device and compute type: CUDA FP16
- Decoder: beam size 4
- Long recordings: faster-whisper batched pipeline, batch size 2 at 60 seconds or longer
- Silero VAD: threshold 0.30, minimum silence 1,000 ms, speech padding 400 ms
- Decoder thresholds: log probability -1.0, no-speech 0.3, compression ratio 2.4
- Prompt and variant conversion: disabled inside the voter

### Cohere Voter

- Model: `CohereLabs/cohere-transcribe-03-2026`
- Runtime: Transformers 5.14.1 and PyTorch 2.11 CUDA 12.8
- Precision: BF16
- Audio: 16 kHz mono; Silero used only to trim the outer non-speech edges
- Long form: official processor boundaries, microbatch size 1, ordered `audio_chunk_index` reassembly
- Generation: punctuation enabled, greedy decoding, `max_new_tokens=256`

### Parakeet Voter

- Model: `nvidia/parakeet-tdt-0.6b-v2`
- Preset: `accuracy`, BF16, `greedy_batch`, label looping and CUDA graphs enabled
- VAD: threshold 0.30, minimum speech 150 ms, minimum silence 700 ms, padding 400 ms
- Segmentation: preserve internal silence, 120-second maximum, no forced overlap
- Phrase boosting and technical post-processing: disabled
- Word timestamps retained; word-confidence collection disabled due to the current NeMo metadata failure

### ROVER Merge

`transcription_consensus.py` aligns Cohere and Parakeet to the Whisper word stream. It chooses words, insertions, deletions, and punctuation by strict three-voter majority. Whisper supplies surface spelling and punctuation only when a vote is tied. No benchmark reference, language model, glossary, or model-specific confidence is used during merging.

## VRAM Measurement

VRAM was sampled every 50 ms from `nvidia-smi --query-compute-apps=pid,used_memory` for the parent process and every descendant. The maximum observed values were:

| Voter | Driver-level peak |
| --- | ---: |
| faster-whisper | 4,380 MiB |
| Cohere | 4,348 MiB |
| Parakeet | 2,082 MiB |
| Sequential consensus pipeline | 4,380 MiB |

The measured pipeline peak is 740 MiB below the 5 GiB limit of 5,120 MiB. A production-path run on the ten-minute recording reproduced the exact validated voter transcripts and the exact consensus output.

## Rejected Approaches

- Whisper beam 2, 3, 5, and 8; VAD/no-speech sweeps; timestamp-token batched decoding; patience 1.2; length penalties 1.05 and 1.10; hotword prompts; and a style prompt did not improve both target metrics. Batch size 4 reduced aggregate runtime by about 24%, but made three additional errors across 41,500 words, so batch size 2 remains the accuracy default.
- A separate FullStop punctuation model reduced punctuation quality.
- Cohere, Parakeet, Granite Speech, Qwen3-ASR, and CrisperWhisper did not individually beat the Whisper baseline on both metrics over the tested long-form workload.
- Higgs Audio v3 STT Q8 peaked at 5,716 MiB and produced a repetition failure, so it violated both the memory and quality requirements.

## Adaptive Adjudication Control

The `adaptive-consensus` backend runs full Whisper and Parakeet passes, locates their lexical and punctuation disagreements from Whisper segment timings, and asks one loaded Cohere worker to transcribe only the merged disagreement regions. It keeps Whisper outside those regions and applies the same three-way vote inside them.

| Recording | Whisper WER | Adaptive WER | Whisper punctuation F1 | Adaptive punctuation F1 | Whisper time | Adaptive time |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Robust Python excerpt | 3.0097% | 2.9126% | 0.8387 | 0.8541 | 17.22 s | 45.86 s |
| SQLAlchemy 2.0 | 5.3788% | 5.3352% | 0.8109 | 0.8152 | 114.00 s | 189.06 s |

This validates the selective merge and gives a smaller latency penalty than full three-model processing on the short sample, but the long-file gain was only six avoided errors. It remains an explicit offline accuracy option, not the live default.

## Running It

```bash
cd ~/Dev/assistant
UV_CACHE_DIR=/data/.cache/uv uv sync --extra consensus
UV_CACHE_DIR=/data/.cache/uv uv sync --project consensus_cohere
uv run python whisper_service.py --backend consensus --device cuda
```

The Cohere model is gated. The Hugging Face account used by the service must have accepted the model terms and be authenticated locally.
