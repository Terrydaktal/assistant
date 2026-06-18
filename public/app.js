'use strict';

let mediaRecorder = null;
let audioChunks = [];
let isRecording = false;

const actionBtn = document.getElementById('action-btn');
const glowRing = document.getElementById('glow-ring');
const micIcon = document.getElementById('mic-icon');
const stopIcon = document.getElementById('stop-icon');
const stateText = document.getElementById('state-text');
const connectionStatus = document.getElementById('connection-status');

const transcriptBox = document.getElementById('transcript-box');
const transcriptText = document.getElementById('transcript-text');
const responseBox = document.getElementById('response-box');
const responseText = document.getElementById('response-text');

const ttsRate = document.getElementById('tts-rate');
const rateVal = document.getElementById('rate-val');
const ttsPitch = document.getElementById('tts-pitch');
const pitchVal = document.getElementById('pitch-val');

// Update slider label values
ttsRate.addEventListener('input', (e) => {
  rateVal.textContent = `${e.target.value}x`;
});
ttsPitch.addEventListener('input', (e) => {
  pitchVal.textContent = e.target.value;
});

// Setup audio recording
async function setupRecorder() {
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    // Determine the optimal MIME type supported by the browser
    const options = { mimeType: 'audio/webm' };
    if (!MediaRecorder.isTypeSupported(options.mimeType)) {
      options.mimeType = 'audio/ogg';
    }
    if (!MediaRecorder.isTypeSupported(options.mimeType)) {
      options.mimeType = ''; // Default browser format
    }

    mediaRecorder = new MediaRecorder(stream, options);

    mediaRecorder.ondataavailable = (event) => {
      if (event.data.size > 0) {
        audioChunks.push(event.data);
      }
    };

    mediaRecorder.onstop = async () => {
      const audioBlob = new Blob(audioChunks, { type: mediaRecorder.mimeType || 'audio/webm' });
      audioChunks = [];
      await sendAudioToServer(audioBlob);
    };

    connectionStatus.textContent = 'Ready';
    connectionStatus.className = 'status-badge connected';
  } catch (err) {
    console.error('Microphone access denied or error:', err);
    stateText.textContent = 'Microphone access required!';
    connectionStatus.textContent = 'Mic Error';
    connectionStatus.className = 'status-badge error';
  }
}

// Toggle recording state
actionBtn.addEventListener('click', async () => {
  if (!mediaRecorder) {
    await setupRecorder();
  }

  if (isRecording) {
    // Stop recording
    mediaRecorder.stop();
    isRecording = false;
    actionBtn.classList.remove('recording');
    micIcon.classList.remove('hidden');
    stopIcon.classList.add('hidden');
    stateText.textContent = 'Transcribing voice...';
    // Cancel any active SpeechSynthesis playback when user starts a new interaction
    window.speechSynthesis.cancel();
  } else {
    // Start recording
    audioChunks = [];
    mediaRecorder.start();
    isRecording = true;
    actionBtn.classList.add('recording');
    micIcon.classList.add('hidden');
    stopIcon.classList.remove('hidden');
    stateText.textContent = 'Listening to voice...';
    window.speechSynthesis.cancel();
  }
});

// Send captured audio binary to voice bridge
async function sendAudioToServer(blob) {
  try {
    const response = await fetch('/voice-command', {
      method: 'POST',
      body: blob,
      headers: {
        'Content-Type': 'application/octet-stream'
      }
    });

    if (!response.ok) {
      throw new Error(`Server returned code ${response.status}`);
    }

    const data = await response.json();
    
    // Display results in UI
    if (data.transcription) {
      transcriptText.textContent = data.transcription;
      transcriptBox.classList.remove('hidden');
    } else {
      transcriptBox.classList.add('hidden');
    }

    if (data.response) {
      responseText.textContent = data.response;
      responseBox.classList.remove('hidden');
      stateText.textContent = 'Speaking response...';
      speak(data.response);
    } else if (data.error) {
      responseText.textContent = `Error: ${data.error}`;
      responseBox.classList.remove('hidden');
      stateText.textContent = 'Error occurred';
    }

  } catch (err) {
    console.error('Failed to process command:', err);
    stateText.textContent = 'Network or Server Error';
    responseText.textContent = 'Failed to communicate with PC server.';
    responseBox.classList.remove('hidden');
  }
}

// Perform Text to Speech playback
function speak(text) {
  // Cancel any ongoing speaking
  window.speechSynthesis.cancel();

  // Strip Markdown / HTML tags briefly for cleaner speech synthesis
  const cleanText = text
    .replace(/<\/?[^>]+(>|$)/g, "")
    .replace(/[*_`#\-]/g, "")
    .trim();

  const utterance = new SpeechSynthesisUtterance(cleanText);
  utterance.rate = parseFloat(ttsRate.value);
  utterance.pitch = parseFloat(ttsPitch.value);

  // Attempt to select a high-quality local voice if available
  const voices = window.speechSynthesis.getVoices();
  const preferredVoice = voices.find(v => 
    v.lang.startsWith('en') && 
    (v.name.includes('Google') || v.name.includes('Natural') || v.name.includes('Premium'))
  );
  if (preferredVoice) {
    utterance.voice = preferredVoice;
  }

  utterance.onend = () => {
    stateText.textContent = 'Tap to talk';
  };

  utterance.onerror = (e) => {
    console.error('SpeechSynthesis error:', e);
    stateText.textContent = 'Tap to talk';
  };

  window.speechSynthesis.speak(utterance);
}

// Initial setup on load
window.addEventListener('load', () => {
  setupRecorder();
  // Chrome requires voices to be loaded asynchronously
  if (window.speechSynthesis.onvoiceschanged !== undefined) {
    window.speechSynthesis.onvoiceschanged = () => {
      // Warm up voices load
      window.speechSynthesis.getVoices();
    };
  }
});
