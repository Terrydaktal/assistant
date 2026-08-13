# Assistant: Standalone Earpiece Voice-Control Bundle

This workspace contains a completely standalone application bundle that routes voice inputs from a dedicated Android app client, processes them locally on your PC via GPU-accelerated Whisper transcription, and communicates programmatically with your active Google AI Mode Chromium session.

Responses are returned to your phone and spoken back into your earpiece using your phone's native, high-quality Text-to-Speech (TTS) engine.

Desktop dictation is included locally and shares the persistent Whisper service rather than loading another model.

---

## Directory Tree

```text
~/Dev/assistant/
├── package.json          # Node dependency configuration
├── package-lock.json     # Node lockfile
├── pyproject.toml        # Python virtual environment configuration (uv)
├── uv.lock               # Python lockfile
├── whisper_service.py    # Configurable transcription server (FastAPI)
├── consensus_worker.py   # Isolated Whisper/Parakeet consensus voter
├── transcription_consensus.py # Deterministic ROVER word/punctuation voting
├── consensus_cohere/     # Isolated Transformers 5 Cohere worker project
├── docs/                 # Reproducible benchmark results and methodology
├── voice_bridge.js       # Voice Router / CDP browser automator (Node.js)
├── desktop_dictation/    # Queued desktop recording, transcription, and safe typing
├── README.md             # This guide
└── android/              # Standalone Android application client
    ├── app/
    ├── swiftsay-local/    # SwiftSay-style client using the computer Whisper service
    ├── gradle/
    └── ...
```

---

## Script Breakdown

### 1. `whisper_service.py` (Local Whisper Server)
Loads a selectable transcription backend into memory.
- **Input:** POST request with binary audio payload at `/transcribe_raw`.
- **Output:** JSON containing `{"text": "...", "backend": "...", "model": "..."}` plus variant-conversion metadata when enabled.
- **Backends:** `faster-whisper` by default, `nvidia/canary-qwen-2.5b` with the Canary backend, `nvidia/parakeet-tdt-0.6b-v2` with the Parakeet backend, IBM's `granite-speech-4.1-2b-GGUF:Q8_0` with the Granite Speech backend, or the sequential `consensus` and disagreement-only `adaptive-consensus` modes using Whisper, Cohere, and Parakeet.
- **Optional post-processing:** deterministic English variant conversion (for example `en_US -> en_GB`) using the Trelis English Variant Converter library.
- **Diagnostic retention:** keeps the five most recent request recordings as `latest_request.wav` plus four numbered history slots and keeps up to 20 timestamped failed request/error pairs in `.transcription_recovery`. Set `--recovery-request-limit` or `WHISPER_RECOVERY_REQUEST_LIMIT`, and `--failed-recovery-limit` or `WHISPER_FAILED_RECOVERY_LIMIT`, to change the limits; `0` disables that category.

### 2. `voice_bridge.js` (Web Server & Puppeteer Automator)
Connects to your active Chromium browser (remote-debugging on port `9233`) and acts as the broker between the phone and Google AI Mode.
- **Voice Routing:**
  - Receives raw audio from the Android client.
  - POSTs it to the local Whisper service (`whisper_service.py`).
  - Evaluates the transcription text:
    - If `"new chat"` / `"open a new chat"` $\rightarrow$ Navigates Chrome to a fresh AI Mode window.
    - If `"open chat [Name]"` / `"navigate to chat [Name]"` $\rightarrow$ Scrapes the list of chats and switches to the matching one.
    - Otherwise $\rightarrow$ Calls `BrowserAiInterface.ask(prompt)` on the active page.
  - Returns `{"transcription": "...", "response": "..."}`.

### 3. `android/` (Standalone Android App Client)
A Kotlin-based Android app compiled and installed on your phone.
- **Recording:** Captures 16 kHz mono audio and writes WAV for private/local server addresses or fast level-0 FLAC for remote addresses.
- **Voice Bridge Request:** POSTs the selected audio payload to `http://<your-pc-ip>:9090/voice-command`.
- **Imported audio tails:** Copies compressed M4A/AAC packets or MP3 frames without decoding when possible, then falls back to PCM decode and level-0 FLAC when direct extraction is unsupported.
- **TTS rendering:** Leverages on-device Android `TextToSpeech` to speak responses back into the earpiece.

### 4. `desktop_dictation/` (Desktop Dictation Client)
`toggle.sh` starts or stops a uniquely named microphone recording. Completed recordings are processed in order by `queue-worker.sh`, sent to `whisper_service.py`, and inserted by `transcribe-and-type.py` through the modifier-safe `wayland-type-helper.sh`. Failed server requests remain queued under `$XDG_STATE_HOME/assistant-desktop-dictation/queue` for the next invocation, including across restarts. Delivery completion and modifier refusals are reported to `/desktop-delivery-report`; refused text is not retried as delayed keyboard input.

---

## Setup & Running Guide

### Step 1: Start Chromium in Debugging Mode
Ensure your browser automation target is active:
```bash
cd ~/Dev/chatbot
./chatbot --ai-mode
```
*Note: This starts Chromium on debugging port `9233`.*

### Step 2: Start the Whisper Server
Run the local transcriber. Default is `faster-whisper` with `large-v3`:
```bash
cd ~/Dev/assistant
uv run python whisper_service.py
```
Or start it with Canary-Qwen 2.5B:
```bash
cd ~/Dev/assistant
uv sync --extra canary
uv run python whisper_service.py --backend canary-qwen --model nvidia/canary-qwen-2.5b
```
Or start it with Parakeet TDT 0.6B v2:
```bash
cd ~/Dev/assistant
uv sync --extra parakeet
uv run python whisper_service.py --backend parakeet --model nvidia/parakeet-tdt-0.6b-v2
```
Or start it with Granite Speech 4.1 2B:
```bash
cd ~/Dev/assistant
sudo pacman -S --needed llama-cpp
uv sync --extra granite-speech
uv run python whisper_service.py \
  --backend granite-speech \
  --device cuda
```
Or start the validated maximum-accuracy consensus backend:
```bash
cd ~/Dev/assistant
UV_CACHE_DIR=/data/.cache/uv uv sync --extra consensus
UV_CACHE_DIR=/data/.cache/uv uv sync --project consensus_cohere
uv run python whisper_service.py --backend consensus --device cuda
```
The consensus backend runs three fixed voters sequentially: faster-whisper large-v3 with beam 4 and batch 2 for recordings of at least 60 seconds, Cohere Transcribe BF16 with official chunk boundaries and microbatch 1, and the unboosted Parakeet TDT 0.6B v2 `accuracy` preset with 120-second segments and no forced overlap. It then performs deterministic word and punctuation majority voting with Whisper as the tie-breaking anchor. Each voter runs in an isolated process and exits before the next starts, so VRAM is never additive. Driver-level measurements on the RTX 5060 were 4,380 MiB for Whisper, 4,348 MiB for Cohere, and 2,082 MiB for Parakeet, making the measured pipeline peak 4,380 MiB. Across four technical recordings containing 41,500 scored reference words, consensus reduced WER from 5.1518% to 4.4964%, increased punctuation micro-F1 from 0.7872 to 0.8254, and increased sentence-boundary F1 from 0.8191 to 0.8662. See `docs/asr-consensus-benchmark.md` for the full methodology and per-recording results. The Cohere checkpoint is gated; accept its Hugging Face terms and authenticate with `hf auth login` before first use. This mode favors accuracy over latency and leaves the ordinary faster-whisper backend as the interactive default.

`--backend adaptive-consensus` first runs Whisper and Parakeet over the complete recording, detects their word or punctuation disagreements, and sends only those timestamped regions to Cohere. It is an optional middle ground rather than the interactive default: on the ten-minute test it improved WER from 3.0097% to 2.9126% and punctuation F1 from 0.8387 to 0.8541, but took 45.86 seconds instead of 17.22 seconds. On the 66-minute SQLAlchemy recording it recovered only six errors and took 189.06 seconds instead of 114.00 seconds. Overlapping coarse timestamp regions are merged before adjudication so every Whisper anchor range is replaced at most once.

Granite Speech defaults to IBM's official `ibm-granite/granite-speech-4.1-2b-GGUF:Q8_0` model, whose quantized weights are approximately 1.96 GB. The service starts a private persistent llama-server on `127.0.0.1:9797`, automatically offloads the maximum safe number of model layers to the GPU, uses the model's trained 4096-token context, waits up to five minutes for the first download/load, and terminates the managed server during shutdown. Every recording is converted to channel 0, mono 16 kHz PCM16 WAV before it is streamed to `/v1/audio/transcriptions`. Requests explicitly use temperature `0`, a 512-token output guard, and IBM's punctuation/capitalization prompt with the canonical entries from `granite_programming_keywords.txt` appended as `Keywords: ...`. Granite keyword prompt adaptation is separate from Parakeet's uppercase `programming_phrases.txt` NeMo boosting tree. Use `--key-phrases-file` to replace the Granite keyword file, `--no-key-phrases` to disable it, `--granite-prompt` to replace the instruction, and `--granite-max-new-tokens` or `--granite-temperature` to override deterministic generation controls. Internal runtime controls include `--granite-server-binary`, `--granite-server-host`, `--granite-server-port`, `--granite-server-startup-timeout`, `--granite-request-timeout`, and `--granite-context-size`.

With llama.cpp build `b10221`, direct raw, punctuation, and punctuation-plus-keywords tests confirmed that the multipart prompt reaches Granite and affects technical-token casing, but the Q8 GGUF path still returned no sentence punctuation and keyword prompting alone did not reliably canonicalize every identifier. The wrapper therefore applies only curated deterministic technical normalization after Granite; it does not invent general sentence punctuation. The CUDA backend also reports unsupported speech-projector unary operations and falls those operations back internally, although the language model remains GPU-offloaded.
Parakeet supports four presets. The programming preset is the default and is intended for push-to-talk recordings. The `rustly-pocket-conversation` preset enables Silero VAD with 250 ms minimum speech, 600 ms minimum silence, 450 ms padding, 25-second segments, two-second forced-split overlap, word timestamps, and word confidence. Both use `greedy_batch`. The primary `accuracy` preset uses `greedy_batch`, label looping, CUDA graph decoding, the repository `programming_phrases.txt` vocabulary at boosting alpha `0.5`, Silero VAD at threshold `0.30`, 150 ms minimum speech, 700 ms minimum silence, 400 ms speech padding, word timestamps, and word confidence. Accuracy VAD trims the request once from the first padded speech onset through the last padded speech offset, preserves every internal pause, and sends up to 120 seconds through one uninterrupted inference with no forced overlap. Longer requests are split into non-overlapping 120-second ranges. `accuracy-beam-experimental` uses the same input segmentation while preserving beam-5 `malsd_batch` with CUDA graphs for A/B testing. All presets use 16 kHz mono lossless input, BF16 on supported CUDA hardware with FP16 fallback, ten symbols per step, and batch size 1. Parakeet is converted to its inference dtype on CPU before one transfer to CUDA. The persistent service retains unused CUDA allocations between successful requests and releases them after an OOM; startup still releases temporary loading allocations. The uv environment pins PyTorch and Torchaudio 2.11 to CUDA 12.8, cuDNN 9.19, and cuBLAS 12.8 so NeMo and faster-whisper do not load conflicting CUDA 12 and CUDA 13 sublibraries. VAD reads lossless audio through SoundFile rather than TorchCodec. Use `--channel-selector 1` when the second source channel is the clearer one; the presets otherwise select channel 0 instead of averaging.

Every backend now passes through the same technical canonicalizer after raw ASR. Canonical filenames, paths, hashes, command-line options, and curated repository terms are masked while optional US-to-UK conversion runs, then restored exactly. This prevents ordinary spelling conversion from changing protected technical identifiers. Granite Q8 still receives its prompt keywords, but direct G1/G2/G3 and `llama-cli` testing with llama.cpp build `b10221` found that punctuation and keyword prompt modes did not affect this recording's raw output; canonical Granite identifiers observed through the service are therefore produced by deterministic post-processing rather than assumed model keyword adaptation.

Programming preset command:
```bash
cd ~/Dev/assistant
uv sync --extra parakeet
uv run python whisper_service.py \
  --backend parakeet \
  --preset programming \
  --model nvidia/parakeet-tdt-0.6b-v2 \
  --device cuda
```
Programming phrase boosting is configured at alpha `1.5` and uses context score `1.0` and depth scaling `2.0`. To activate it, add `--key-phrases-file /path/to/capitalized-phrases.txt`; the file must contain one capitalized phrase per line. The accuracy presets automatically use `programming_phrases.txt`; override it with `--key-phrases-file`, or disable it with `--no-key-phrases` for an unboosted A/B test. The conversation preset leaves phrase boosting disabled by default. Presets can also be selected with `WHISPER_PRESET`, and the phrase file and channel selector can be set with `WHISPER_KEY_PHRASES_FILE` and `WHISPER_CHANNEL_SELECTOR`.

Accuracy preset command:
```bash
cd ~/Dev/assistant
uv sync --extra parakeet
uv run python whisper_service.py \
  --backend parakeet \
  --preset accuracy \
  --model nvidia/parakeet-tdt-0.6b-v2 \
  --device cuda \
  --variant-conversion \
  --variant-source en_US \
  --variant-target en_GB
```
Use `--preset accuracy-beam-experimental` with the same command to compare the beam-5 decoder against the primary greedy accuracy path.
To force British spellings from a US-biased Whisper transcript:
```bash
cd ~/Dev/assistant
uv run python whisper_service.py \
  --backend faster-whisper \
  --variant-conversion \
  --variant-source en_US \
  --variant-target en_GB
```
For long recordings, audio at least 60 seconds long uses the loaded GPU model through faster-whisper's batched pipeline with accuracy-validated batch size 2. Batch size 4 is an optional speed mode: on the RTX 5060 it peaked at 4,924 MiB and reduced aggregate processing time by about 24% across four recordings, but made three additional errors across 41,500 reference words. Enable it with `--long-audio-batch-size 4` or `WHISPER_LONG_AUDIO_BATCH_SIZE=4`; the same explicit override controls the Whisper voter in consensus modes. If a batched attempt fails, the service retries the same file with ordinary GPU transcription before using the cached CPU fallback for a CUDA memory failure. Configure the threshold with `--batch-threshold-seconds` or `WHISPER_BATCH_THRESHOLD_SECONDS`; batch size 1 disables batching.

Known names and jargon can be supplied to faster-whisper with `--hotwords`, `--hotwords-file`, `WHISPER_HOTWORDS`, or `WHISPER_HOTWORDS_FILE`. Hotwords are disabled by default and should remain narrowly scoped: on the retained technical excerpt, both an 11-term list and a focused three-term list increased WER. Beam-search `--patience` and `--length-penalty` are also configurable through `WHISPER_PATIENCE` and `WHISPER_LENGTH_PENALTY`; the validated defaults remain `1.0` because the tested alternatives did not improve both WER and punctuation.

*It listens on `http://0.0.0.0:5001`. You can also override `--device`, `--compute-type`, `--host`, and `--port`, or use `WHISPER_BACKEND`, `WHISPER_MODEL`, `WHISPER_DEVICE`, `WHISPER_COMPUTE_TYPE`, `WHISPER_HOST`, and `WHISPER_PORT`. Variant conversion can also be controlled with `WHISPER_VARIANT_CONVERSION`, `WHISPER_VARIANT_SOURCE`, and `WHISPER_VARIANT_TARGET`.*

Generic HTTP access records are hidden by default because the transcription and delivery events already report their outcomes. Enable them for protocol debugging with `--http-access-log` or `WHISPER_HTTP_ACCESS_LOG=true`.

### Step 3: Start the Voice Bridge
Run the main server:
```bash
cd ~/Dev/assistant
node voice_bridge.js
```
*It will listen on `http://0.0.0.0:9090`.*

### Step 4: Configure Desktop Dictation
Bind the numeric keypad `+` shortcut to:
```bash
/home/lewis/Dev/assistant/desktop_dictation/toggle.sh
```
The first press starts recording and the second press stops it, queues it, transcribes it through port `5001`, and types it only when no modifier key is held. Logs are written to `$XDG_STATE_HOME/assistant-desktop-dictation/dictation.log`.
PipeWire is used normally. If its recorder process does not produce audio shortly after startup, the client retries the same recording through direct ALSA using `plughw`; it prefers a device named `Yeti Stereo Microphone` and otherwise chooses the first capture device. Set `ASSISTANT_ALSA_DEVICE=plughw:<card>,<device>` to override detection, or set `ASSISTANT_PIPEWIRE_HEALTH_DELAY_S` to adjust the startup check delay.
The included `net.local.trigger.sh.desktop` launcher preserves the existing KDE shortcut service ID when installed under `~/.local/share/applications`.

### Step 5: Install and Run the Android Client
1. Connect your Android phone to the PC with USB Debugging enabled.
2. Build and install the app:
   ```bash
   cd ~/Dev/assistant/android
   ./gradlew installDebug
   ```
3. Pair your Bluetooth earpiece/earbuds to the phone.
4. Launch **SwiftSay** on your phone, configure overlay and accessibility permissions, and tap **Start Service**.
5. Tap the floating microphone button, speak, and tap it again when finished.

### Step 6: Build SwiftSay Local
`swiftsay-local` is a separate app variant. It keeps SwiftSay's floating recording button and accessibility text insertion, but sends its FLAC recording directly to the computer's `whisper_service.py` at `/transcribe_raw` instead of using the hosted API.
```bash
cd ~/Dev/assistant/android
./gradlew :swiftsay-local:assembleDebug -Pkotlin.compiler.execution.strategy=in-process
cp swiftsay-local/build/outputs/apk/debug/swiftsay-local-debug.apk ~/Dev/assistant/SwiftSayLocal.apk
```
Install `SwiftSayLocal.apk`, open **Whisper Server Settings**, enter the computer's LAN address and port `5001`, then start `whisper_service.py` before starting the floating service.
