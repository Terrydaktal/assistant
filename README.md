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
├── voice_bridge.js       # Voice Router / CDP browser automator (Node.js)
├── desktop_dictation/    # Queued desktop recording, transcription, and safe typing
├── README.md             # This guide
└── android/              # Standalone Android application client
    ├── app/
    ├── gradle/
    └── ...
```

---

## Script Breakdown

### 1. `whisper_service.py` (Local Whisper Server)
Loads a selectable transcription backend into memory.
- **Input:** POST request with binary audio payload at `/transcribe_raw`.
- **Output:** JSON containing `{"text": "...", "backend": "...", "model": "..."}` plus variant-conversion metadata when enabled.
- **Backends:** `faster-whisper` by default, or `nvidia/canary-qwen-2.5b` when started with the Canary backend.
- **Optional post-processing:** deterministic English variant conversion (for example `en_US -> en_GB`) using the Trelis English Variant Converter library.

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
- **Recording:** Captures audio from your microphone/earpiece and saves to FLAC.
- **Voice Bridge Request:** POSTs the FLAC audio payload to `http://<your-pc-ip>:9090/voice-command`.
- **TTS rendering:** Leverages on-device Android `TextToSpeech` to speak responses back into the earpiece.

### 4. `desktop_dictation/` (Desktop Dictation Client)
`toggle.sh` starts or stops a uniquely named microphone recording. Completed recordings are processed in order by `queue-worker.sh`, sent to `whisper_service.py`, and inserted by `transcribe-and-type.py` through the modifier-safe `wayland-type-helper.sh`. Failed server requests remain queued under `$XDG_STATE_HOME/assistant-desktop-dictation/queue` for the next invocation, including across restarts. Modifier refusals are reported to `/client-event` and are not retried as delayed keyboard input.

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
To force British spellings from a US-biased Whisper transcript:
```bash
cd ~/Dev/assistant
uv run python whisper_service.py \
  --backend faster-whisper \
  --variant-conversion \
  --variant-source en_US \
  --variant-target en_GB
```
*It listens on `http://0.0.0.0:5001`. You can also override `--device`, `--compute-type`, `--host`, and `--port`, or use `WHISPER_BACKEND`, `WHISPER_MODEL`, `WHISPER_DEVICE`, `WHISPER_COMPUTE_TYPE`, `WHISPER_HOST`, and `WHISPER_PORT`. Variant conversion can also be controlled with `WHISPER_VARIANT_CONVERSION`, `WHISPER_VARIANT_SOURCE`, and `WHISPER_VARIANT_TARGET`.*

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
