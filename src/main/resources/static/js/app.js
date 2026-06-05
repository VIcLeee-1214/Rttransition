/**
 * AI 同声传译助手 - 前端主逻辑（流式翻译版）
 *
 * 三大模块:
 *   1. 麦克风采集 (Web Audio API → PCM)
 *   2. WebSocket 音频上行
 *   3. SSE 字幕下行 + 流式渲染
 *
 * 实时性优化:
 *   - interim 阶段: 显示英文原文 + gpt-4o-mini 预翻译的中文草稿（灰色）
 *   - final 阶段: 字幕逐 token 流出（像打字机效果），不再等整句翻译完
 */

// ========== DOM 元素 ==========
const $ = (id) => document.getElementById(id);
const btnStart = $('btnStart');
const btnStop = $('btnStop');
const sourceLangSelect = $('sourceLang');
const statusText = $('statusText');
const errorText = $('errorText');
const volumeFill = $('volumeFill');
const subtitleArea = $('subtitleArea');
const interimText = $('interimText');
const emptyHint = $('emptyHint');
const btnScrollBottom = $('btnScrollBottom');

// ========== 状态 ==========
let sessionId = null;
let audioContext = null;
let processor = null;
let mediaStream = null;
let ws = null;
let eventSource = null;
let autoScroll = true;
let wsReconnectAttempt = 0;

/** 当前正在流式输出的字幕 DOM 元素 */
let activeStreamingItem = null;
/** 当前流式输出的译文累积 */
let streamingTranslation = '';

// ========== 事件绑定 ==========
btnStart.addEventListener('click', handleStart);
btnStop.addEventListener('click', handleStop);

subtitleArea.addEventListener('scroll', () => {
    const { scrollTop, scrollHeight, clientHeight } = subtitleArea;
    autoScroll = scrollHeight - scrollTop - clientHeight < 30;
    btnScrollBottom.style.display = autoScroll ? 'none' : 'block';
});
btnScrollBottom.addEventListener('click', () => {
    subtitleArea.scrollTop = subtitleArea.scrollHeight;
    autoScroll = true;
    btnScrollBottom.style.display = 'none';
});


// ========== 开始同传 ==========
async function handleStart() {
    try {
        errorText.textContent = '';
        setStatus('正在创建会话...');

        const sourceLang = sourceLangSelect.value;
        const res = await fetch('/api/session', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sourceLang, targetLang: 'zh-CN' }),
        });
        if (!res.ok) throw new Error('创建会话失败: ' + res.statusText);
        const data = await res.json();
        sessionId = data.sessionId;

        await startAudioCapture();
        connectWebSocket(sessionId);
        connectSSE(sessionId);

        btnStart.disabled = true;
        btnStop.disabled = false;
        sourceLangSelect.disabled = true;
        emptyHint.style.display = 'none';
        setStatus('同传进行中');

    } catch (err) {
        errorText.textContent = err.message;
        setStatus('就绪');
    }
}


// ========== 停止同传 ==========
function handleStop() {
    stopAudioCapture();
    ws?.close();
    eventSource?.close();

    fetch('/api/session/' + sessionId, { method: 'DELETE' }).catch(() => {});
    sessionId = null;

    btnStart.disabled = false;
    btnStop.disabled = true;
    sourceLangSelect.disabled = false;
    setStatus('就绪');
    clearInterim();
}


// ========== 模块 1: 麦克风采集 ==========
async function startAudioCapture() {
    mediaStream = await navigator.mediaDevices.getUserMedia({
        audio: {
            channelCount: 1,
            sampleRate: 16000,
            echoCancellation: true,
            noiseSuppression: true,
        },
    });

    audioContext = new AudioContext({ sampleRate: 16000 });
    const source = audioContext.createMediaStreamSource(mediaStream);

    // AudioWorklet 替代已废弃的 ScriptProcessorNode
    // 使用 Blob URL 内联 worklet 代码，避免单独文件依赖
    const workletCode = `
        class AudioProcessor extends AudioWorkletProcessor {
            process(inputs) {
                const input = inputs[0];
                if (input && input[0]) {
                    const channelData = input[0];
                    // Float32 → Int16 PCM
                    const pcm = new Int16Array(channelData.length);
                    for (let i = 0; i < channelData.length; i++) {
                        const s = Math.max(-1, Math.min(1, channelData[i]));
                        pcm[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
                    }
                    this.port.postMessage(pcm.buffer, [pcm.buffer]);
                }
                return true;
            }
        }
        registerProcessor('audio-processor', AudioProcessor);
    `;
    const blob = new Blob([workletCode], { type: 'application/javascript' });
    const workletUrl = URL.createObjectURL(blob);

    await audioContext.audioWorklet.addModule(workletUrl);
    processor = new AudioWorkletNode(audioContext, 'audio-processor');

    processor.port.onmessage = (event) => {
        const pcmBuffer = event.data;

        // 计算音量 (RMS) - 从 PCM 数据反算
        const pcm = new Int16Array(pcmBuffer);
        let sum = 0;
        for (let i = 0; i < pcm.length; i++) {
            const v = pcm[i] / 32768;
            sum += v * v;
        }
        const rms = Math.sqrt(sum / pcm.length);
        const vol = Math.min(1, rms * 5);
        updateVolume(vol);

        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(pcmBuffer);
        }
    };

    source.connect(processor);
    processor.connect(audioContext.destination);
    URL.revokeObjectURL(workletUrl);
}

function stopAudioCapture() {
    if (processor) {
        processor.disconnect();
        processor = null;
    }
    mediaStream?.getTracks().forEach(t => t.stop());
    mediaStream = null;
    audioContext?.close();
    audioContext = null;
    updateVolume(0);
}

function updateVolume(level) {
    volumeFill.style.width = (level * 100) + '%';
    volumeFill.classList.toggle('high', level > 0.8);
}


// ========== 模块 2: WebSocket 音频上行 ==========
function connectWebSocket(sid) {
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = protocol + '//' + location.host + '/ws/audio/' + sid;

    ws = new WebSocket(url);
    ws.binaryType = 'arraybuffer';

    ws.onopen = () => {
        console.log('WebSocket 已连接');
        wsReconnectAttempt = 0; // 成功后重置
    };
    ws.onerror = (e) => console.error('WebSocket 错误:', e);

    ws.onclose = () => {
        const MAX_RETRIES = 10;
        if (sessionId && sid === sessionId && wsReconnectAttempt < MAX_RETRIES) {
            const delay = Math.min(1000 * Math.pow(2, wsReconnectAttempt), 10000);
            wsReconnectAttempt++;
            setStatus(`重连中 (${wsReconnectAttempt}/${MAX_RETRIES})...`);
            setTimeout(() => {
                if (sessionId === sid) connectWebSocket(sid);
            }, delay);
        } else if (wsReconnectAttempt >= MAX_RETRIES) {
            errorText.textContent = '连接已断开，无法重连。请刷新页面重试。';
            setStatus('连接失败');
        }
    };
}


// ========== 模块 3: SSE 字幕下行（流式） ==========
function connectSSE(sid) {
    eventSource = new EventSource('/api/stream/' + sid);

    // ---- interim: ASR 中间结果（英文原文预览） ----
    eventSource.addEventListener('interim', (event) => {
        const data = JSON.parse(event.data);
        updateInterimEnglish(data.text);
        scrollToBottom();
    });

    // ---- interim_translation: gpt-4o-mini 快速预翻译（中文草稿） ----
    eventSource.addEventListener('interim_translation', (event) => {
        const data = JSON.parse(event.data);
        updateInterimPreview(data.preview);
        scrollToBottom();
    });

    // ---- translation_start: 正式翻译开始，创建字幕占位 ----
    eventSource.addEventListener('translation_start', (event) => {
        const data = JSON.parse(event.data);
        clearInterim(); // 清除 interim 预览
        createStreamingItem(data);
        streamingTranslation = '';
        scrollToBottom();
    });

    // ---- token: 流式推送翻译 token（逐字出现） ----
    eventSource.addEventListener('token', (event) => {
        const data = JSON.parse(event.data);
        appendToken(data.token);
        scrollToBottom();
    });

    // ---- translation_end: 翻译完成，锁定字幕 ----
    eventSource.addEventListener('translation_end', (event) => {
        const data = JSON.parse(event.data);
        finalizeStreamingItem(data);
        speakTranslation(data.translation);
        activeStreamingItem = null;
        streamingTranslation = '';
    });

    // ---- translation: 兼容旧的一次性翻译事件 ----
    eventSource.addEventListener('translation', (event) => {
        const data = JSON.parse(event.data);
        clearInterim();
        addSubtitleItem(data);
        scrollToBottom();
    });

    // ---- correction: 纠错修正 ----
    eventSource.addEventListener('correction', (event) => {
        const data = JSON.parse(event.data);
        applyCorrection(data);
    });

    // ---- error ----
    eventSource.addEventListener('error', (event) => {
        if (event.data) {
            try {
                const data = JSON.parse(event.data);
                errorText.textContent = data.message || '未知错误';
            } catch (e) {
                // EventSource 原生 error 事件（重连中），忽略
            }
        }
    });

    eventSource.onerror = () => {
        console.warn('SSE 连接中断，正在重连...');
    };
}


// ========== Interim 渲染 ==========

/** 更新 interim 英文原文预览 */
function updateInterimEnglish(text) {
    interimText.innerHTML = `
        <div class="interim-original">${escapeHtml(text)}</div>
        <div class="interim-preview" id="interimPreview"></div>
    `;
}

/** 更新 interim 中文预翻译 */
function updateInterimPreview(preview) {
    const el = document.getElementById('interimPreview');
    if (el) {
        el.textContent = preview;
    }
}

/** 清除 interim 区域 */
function clearInterim() {
    interimText.innerHTML = '';
}


// ========== 流式字幕渲染 ==========

/** 创建流式字幕占位（translation_start 时调用） */
function createStreamingItem(data) {
    const div = document.createElement('div');
    div.className = 'subtitle-item streaming';
    div.dataset.sentenceId = data.sentenceId;

    const time = new Date(data.timestamp).toLocaleTimeString('zh-CN', {
        hour: '2-digit', minute: '2-digit', second: '2-digit',
    });

    div.innerHTML = `
        <div class="subtitle-time">${time}</div>
        <div class="subtitle-original">${escapeHtml(data.original)}</div>
        <div class="subtitle-translation streaming-text"></div>
    `;

    subtitleArea.insertBefore(div, interimText);
    activeStreamingItem = div;
}

/** 追加 token 到当前流式字幕 */
function appendToken(tokenText) {
    if (!activeStreamingItem) return;
    const el = activeStreamingItem.querySelector('.streaming-text');
    if (el) {
        streamingTranslation += tokenText;
        el.textContent = streamingTranslation;
    }
}

/** 流式完成，锁定字幕样式 */
function finalizeStreamingItem(data) {
    if (!activeStreamingItem) return;
    activeStreamingItem.classList.remove('streaming');
    activeStreamingItem.classList.add('confirmed');

    // 用最终的完整译文覆盖（以防 token 拼接有细微差异）
    const el = activeStreamingItem.querySelector('.subtitle-translation');
    if (el && data.translation) {
        el.textContent = data.translation;
    }
}


// ========== 完整字幕条目（兼容旧事件） ==========
function addSubtitleItem(data) {
    const div = document.createElement('div');
    div.className = 'subtitle-item confirmed';
    div.dataset.sentenceId = data.sentenceId;

    const time = new Date(data.timestamp).toLocaleTimeString('zh-CN', {
        hour: '2-digit', minute: '2-digit', second: '2-digit',
    });

    div.innerHTML = `
        <div class="subtitle-time">${time}</div>
        <div class="subtitle-original">${escapeHtml(data.original)}</div>
        <div class="subtitle-translation">${escapeHtml(data.translation)}</div>
    `;

    subtitleArea.insertBefore(div, interimText);
}

function applyCorrection(data) {
    const item = subtitleArea.querySelector(
        `.subtitle-item[data-sentence-id="${data.sentenceId}"]`
    );
    if (!item) return;

    item.classList.add('corrected');
    const translationEl = item.querySelector('.subtitle-translation');
    const timeEl = item.querySelector('.subtitle-time');

    timeEl.innerHTML += `
        <span class="correction-badge">[已修正]</span>
        <span class="correction-old">${escapeHtml(data.previousTranslation)}</span>
    `;
    translationEl.textContent = data.correctedTranslation;
}

function scrollToBottom() {
    if (autoScroll) {
        subtitleArea.scrollTop = subtitleArea.scrollHeight;
    }
}


// ========== TTS 语音播报 ==========
let ttsEnabled = false;

function toggleTTS() {
    ttsEnabled = !ttsEnabled;
    const btn = document.getElementById('btnTTS');
    if (btn) {
        btn.textContent = ttsEnabled ? '🔊 语音开' : '🔇 语音关';
        btn.classList.toggle('active', ttsEnabled);
    }
    if (!ttsEnabled) {
        window.speechSynthesis.cancel(); // 关闭时停止当前播报
    }
}

function speakTranslation(text) {
    if (!ttsEnabled) return;
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = 'zh-CN';
    utterance.rate = 1.1; // 略快于正常，跟上字幕节奏
    window.speechSynthesis.speak(utterance);
}

// ========== 工具函数 ==========
function setStatus(text) {
    statusText.textContent = text;
}

function escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}
