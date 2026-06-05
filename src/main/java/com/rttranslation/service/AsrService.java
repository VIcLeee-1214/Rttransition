package com.rttranslation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rttranslation.config.DeepgramProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.ByteString;
import org.springframework.stereotype.Service;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * ASR 引擎 - 封装 Deepgram Nova-3 流式语音识别
 *
 * <p>每个同传会话对应一个 AsrService 实例，内部维护一条到 Deepgram 的 WebSocket 连接。
 * 前端通过 Spring WebSocket 将 PCM 音频帧转发到此服务，
 * 此服务将音频发送给 Deepgram 并通过回调返回识别结果。</p>
 */
@Slf4j
@Service
public class AsrService {

    private final DeepgramProperties config;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public AsrService(DeepgramProperties config) {
        this.config = config;
        this.httpClient = new OkHttpClient();
    }

    /**
     * 开启一条到 Deepgram 的流式识别连接
     *
     * @param sessionId  会话 ID（用于日志）
     * @param sourceLang 源语言代码（如 en, ja）
     * @param onResult   回调函数: (type, text)
     *                   type = "interim" 表示中间结果（不断更新）
     *                   type = "final"   表示一句话的最终结果
     * @return DeepgramConnection 用于发送音频和关闭连接
     */
    public DeepgramConnection start(String sessionId, String sourceLang,
                                     BiConsumer<String, String> onResult) {
        // Deepgram WebSocket URL
        String url = String.format(
                "wss://api.deepgram.com/v1/listen?model=%s&language=%s" +
                        "&smart_format=true&punctuate=true&interim_results=true" +
                        "&endpointing=%d&vad_events=true",
                config.getModel(), sourceLang, config.getEndpointingMs()
        );

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Token " + config.getApiKey())
                .build();

        DeepgramConnection connection = new DeepgramConnection(httpClient, request, sessionId, onResult);
        connection.connect();
        return connection;
    }

    /**
     * 封装一条 Deepgram WebSocket 连接，支持自动重连和音频缓冲
     */
    public class DeepgramConnection {
        private volatile WebSocket currentWs;
        private final AtomicBoolean connected = new AtomicBoolean(false);
        private int reconnectAttempts = 0;
        private final int maxReconnectAttempts = 3;
        private final OkHttpClient httpClient;
        private final Request originalRequest;
        private final String sessionId;
        private final BiConsumer<String, String> onResult;
        private final LinkedBlockingDeque<byte[]> audioBuffer = new LinkedBlockingDeque<>(500);
        private final WebSocketListener listener;

        DeepgramConnection(OkHttpClient httpClient, Request request, String sessionId,
                           BiConsumer<String, String> onResult) {
            this.httpClient = httpClient;
            this.originalRequest = request;
            this.sessionId = sessionId;
            this.onResult = onResult;
            this.listener = createListener();
        }

        private WebSocketListener createListener() {
            return new WebSocketListener() {

                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    connected.set(true);
                    reconnectAttempts = 0;
                    log.info("[{}] Deepgram 连接已建立", sessionId);
                    flushBuffer();
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    try {
                        JsonNode root = mapper.readTree(text);
                        JsonNode channel = root.path("channel");
                        if (channel.isMissingNode()) return;

                        JsonNode alternatives = channel.path("alternatives");
                        if (!alternatives.isArray() || alternatives.isEmpty()) return;

                        String transcript = alternatives.get(0).path("transcript").asText("");
                        if (transcript.isBlank()) return;

                        boolean isFinal = root.path("is_final").asBoolean(false);
                        String type = isFinal ? "final" : "interim";

                        onResult.accept(type, transcript);
                    } catch (Exception e) {
                        log.error("[{}] 解析 Deepgram 响应失败: {}", sessionId, e.getMessage());
                    }
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    log.error("[{}] Deepgram 连接异常: {}", sessionId, t.getMessage());
                    connected.set(false);
                    reconnect();
                }

                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    log.info("[{}] Deepgram 连接关闭: {} - {}", sessionId, code, reason);
                    connected.set(false);
                    reconnect();
                }
            };
        }

        void connect() {
            currentWs = httpClient.newWebSocket(originalRequest, listener);
        }

        private void reconnect() {
            if (reconnectAttempts >= maxReconnectAttempts) {
                log.error("[{}] Deepgram 重连次数已达上限 ({}), 放弃重连", sessionId, maxReconnectAttempts);
                return;
            }
            reconnectAttempts++;
            long delayMs = (long) Math.pow(2, reconnectAttempts - 1) * 1000;
            log.info("[{}] 第 {} 次重连，等待 {}ms", sessionId, reconnectAttempts, delayMs);
            try {
                TimeUnit.MILLISECONDS.sleep(delayMs);
                currentWs = httpClient.newWebSocket(originalRequest, listener);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[{}] 重连等待被中断", sessionId);
            }
        }

        private void flushBuffer() {
            byte[] data;
            while ((data = audioBuffer.pollFirst()) != null) {
                if (connected.get()) {
                    currentWs.send(ByteString.of(data));
                }
            }
        }

        /** 发送 PCM 音频帧，断连时缓冲到队列 */
        public void sendAudio(byte[] data) {
            if (connected.get()) {
                currentWs.send(ByteString.of(data));
            } else {
                if (!audioBuffer.offerLast(data)) {
                    log.warn("[{}] 音频缓冲区已满，丢弃帧", sessionId);
                }
            }
        }

        /** 关闭连接 */
        public void close() {
            if (currentWs != null) {
                currentWs.close(1000, "session ended");
            }
            log.info("[{}] Deepgram 连接已关闭", sessionId);
        }

        public boolean isConnected() {
            return connected.get();
        }
    }
}
