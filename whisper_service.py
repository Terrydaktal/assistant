import os
import sys
import glob
import ctypes

# Automatically locate and preload the local virtual environment's CUDA libraries
venv_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".venv")
nvidia_libs = glob.glob(os.path.join(venv_path, "lib", "python*", "site-packages", "nvidia", "*", "lib"))
if nvidia_libs:
    # Update LD_LIBRARY_PATH env var
    existing_ld = os.environ.get("LD_LIBRARY_PATH", "")
    new_ld = ":".join(nvidia_libs)
    os.environ["LD_LIBRARY_PATH"] = f"{new_ld}:{existing_ld}" if existing_ld else new_ld

    # Preload libcublas and libcudnn directly into process memory using ctypes
    for path in nvidia_libs:
        if os.path.exists(path):
            for file in os.listdir(path):
                if file.startswith("libcublas.so") or file.startswith("libcudnn.so"):
                    try:
                        ctypes.CDLL(os.path.join(path, file))
                    except Exception:
                        pass

import tempfile
from fastapi import FastAPI, Request
from faster_whisper import WhisperModel

app = FastAPI(title="Local Whisper Server")

# Load model in memory (RTX 5060 has CUDA)
MODEL_SIZE = "large-v3"
DEVICE = "cuda"
COMPUTE_TYPE = "float16"

print(f"Loading Whisper model {MODEL_SIZE} on {DEVICE}...")
model = WhisperModel(MODEL_SIZE, device=DEVICE, compute_type=COMPUTE_TYPE)
print("Whisper model loaded successfully!")


@app.post("/transcribe_raw")
async def transcribe_raw(request: Request):
    body = await request.body()
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        tmp.write(body)
        tmp_path = tmp.name

    try:
        # Transcribe audio file using local CUDA-accelerated Whisper model
        segments, info = model.transcribe(
            tmp_path,
            word_timestamps=False,
            language="en",
            beam_size=5
        )
        text = "".join(segment.text for segment in segments).strip()
        print(f"Transcribed Raw: '{text}'")
        return {"text": text}
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)

if __name__ == "__main__":
    import uvicorn
    # Listening on all interfaces on port 5001 (to avoid conflict with 5000)
    uvicorn.run(app, host="0.0.0.0", port=5001)
