import os
import sys
import tempfile
import json
import secrets # Used for secure comparison
from http.server import HTTPServer, BaseHTTPRequestHandler
from faster_whisper import WhisperModel

# --- CONFIGURATION ---
MODEL_SIZE = "large-v3"
DEVICE = "cuda"
COMPUTE_TYPE = "float16"

# 🔒 SECURITY: Define your required secret key here
# Ideally, load this from an environment variable
SERVER_API_KEY = "4twthvt384d"

print("Loading Whisper model...")
model = WhisperModel(MODEL_SIZE, device=DEVICE, compute_type=COMPUTE_TYPE)
print("✅ Model loaded!")

class SwiftsayHandler(BaseHTTPRequestHandler):
    
    def validate_auth(self):
        """
        Checks if the request contains the correct API key.
        Returns True if authorized, False otherwise.
        """
        # We look for a header named 'X-API-KEY'
        # You can change this to 'Authorization' or anything else
        incoming_key = self.headers.get('X-API-KEY')

        if incoming_key is None:
            self.send_error(401, "Unauthorized: Missing API Key")
            return False
            
        # secrets.compare_digest prevents timing attacks
        # (It's safer than using '==')
        if not secrets.compare_digest(incoming_key, SERVER_API_KEY):
            self.send_error(403, "Forbidden: Invalid API Key")
            return False
            
        return True

    def do_GET(self):
        # Optional: You can choose to protect GET requests too, or leave them public
        if self.path == '/health':
            self.send_success({"status": "ok", "model": MODEL_SIZE})
        elif self.path == '/':
            self.send_response(200)
            self.wfile.write(b"Swiftsay Server. Use POST /transcribe with X-API-KEY header.")
        else:
            self.send_error(404, "Not Found")
    
    def do_POST(self):
        # 🛑 SECURITY CHECK
        # We check this FIRST. If it fails, we stop immediately.
        if not self.validate_auth():
            return

        if self.path == '/transcribe':
            try:
                content_length = int(self.headers.get('Content-Length', 0))
                
                # ... (Rest of your code remains the same) ...
                
                if content_length == 0:
                    self.send_error(400, "No audio data")
                    return
                
                # Add a size limit check (e.g., 50MB) to prevent crashing RAM
                if content_length > 50 * 1024 * 1024:
                    self.send_error(413, "File too large (Max 50MB)")
                    return

                audio_data = self.rfile.read(content_length)
                
                with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as f:
                    f.write(audio_data)
                    temp_path = f.name
                
                print(f"📥 Authorized Request Received: {len(audio_data)} bytes")
                
                segments, info = model.transcribe(
                    temp_path,
                    word_timestamps=False,
                    initial_prompt="Hello.",
                    language="en",
                    beam_size=5,
                    no_speech_threshold=None,
                    vad_filter=False
                )
                
                transcription = "".join([segment.text for segment in segments]).strip()
                os.unlink(temp_path)
                
                self.send_success({
                    "text": transcription,
                    "language": info.language,
                    "duration": info.duration
                })
                
            except Exception as e:
                print(f"❌ Error: {e}")
                self.send_error(500, f"Error: {str(e)}")
        else:
            self.send_error(404, "Not Found")
    
    def send_success(self, data):
        response = json.dumps(data).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        self.wfile.write(response)

def run_server(host='0.0.0.0', port=5000):
    server = HTTPServer((host, port), SwiftsayHandler)
    print(f"🚀 Secured Server running on port {port}")
    server.serve_forever()

if __name__ == '__main__':
    run_server()
