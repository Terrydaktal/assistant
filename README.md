# Assistant: Standalone Earpiece Voice-Control Bundle

This workspace contains a completely standalone application bundle that routes voice inputs from a dedicated Android app client, processes them locally on your PC via GPU-accelerated Whisper transcription, and communicates programmatically with your active Google AI Mode Chromium session.

Responses are returned to your phone and spoken back into your earpiece using your phone's native, high-quality Text-to-Speech (TTS) engine.

No modifications are made to any of your external repositories (`swiftsay`, `faster-whisperer`, or `chatbot`).

---

## Directory Tree

```text
~/Dev/assistant/
├── package.json          # Node dependency configuration
├── package-lock.json     # Node lockfile
├── pyproject.toml        # Python virtual environment configuration (uv)
├── uv.lock               # Python lockfile
├── whisper_service.py    # Whisper-large-v3 CUDA server (FastAPI)
├── voice_bridge.js       # Voice Router / CDP browser automator (Node.js)
├── README.md             # This guide
└── android/              # Standalone Android application client
    ├── app/
    ├── gradle/
    └── ...
```

---

## Script Breakdown

### 1. `whisper_service.py` (Local Whisper Server)
Loads `whisper-large-v3` into memory on CUDA.
- **Input:** POST request with binary audio payload at `/transcribe_raw`.
- **Output:** JSON containing the decoded string `{"text": "..."}`.
- **Backend:** Python + FastAPI + faster-whisper.

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
Run the local GPU transcriber:
```bash
cd ~/Dev/assistant
uv run python whisper_service.py
```
*It will load the model and listen on `http://0.0.0.0:5001`.*

### Step 3: Start the Voice Bridge
Run the main server:
```bash
cd ~/Dev/assistant
node voice_bridge.js
```
*It will listen on `http://0.0.0.0:9090`.*

### Step 4: Install and Run the Android Client
1. Connect your Android phone to the PC with USB Debugging enabled.
2. Build and install the app:
   ```bash
   cd ~/Dev/assistant/android
   ./gradlew installDebug
   ```
3. Pair your Bluetooth earpiece/earbuds to the phone.
4. Launch **SwiftSay** on your phone, configure overlay and accessibility permissions, and tap **Start Service**.
5. Tap the floating microphone button, speak, and tap it again when finished.
