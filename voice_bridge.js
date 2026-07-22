#!/usr/bin/env node
'use strict';

const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer-core');

function formatLogTimestamp() {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  const hours = String(now.getHours()).padStart(2, '0');
  const minutes = String(now.getMinutes()).padStart(2, '0');
  const seconds = String(now.getSeconds()).padStart(2, '0');
  const millis = String(now.getMilliseconds()).padStart(3, '0');
  return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}.${millis}`;
}

function patchConsoleMethod(methodName) {
  const original = console[methodName].bind(console);
  console[methodName] = (...args) => original(`[${formatLogTimestamp()}]`, ...args);
}

patchConsoleMethod('log');
patchConsoleMethod('warn');
patchConsoleMethod('error');

// Dynamically reference the BrowserAiInterface from the user's chatbot repository
const { BrowserAiInterface } = require('/home/lewis/Dev/chatbot/lib/browser-ai-interface.js');

const BROWSER_PORT = Number(process.env.BROWSER_PORT || 9233);
const LISTEN_PORT = Number(process.env.LISTEN_PORT || 9090);
const WHISPER_SERVER_URL = process.env.WHISPER_SERVER_URL || 'http://127.0.0.1:5001/transcribe_raw';
const AI_MODE_RESPONSE_TIMEOUT_MS = Number(process.env.AI_MODE_RESPONSE_TIMEOUT_MS || 120000);

const AI_MODE_URL = 'https://www.google.com/search?udm=50&aep=11';
const AI_MODE_HISTORY_BUTTON_SELECTOR = 'button.UTNPFf[aria-label="AI Mode history"]';
const AI_MODE_HISTORY_ITEM_SELECTOR = 'button.qqMZif[data-thread-id]';
const JOB_TTL_MS = 10 * 60 * 1000;
const voiceCommandJobs = new Map();
const WAKE_WORD_VARIANTS = new Set([
  'earpiece',
  'earpeace',
  'earpies',
  'earbuds',
  'airpiece',
  'airpeace',
  'piece',
]);
const WAKE_PHRASE_VARIANTS = new Set([
  'ear piece',
  'ear peace',
  'air piece',
  'air peace',
  'earpiece ai',
  'earpiece hey',
]);

function normalizeCommandText(text) {
  return text
    .toLowerCase()
    .replace(/[^a-z0-9 ]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function hasWakePhrase(normalized) {
  const words = normalized.split(' ').filter(Boolean);
  if (words.some((word) => WAKE_WORD_VARIANTS.has(word))) {
    return true;
  }
  return words.some((word, index) => {
    if (index === words.length - 1) return false;
    return WAKE_PHRASE_VARIANTS.has(`${word} ${words[index + 1]}`);
  });
}

function isStopCommand(normalized) {
  return hasWakePhrase(normalized) && normalized.split(' ').includes('stop');
}

function isRepeatCommand(normalized) {
  const words = normalized.split(' ');
  return hasWakePhrase(normalized) &&
    words.some((word) => word === 'repeat' || word === 'replay' || word === 'again');
}

function isRewindCommand(normalized) {
  const words = normalized.split(' ');
  return hasWakePhrase(normalized) &&
    words.some((word) => word === 'rewind');
}

function isGoBackCommand(normalized) {
  const words = normalized.split(' ');
  return hasWakePhrase(normalized) &&
    words.some((word, index) => word === 'back' || (word === 'go' && words[index + 1] === 'back'));
}

function isGoForwardCommand(normalized) {
  const words = normalized.split(' ');
  return hasWakePhrase(normalized) &&
    words.some((word, index) => word === 'forward' || (word === 'go' && words[index + 1] === 'forward'));
}

const NUMBER_WORDS = new Map([
  ['one', 1],
  ['two', 2],
  ['three', 3],
  ['four', 4],
  ['five', 5],
  ['six', 6],
  ['seven', 7],
  ['eight', 8],
  ['nine', 9],
  ['ten', 10],
]);

function parseChatNumber(value) {
  if (typeof value === 'number' && Number.isInteger(value) && value > 0) {
    return value;
  }
  if (typeof value !== 'string') {
    return null;
  }
  const trimmed = value.trim().toLowerCase();
  if (/^\d+$/.test(trimmed)) {
    return Number(trimmed);
  }
  return NUMBER_WORDS.get(trimmed) || null;
}

function formatChatTitle(title) {
  const clean = (title || 'Untitled chat').replace(/\s+/g, ' ').trim();
  if (clean.length <= 60) {
    return clean;
  }
  return `${clean.slice(0, 57).trimEnd()}...`;
}

function formatChatListResponse(chats) {
  if (!chats.length) {
    return 'No chats were found.';
  }
  return `Current chats: ${chats
    .map((chat, index) => `${index + 1}. ${formatChatTitle(chat.title)}`)
    .join('. ')}`;
}

async function handleLocalBridgeCommand(action, payload = {}) {
  switch (action) {
    case 'create_new_chat':
      await bridge.startNewChat();
      bridge.resetMessageNavigation();
      return {
        transcription: '',
        response: 'Opened a new chat.',
        action: 'speak_response',
      };
    case 'list_chats': {
      const chats = await bridge.listRecentChats(10);
      return {
        transcription: '',
        response: formatChatListResponse(chats),
        action: 'speak_response',
      };
    }
    case 'select_chat': {
      const requestedNumber = parseChatNumber(payload.chat_number);
      if (!requestedNumber) {
        return {
          transcription: '',
          response: 'I could not tell which chat number to open.',
          action: 'speak_response',
        };
      }
      const chats = await bridge.listRecentChats(20);
      const selected = chats[requestedNumber - 1];
      if (!selected) {
        return {
          transcription: '',
          response: `Chat ${requestedNumber} is not available.`,
          action: 'speak_response',
        };
      }
      await bridge.selectChat(selected.clickIndex);
      return {
        transcription: '',
        response: `Opened chat ${requestedNumber}: ${formatChatTitle(selected.title)}`,
        action: 'speak_response',
      };
    }
    case 'navigate_chat_message':
      return bridge.navigateChatMessages(payload.direction);
    default:
      return {
        transcription: '',
        response: 'Unknown local command.',
        action: 'speak_response',
      };
  }
}

function writeJson(res, statusCode, body) {
  res.writeHead(statusCode, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

function createVoiceCommandJob() {
  const id = crypto.randomUUID();
  voiceCommandJobs.set(id, {
    id,
    status: 'processing',
    createdAt: Date.now(),
    updatedAt: Date.now(),
    result: null,
    error: null,
    timings: null,
    listeners: new Set(),
  });
  return id;
}

function completeVoiceCommandJob(id, result) {
  const job = voiceCommandJobs.get(id);
  if (!job) return;
  job.status = 'completed';
  job.updatedAt = Date.now();
  job.result = result;
  if (result && result.timings) {
    console.log('Voice command timing outcome=success', result.timings);
  }
  notifyVoiceCommandJobListeners(job);
}

function failVoiceCommandJob(id, error) {
  const job = voiceCommandJobs.get(id);
  if (!job) return;
  job.status = 'failed';
  job.updatedAt = Date.now();
  job.error = error instanceof Error ? error.message : String(error);
  job.timings = error && error.timings ? error.timings : null;
  notifyVoiceCommandJobListeners(job);
}

function formatVoiceCommandJobPayload(job) {
  if (job.status === 'completed') {
    return {
      request_id: job.id,
      status: job.status,
      ...job.result,
    };
  }
  if (job.status === 'failed') {
    return {
      request_id: job.id,
      status: job.status,
      error: job.error || 'Unknown processing error',
      timings: job.timings,
    };
  }
  return {
    request_id: job.id,
    status: job.status,
  };
}

function writeSseEvent(res, eventName, payload) {
  res.write(`event: ${eventName}\n`);
  res.write(`data: ${JSON.stringify(payload)}\n\n`);
}

function notifyVoiceCommandJobListeners(job) {
  if (!job.listeners || !job.listeners.size) return;
  const payload = formatVoiceCommandJobPayload(job);
  const eventName = job.status === 'completed' ? 'result' : 'error';
  for (const res of [...job.listeners]) {
    try {
      writeSseEvent(res, eventName, payload);
      res.end();
    } catch (err) {
      console.error('Failed to send SSE result:', err);
    } finally {
      job.listeners.delete(res);
    }
  }
}

function attachVoiceCommandJobListener(job, req, res) {
  if (job.status !== 'processing') {
    const payload = formatVoiceCommandJobPayload(job);
    const eventName = job.status === 'completed' ? 'result' : job.status === 'failed' ? 'error' : 'status';
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    });
    writeSseEvent(res, eventName, payload);
    res.end();
    return;
  }

  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
  res.write(': connected\n\n');
  writeSseEvent(res, 'accepted', formatVoiceCommandJobPayload(job));
  job.listeners.add(res);

  req.on('close', () => {
    job.listeners.delete(res);
  });
}

function pruneExpiredVoiceCommandJobs() {
  const cutoff = Date.now() - JOB_TTL_MS;
  for (const [id, job] of voiceCommandJobs.entries()) {
    if (job.updatedAt < cutoff) {
      if (job.listeners) {
        for (const res of job.listeners) {
          try {
            res.end();
          } catch {}
        }
      }
      voiceCommandJobs.delete(id);
    }
  }
}

class VoiceBridge {
  constructor(port) {
    this.port = port;
    this.browser = null;
    this.page = null;
    this.ai = new BrowserAiInterface();
    this.messageNavigation = {
      chatKey: '',
      currentIndex: -1,
      currentText: '',
    };
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
    this.resetMessageNavigation();
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
    this.resetMessageNavigation();
  }

  async ask(promptText) {
    const page = await this.ensurePage();
    console.log(`Sending query to AI Mode: "${promptText}"`);
    const response = await this.ai.ask(page, {
      prompt: promptText,
      model: 'aimode',
      timeoutMs: AI_MODE_RESPONSE_TIMEOUT_MS,
    });
    await this.setMessageNavigationToLatest();
    return response;
  }

  resetMessageNavigation() {
    this.messageNavigation = {
      chatKey: '',
      currentIndex: -1,
      currentText: '',
      initializedByNavigation: false,
    };
  }

  async getConversationMessages() {
    const page = await this.ensurePage();
    return page.evaluate(() => {
      const isVisible = (el) => {
        if (!el) return false;
        const style = window.getComputedStyle(el);
        if (!style || style.display === 'none' || style.visibility === 'hidden') return false;
        return el.offsetWidth > 0 && el.offsetHeight > 0;
      };

      const cleanup = (text) => String(text || '')
        .replace(/\bCopy text\b/ig, '')
        .replace(/(Generating\.\.\.|\d{1,2}:\d{2})\s*$/ig, '')
        .replace(/\n{3,}/g, '\n\n')
        .trim();

      const turns = Array.from(document.querySelectorAll('[data-xid="aim-mars-turn-root"]'))
        .filter(isVisible)
        .sort((a, b) => a.getBoundingClientRect().top - b.getBoundingClientRect().top);

      return turns
        .map((turn, index) => {
          const leafBlocks = Array.from(turn.querySelectorAll('[data-xid="VpUvz"]'))
            .filter(isVisible)
            .sort((a, b) => a.getBoundingClientRect().bottom - b.getBoundingClientRect().bottom);
          const rawText = leafBlocks.length
            ? (leafBlocks[leafBlocks.length - 1].innerText || leafBlocks[leafBlocks.length - 1].textContent || '')
            : (turn.innerText || turn.textContent || '');
          const text = cleanup(rawText);
          const hasCopyButton = Boolean(turn.querySelector('button[aria-label="Copy text"]'));
          const role = hasCopyButton ? 'assistant' : 'user';
          return {
            index,
            role,
            text,
          };
        })
        .filter((message) => message.text);
    });
  }

  async getCurrentChatKey() {
    const page = await this.ensurePage();
    return page.evaluate(() => {
      const thread = document.querySelector('[data-thread-id]');
      const threadId = thread ? String(thread.getAttribute('data-thread-id') || '').trim() : '';
      const title = document.title || '';
      return `${location.href}::${threadId}::${title}`;
    });
  }

  async setMessageNavigationToLatest() {
    const messages = await this.getConversationMessages();
    if (!messages.length) {
      this.resetMessageNavigation();
      return null;
    }
    const chatKey = await this.getCurrentChatKey();
    const latest = messages[messages.length - 1];
    this.messageNavigation = {
      chatKey,
      currentIndex: latest.index,
      currentText: latest.text,
      initializedByNavigation: false,
    };
    return latest;
  }

  async navigateChatMessages(direction) {
    const messages = await this.getConversationMessages();
    if (!messages.length) {
      return {
        response: 'There are no messages in the current chat.',
        action: 'speak_response',
      };
    }

    const chatKey = await this.getCurrentChatKey();
    const latestIndex = messages[messages.length - 1].index;
    let currentIndex = latestIndex;
    let hasExistingCursor = false;

    if (this.messageNavigation.chatKey === chatKey) {
      if (
        this.messageNavigation.initializedByNavigation &&
        Number.isInteger(this.messageNavigation.currentIndex) &&
        this.messageNavigation.currentIndex >= 0 &&
        this.messageNavigation.currentIndex < messages.length
      ) {
        currentIndex = this.messageNavigation.currentIndex;
        hasExistingCursor = true;
      } else {
        const byText = messages.findIndex((message) => message.text === this.messageNavigation.currentText);
        if (byText >= 0) {
          currentIndex = byText;
          hasExistingCursor = true;
        } else if (this.messageNavigation.currentIndex >= 0 && this.messageNavigation.currentIndex < messages.length) {
          currentIndex = this.messageNavigation.currentIndex;
          hasExistingCursor = true;
        }
      }
    }

    if (currentIndex < 0 || currentIndex >= messages.length) {
      currentIndex = latestIndex;
      hasExistingCursor = false;
    }

    let targetIndex = currentIndex;
    if (!hasExistingCursor) {
      if (direction === 'back') {
        targetIndex = 0;
      } else if (direction === 'forward') {
        targetIndex = latestIndex;
      }
    } else if (direction === 'back') {
      targetIndex = Math.max(0, currentIndex - 1);
    } else if (direction === 'forward') {
      targetIndex = Math.min(messages.length - 1, currentIndex + 1);
    }

    if (targetIndex < 0 || targetIndex >= messages.length) {
      targetIndex = currentIndex;
    }

    const target = messages[targetIndex] || messages[messages.length - 1];
    this.messageNavigation = {
      chatKey,
      currentIndex: targetIndex,
      currentText: target.text,
      initializedByNavigation: true,
    };

    return {
      transcription: '',
      response: target.text,
      action: 'speak_response',
    };
  }
}

const bridge = new VoiceBridge(BROWSER_PORT);

// Forward raw audio bytes to local Whisper server
function transcribeAudio(audioBuffer) {
  return new Promise((resolve, reject) => {
    const startedAt = Date.now();
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
        const durationMs = Date.now() - startedAt;
        try {
          const parsed = JSON.parse(data);
          const result = {
            text: parsed.text || '',
            durationMs,
            backend: parsed.backend || '',
            model: parsed.model || '',
            timings: parsed.timings || {},
          };
          if ((res.statusCode || 500) >= 400) {
            const error = new Error(parsed.error || `Whisper service failed with HTTP ${res.statusCode}`);
            error.timings = {
              whisper_request_ms: durationMs,
              ...result.timings,
            };
            reject(error);
            return;
          }
          resolve({
            ...result,
          });
        } catch (err) {
          const parseError = new Error(`Failed to parse response: ${data}`);
          parseError.timings = { whisper_request_ms: durationMs };
          reject(parseError);
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

async function processVoiceCommandAudio(audioBuffer, initialTimings = {}) {
  const processStartedAt = Date.now();
  const timings = {
    bridge_upload_body_read_ms: initialTimings.bridge_upload_body_read_ms ?? -1,
    whisper_request_ms: -1,
    upload_body_read_ms: -1,
    server_transcribe_ms: -1,
    postprocess_ms: -1,
    server_total_ms: -1,
    transcribe_ms: -1,
    ai_ms: 0,
    total_process_ms: 0,
  };
  try {
    const transcriptionResult = await transcribeAudio(audioBuffer);
    Object.assign(timings, transcriptionResult.timings || {});
    timings.whisper_request_ms = transcriptionResult.durationMs;
    timings.transcribe_ms = timings.server_transcribe_ms >= 0
      ? timings.server_transcribe_ms
      : transcriptionResult.durationMs;
    const transcribedText = transcriptionResult.text;
    console.log(`Whisper output: "${transcribedText}"`);

  if (!transcribedText || transcribedText.trim().length === 0) {
    timings.total_process_ms = Date.now() - processStartedAt;
    return {
      transcription: '',
      response: "I didn't hear anything. Please try again.",
      action: 'speak_response',
      timings,
    };
  }

  const normalized = normalizeCommandText(transcribedText);

  if (isRepeatCommand(normalized)) {
    timings.total_process_ms = Date.now() - processStartedAt;
    return {
      transcription: transcribedText,
      response: 'Repeating the last assistant message.',
      action: 'repeat_last_response',
      timings,
    };
  }

  if (isStopCommand(normalized)) {
    timings.total_process_ms = Date.now() - processStartedAt;
    return {
      transcription: transcribedText,
      response: 'Stopping speech.',
      action: 'stop_speaking',
      timings,
    };
  }

  if (isRewindCommand(normalized)) {
    timings.total_process_ms = Date.now() - processStartedAt;
    return {
      transcription: transcribedText,
      response: 'Rewinding speech by 10 seconds.',
      action: 'rewind_speaking',
      timings,
    };
  }

  if (normalized === 'open a new chat' || normalized === 'new chat' || normalized === 'open new chat') {
    await bridge.startNewChat();
    timings.total_process_ms = Date.now() - processStartedAt;
    return {
      transcription: transcribedText,
      response: 'Opened a new chat.',
      action: 'speak_response',
      timings,
    };
  }

  const openChatMatch = normalized.match(/^(?:open chat|navigate to chat|switch to chat) (.+)$/);
  if (openChatMatch) {
    const targetTitle = openChatMatch[1].trim();
    console.log(`Attempting to switch to chat: "${targetTitle}"`);
    const chats = await bridge.listRecentChats();
    const found = chats.find(c => c.title.toLowerCase().includes(targetTitle));
    if (found) {
      await bridge.selectChat(found.clickIndex);
      timings.total_process_ms = Date.now() - processStartedAt;
      return {
        transcription: transcribedText,
        response: `Navigated to chat: ${found.title}`,
        action: 'speak_response',
        timings,
      };
    }
    timings.total_process_ms = Date.now() - processStartedAt;
    return {
      transcription: transcribedText,
      response: `Could not find a chat matching "${targetTitle}".`,
      action: 'speak_response',
      timings,
    };
  }

    const aiStartedAt = Date.now();
    const aiResponse = await bridge.ask(transcribedText);
    timings.ai_ms = Date.now() - aiStartedAt;
    timings.total_process_ms = Date.now() - processStartedAt;
    return {
      transcription: transcribedText,
      response: aiResponse,
      action: 'speak_response',
      timings,
    };
  } catch (error) {
    Object.assign(timings, error && error.timings ? error.timings : {});
    timings.total_process_ms = Date.now() - processStartedAt;
    const timedError = error instanceof Error ? error : new Error(String(error));
    timedError.timings = timings;
    console.error('Voice command timing outcome=failed', timings);
    throw timedError;
  }
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
  pruneExpiredVoiceCommandJobs();
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
    const eventsMatch = urlPath.match(/^\/voice-command-events\/([a-f0-9-]+)$/i);
    if (eventsMatch) {
      const jobId = eventsMatch[1];
      const job = voiceCommandJobs.get(jobId);
      if (!job) {
        writeJson(res, 404, { error: 'Unknown request id' });
        return;
      }
      attachVoiceCommandJobListener(job, req, res);
      return;
    }
    const resultMatch = urlPath.match(/^\/voice-command-result\/([a-f0-9-]+)$/i);
    if (resultMatch) {
      const jobId = resultMatch[1];
      const job = voiceCommandJobs.get(jobId);
      if (!job) {
        writeJson(res, 404, { error: 'Unknown request id' });
        return;
      }
      if (job.status === 'processing') {
        writeJson(res, 202, formatVoiceCommandJobPayload(job));
        return;
      }
      if (job.status === 'failed') {
        writeJson(res, 500, formatVoiceCommandJobPayload(job));
        return;
      }
      writeJson(res, 200, formatVoiceCommandJobPayload(job));
      return;
    }
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
    const uploadStartedAt = Date.now();
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', async () => {
      try {
        const audioBuffer = Buffer.concat(chunks);
        const bridgeUploadBodyReadMs = Date.now() - uploadStartedAt;
        console.log(
          `Received voice recording: ${audioBuffer.length} bytes ` +
          `bridge_upload_body_read_ms=${bridgeUploadBodyReadMs}.`
        );
        const jobId = createVoiceCommandJob();
        writeJson(res, 202, {
          request_id: jobId,
          status: 'accepted',
        });

        processVoiceCommandAudio(audioBuffer, {
          bridge_upload_body_read_ms: bridgeUploadBodyReadMs,
        })
          .then((result) => completeVoiceCommandJob(jobId, result))
          .catch((err) => {
            console.error('Error processing command:', err);
            failVoiceCommandJob(jobId, err);
          });
      } catch (err) {
        console.error('Error processing command:', err);
        writeJson(res, 500, { error: err.message });
      }
    });
  } else if (req.method === 'POST' && urlPath === '/local-command') {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', async () => {
      try {
        const bodyText = Buffer.concat(chunks).toString('utf8');
        const payload = bodyText ? JSON.parse(bodyText) : {};
        const action = typeof payload.action === 'string' ? payload.action : '';
        const result = await handleLocalBridgeCommand(action, payload);
        writeJson(res, 200, result);
      } catch (err) {
        console.error('Error processing local command:', err);
        writeJson(res, 500, {
          transcription: '',
          response: `Local command failed: ${err.message}`,
          action: 'speak_response',
        });
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
