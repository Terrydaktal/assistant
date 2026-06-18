#!/usr/bin/env node
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer-core');

// Dynamically reference the BrowserAiInterface from the user's chatbot repository
const { BrowserAiInterface } = require('/home/lewis/Dev/chatbot/lib/browser-ai-interface.js');

const BROWSER_PORT = Number(process.env.BROWSER_PORT || 9233);
const LISTEN_PORT = Number(process.env.LISTEN_PORT || 9090);
const WHISPER_SERVER_URL = process.env.WHISPER_SERVER_URL || 'http://127.0.0.1:5001/transcribe_raw';

const AI_MODE_URL = 'https://www.google.com/search?udm=50&aep=11';
const AI_MODE_HISTORY_BUTTON_SELECTOR = 'button.UTNPFf[aria-label="AI Mode history"]';
const AI_MODE_HISTORY_ITEM_SELECTOR = 'button.qqMZif[data-thread-id]';

class VoiceBridge {
  constructor(port) {
    this.port = port;
    this.browser = null;
    this.page = null;
    this.ai = new BrowserAiInterface();
  }

  async ensureConnected() {
    if (this.browser && this.browser.connected) return;
    console.log(`Connecting to browser debugging port ${this.port}...`);
    this.browser = await puppeteer.connect({
      browserURL: `http://127.0.0.1:${this.port}`,
      defaultViewport: null,
    });
    this.browser.on('disconnected', () => {
      this.browser = null;
      this.page = null;
      console.log('Browser disconnected.');
    });
  }

  async ensurePage() {
    await this.ensureConnected();
    if (!this.page || this.page.isClosed()) {
      const pages = await this.browser.pages();
      this.page = pages.find((p) => !p.url().startsWith('chrome-extension://')) || await this.browser.newPage();
    }
    const currentUrl = this.page.url() || '';
    if (!currentUrl.includes('google.com/search')) {
      console.log('Navigating to AI Mode surface...');
      await this.page.goto(AI_MODE_URL, { waitUntil: 'networkidle2', timeout: 60000 });
    }
    const selectors = this.ai.getModelConfig('aimode').inputSelectors;
    await this.page.waitForSelector(selectors.join(', '), { timeout: 10000 });
    return this.page;
  }

  async startNewChat() {
    const page = await this.ensurePage();
    console.log('Starting a new chat session...');
    await page.goto(AI_MODE_URL, { waitUntil: 'networkidle2', timeout: 60000 });
    const selectors = this.ai.getModelConfig('aimode').inputSelectors;
    await page.waitForSelector(selectors.join(', '), { timeout: 10000 });
  }

  async listRecentChats(limit = 20) {
    const page = await this.ensurePage();
    const historyButton = await page.$(AI_MODE_HISTORY_BUTTON_SELECTOR);
    if (historyButton) {
      const initialItems = await page.$$(AI_MODE_HISTORY_ITEM_SELECTOR);
      if (!initialItems.length) {
        await historyButton.click();
        await new Promise((resolve) => setTimeout(resolve, 1000));
        try {
          await page.waitForSelector(AI_MODE_HISTORY_ITEM_SELECTOR, { timeout: 5000 });
        } catch {}
      }
    }

    const chats = await page.evaluate((selector) => {
      const results = [];
      const seen = new Set();
      const elements = Array.from(document.querySelectorAll(selector));
      elements.forEach((el, index) => {
        const title = (el.innerText || el.getAttribute('aria-label') || 'Untitled chat').trim();
        const threadId = el.getAttribute('data-thread-id');
        const key = threadId || `idx:${index}`;
        if (seen.has(key)) return;
        seen.add(key);
        results.push({ title, href: '', clickIndex: index });
      });
      return results;
    }, AI_MODE_HISTORY_ITEM_SELECTOR);

    return chats.slice(0, limit);
  }

  async selectChat(clickIndex) {
    const page = await this.ensurePage();
    const clicked = await page.evaluate((index, selector) => {
      const items = document.querySelectorAll(selector);
      if (!items[index]) return false;
      items[index].scrollIntoView({ block: 'center' });
      items[index].click();
      return true;
    }, clickIndex, AI_MODE_HISTORY_ITEM_SELECTOR);
    if (!clicked) throw new Error('Could not click chat item.');
    try {
      await page.waitForNetworkIdle({ timeout: 10000, idleTime: 550 });
    } catch {}
    const selectors = this.ai.getModelConfig('aimode').inputSelectors;
    await page.waitForSelector(selectors.join(', '), { timeout: 10000 });
  }

  async ask(promptText) {
    const page = await this.ensurePage();
    console.log(`Sending query to AI Mode: "${promptText}"`);
    return await this.ai.ask(page, {
      prompt: promptText,
      model: 'aimode',
      timeoutMs: 60000,
    });
  }
}

const bridge = new VoiceBridge(BROWSER_PORT);

// Forward raw audio bytes to local Whisper server
function transcribeAudio(audioBuffer) {
  return new Promise((resolve, reject) => {
    const parsedUrl = new URL(WHISPER_SERVER_URL);
    const options = {
      hostname: parsedUrl.hostname,
      port: parsedUrl.port,
      path: parsedUrl.pathname,
      method: 'POST',
      headers: {
        'Content-Type': 'application/octet-stream',
        'Content-Length': audioBuffer.length,
      },
    };

    const req = http.request(options, (res) => {
      let data = '';
      res.on('data', (chunk) => {
        data += chunk;
      });
      res.on('end', () => {
        try {
          const parsed = JSON.parse(data);
          resolve(parsed.text || '');
        } catch (err) {
          reject(new Error(`Failed to parse response: ${data}`));
        }
      });
    });

    req.on('error', (err) => {
      reject(err);
    });

    req.write(audioBuffer);
    req.end();
  });
}

// Helper to serve static files
function serveStaticFile(res, filePath, contentType) {
  const absolutePath = path.join(__dirname, filePath);
  fs.readFile(absolutePath, (err, content) => {
    if (err) {
      res.writeHead(500, { 'Content-Type': 'text/plain' });
      res.end(`Server Error: ${err.code}`);
    } else {
      res.writeHead(200, { 'Content-Type': contentType });
      res.end(content, 'utf-8');
    }
  });
}

// Start HTTP Server
const server = http.createServer(async (req, res) => {
  // CORS Headers for mobile client access
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  const urlPath = req.url.split('?')[0];

  // Route: Static files
  if (req.method === 'GET') {
    if (urlPath === '/' || urlPath === '/index.html') {
      serveStaticFile(res, 'public/index.html', 'text/html');
    } else if (urlPath === '/app.js') {
      serveStaticFile(res, 'public/app.js', 'application/javascript');
    } else if (urlPath === '/style.css') {
      serveStaticFile(res, 'public/style.css', 'text/css');
    } else {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('Not Found');
    }
    return;
  }

  // Route: Voice Command processing
  if (req.method === 'POST' && urlPath === '/voice-command') {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', async () => {
      try {
        const audioBuffer = Buffer.concat(chunks);
        console.log(`Received voice recording: ${audioBuffer.length} bytes.`);
        
        // 1. Transcribe the audio
        const transcribedText = await transcribeAudio(audioBuffer);
        console.log(`Whisper output: "${transcribedText}"`);
        
        if (!transcribedText || transcribedText.trim().length === 0) {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ 
            transcription: "", 
            response: "I didn't hear anything. Please try again." 
          }));
          return;
        }

        const normalized = transcribedText.toLowerCase().trim();

        // 2. Process Voice Commands
        if (normalized === 'open a new chat' || normalized === 'new chat' || normalized === 'open new chat') {
          await bridge.startNewChat();
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ 
            transcription: transcribedText, 
            response: "Opened a new chat." 
          }));
          return;
        }

        // Navigate to existing chat command
        const openChatMatch = normalized.match(/^(?:open chat|navigate to chat|switch to chat) (.+)$/);
        if (openChatMatch) {
          const targetTitle = openChatMatch[1].trim();
          console.log(`Attempting to switch to chat: "${targetTitle}"`);
          const chats = await bridge.listRecentChats();
          const found = chats.find(c => c.title.toLowerCase().includes(targetTitle));
          if (found) {
            await bridge.selectChat(found.clickIndex);
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ 
              transcription: transcribedText, 
              response: `Navigated to chat: ${found.title}` 
            }));
          } else {
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ 
              transcription: transcribedText, 
              response: `Could not find a chat matching "${targetTitle}".` 
            }));
          }
          return;
        }

        // 3. Normal Prompt to Google AI Mode
        const aiResponse = await bridge.ask(transcribedText);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ 
          transcription: transcribedText, 
          response: aiResponse 
        }));

      } catch (err) {
        console.error('Error processing command:', err);
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: err.message }));
      }
    });
  } else {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Not Found');
  }
});

server.listen(LISTEN_PORT, '0.0.0.0', () => {
  console.log(`Voice Bridge Server running at http://0.0.0.0:${LISTEN_PORT}/`);
  console.log(`Open http://<your-pc-ip>:${LISTEN_PORT} on your phone web browser to begin.`);
});
