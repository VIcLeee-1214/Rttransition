package com.rttranslation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rttranslation.config.IflytekProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * ASR 引擎 - 封装讯飞实时流式语音识别
 *
 * <p>每个同传会话对应一个 AsrService 实例，内部维护一条到讯飞的 WebSocket 连接。
 * 前端通过 Spring WebSocket 将 PCM 音频帧转发到此服务，
 * 此服务将音频以 Base64 JSON 帧发送给讯飞并通过回调返回识别结果。</p>
 */
@Slf4j
@Service
public class AsrService {

    private final IflytekProperties config;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public AsrService(IflytekProperties config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 开启一条到讯飞的流式识别连接
     *
     * @param sessionId  会话 ID（用于日志）
     * @param sourceLang 源语言代码（保留兼容，当前使用配置的 accent）
     * @param onResult   回调函数: (type, text)
     *                   type = "interim" 表示中间结果（不断更新）
     *                   type = "final"   表示一句话的最终结果
     * @return IflytekConnection 用于发送音频和关闭连接
     */
    public IflytekConnection start(String sessionId, String sourceLang,
                                    BiConsumer<String, String> onResult) {
        if (config.getApiKey() == null || config.getApiKey().isBlank()
                || config.getApiSecret() == null || config.getApiSecret().isBlank()) {
            log.error("[{}] 讯飞 API 未配置！请设置 IFLYTEK_APP_ID / IFLYTEK_API_KEY / IFLYTEK_API_SECRET", sessionId);
        }

        String authUrl = buildAuthUrl();
        if (authUrl == null) {
            log.error("[{}] 讯飞鉴权 URL 生成失败", sessionId);
            return null;
        }

        Request request = new Request.Builder().url(authUrl).build();
        IflytekConnection connection = new IflytekConnection(httpClient, request, sessionId, onResult);
        connection.connect();
        return connection;
    }

    // ================== 讯飞鉴权 ==================

    private String buildAuthUrl() {
        try {
            URL url = new URL("https://iat-api.xfyun.cn/v2/iat");
            String date = rfc1123Date();

            String signatureOrigin = "host: " + url.getHost() + "\n"
                    + "date: " + date + "\n"
                    + "GET " + url.getPath() + " HTTP/1.1";

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.getApiSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = Base64.getEncoder().encodeToString(
                    mac.doFinal(signatureOrigin.getBytes(StandardCharsets.UTF_8)));

            String authorizationOrigin = "api_key=\"" + config.getApiKey() + "\", "
                    + "algorithm=\"hmac-sha256\", "
                    + "headers=\"host date request-line\", "
                    + "signature=\"" + signature + "\"";
            String authorization = Base64.getEncoder().encodeToString(
                    authorizationOrigin.getBytes(StandardCharsets.UTF_8));

            return url + "?authorization=" + authorization
                    + "&date=" + java.net.URLEncoder.encode(date, "UTF-8")
                    + "&host=" + url.getHost();
        } catch (Exception e) {
            log.error("生成讯飞鉴权 URL 失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private static String rfc1123Date() {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        return sdf.format(new Date());
    }

    // ================== 讯飞 WebSocket 连接 ==================

    /**
     * 封装一条讯飞 WebSocket 连接，支持自动重连和音频缓冲
     */
    public class IflytekConnection {
        private volatile WebSocket currentWs;
        private final AtomicBoolean connected = new AtomicBoolean(false);
        private int reconnectAttempts = 0;
        private final int maxReconnectAttempts = 3;
        private final OkHttpClient httpClient;
        private final Request originalRequest;
        private final String sessionId;
        private final BiConsumer<String, String> onResult;

        /** 音频帧队列（断连时缓冲） */
        private final LinkedBlockingDeque<byte[]> audioBuffer = new LinkedBlockingDeque<>(500);

        /** 帧状态管理 */
        private volatile boolean firstFrameSent = false;
        private volatile int frameStatus = 0; // 0=首帧, 1=中间帧

        /** 识别结果累积：sn -> text */
        private final TreeMap<Integer, String> segmentMap = new TreeMap<>();

        private volatile long lastBufferWarnTime = 0;
        private volatile int droppedFrames = 0;

        private final WebSocketListener listener;

        IflytekConnection(OkHttpClient httpClient, Request request, String sessionId,
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
                    firstFrameSent = false;
                    frameStatus = 0;
                    log.info("[{}] 讯飞连接已建立", sessionId);
                    flushBuffer();
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    try {
                        JsonNode root = mapper.readTree(text);

                        int code = root.path("code").asInt(-1);
                        if (code != 0) {
                            if (code == 1014) {
                                log.debug("[{}] 讯飞音频处理完成", sessionId);
                            } else {
                                log.error("[{}] 讯飞返回错误 code={}: {}", sessionId, code,
                                        root.path("message").asText(""));
                            }
                            return;
                        }

                        JsonNode data = root.path("data");
                        if (data.isMissingNode()) return;

                        int status = data.path("status").asInt(0);
                        JsonNode result = data.path("result");

                        // 解析 ws 数组，累积/更新各 sn 段的文字
                        JsonNode wsArray = result.path("ws");
                        if (wsArray.isArray()) {
                            for (JsonNode wsNode : wsArray) {
                                int sn = wsNode.path("sn").asInt();
                                StringBuilder sb = new StringBuilder();
                                JsonNode cwArray = wsNode.path("cw");
                                if (cwArray.isArray()) {
                                    for (JsonNode cw : cwArray) {
                                        sb.append(cw.path("w").asText(""));
                                    }
                                }

                                // pgs=rpl 时清除被替换的 sn 范围
                                String pgs = wsNode.path("pgs").asText("apd");
                                if ("rpl".equals(pgs)) {
                                    JsonNode rg = wsNode.path("rg");
                                    if (rg.isArray() && rg.size() >= 2) {
                                        int from = rg.get(0).asInt();
                                        int to = rg.get(1).asInt();
                                        for (int i = from; i <= to; i++) {
                                            segmentMap.remove(i);
                                        }
                                    }
                                }

                                segmentMap.put(sn, sb.toString());
                            }
                        }

                        // 拼接当前完整句子
                        String sentenceText = String.join("", segmentMap.values());
                        if (sentenceText.isBlank()) return;

                        if (status == 0) {
                            // 中间结果
                            onResult.accept("interim", sentenceText);
                        } else {
                            // status == 1：一句话识别完成
                            onResult.accept("final", sentenceText);
                            segmentMap.clear(); // 清空，准备下一句
                        }

                    } catch (Exception e) {
                        log.error("[{}] 解析讯飞响应失败: {}", sessionId, e.getMessage());
                    }
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    log.error("[{}] 讯飞连接异常: {}", sessionId, t.getMessage());
                    connected.set(false);
                    reconnect();
                }

                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    log.info("[{}] 讯飞连接关闭: {} - {}", sessionId, code, reason);
                    connected.set(false);
                    if (code != 1000 && code != 1005) {
                        reconnect();
                    }
                }
            };
        }

        void connect() {
            currentWs = httpClient.newWebSocket(originalRequest, listener);
        }

        private void reconnect() {
            if (reconnectAttempts >= maxReconnectAttempts) {
                log.error("[{}] 讯飞重连次数已达上限 ({}), 放弃重连", sessionId, maxReconnectAttempts);
                return;
            }
            reconnectAttempts++;
            long delayMs = (long) Math.pow(2, reconnectAttempts - 1) * 1000;
            log.info("[{}] 第 {} 次重连，等待 {}ms", sessionId, reconnectAttempts, delayMs);
            try {
                TimeUnit.MILLISECONDS.sleep(delayMs);
                firstFrameSent = false;
                frameStatus = 0;
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
                    sendAudioFrame(data);
                }
            }
        }

        /**
         * 发送 PCM 音频帧到讯飞
         * <p>讯飞协议要求音频以 Base64 编码在 JSON 帧中发送。
         * 首帧包含音频参数，后续帧只包含音频数据。</p>
         */
        public void sendAudio(byte[] data) {
            if (connected.get()) {
                sendAudioFrame(data);
            } else {
                if (!audioBuffer.offerLast(data)) {
                    droppedFrames++;
                    long now = System.currentTimeMillis();
                    if (now - lastBufferWarnTime > 2000) {
                        log.warn("[{}] 音频缓冲区已满，已丢弃 {} 帧（讯飞可能未连接，请检查 API 配置）",
                                sessionId, droppedFrames);
                        lastBufferWarnTime = now;
                        droppedFrames = 0;
                    }
                }
            }
        }

        /**
         * 构造讯飞 JSON 帧并发送
         */
        private void sendAudioFrame(byte[] pcmData) {
            try {
                String audioBase64 = Base64.getEncoder().encodeToString(pcmData);
                Map<String, Object> frame = new LinkedHashMap<>();

                if (!firstFrameSent) {
                    // 首帧：包含 data + common + business
                    Map<String, Object> dataMap = new LinkedHashMap<>();
                    dataMap.put("status", 0);
                    dataMap.put("format", "audio/L16;rate=16000");
                    dataMap.put("encoding", "raw");
                    dataMap.put("audio", audioBase64);

                    Map<String, Object> common = new LinkedHashMap<>();
                    common.put("app_id", config.getAppId());

                    Map<String, Object> business = new LinkedHashMap<>();
                    business.put("domain", "iat");
                    business.put("language", "en_us");
                    business.put("accent", config.getAccent());
                    business.put("vad_eos", 300);
                    business.put("ptt", 1);
                    business.put("nbest", 1);
                    business.put("wbest", 1);
                    business.put("dwa", "wpgs");

                    frame.put("common", common);
                    frame.put("business", business);
                    frame.put("data", dataMap);

                    firstFrameSent = true;
                    frameStatus = 1;
                } else {
                    // 后续帧：只有 data
                    Map<String, Object> dataMap = new LinkedHashMap<>();
                    dataMap.put("status", frameStatus);
                    dataMap.put("format", "audio/L16;rate=16000");
                    dataMap.put("encoding", "raw");
                    dataMap.put("audio", audioBase64);
                    frame.put("data", dataMap);
                }

                String json = mapper.writeValueAsString(frame);
                currentWs.send(json);
            } catch (Exception e) {
                log.error("[{}] 发送音频帧失败: {}", sessionId, e.getMessage());
            }
        }

        /** 发送结束帧并关闭连接 */
        public void close() {
            if (currentWs != null) {
                try {
                    Map<String, Object> frame = new LinkedHashMap<>();
                    Map<String, Object> dataMap = new LinkedHashMap<>();
                    dataMap.put("status", 2);
                    dataMap.put("format", "audio/L16;rate=16000");
                    dataMap.put("encoding", "raw");
                    dataMap.put("audio", "");
                    frame.put("data", dataMap);
                    currentWs.send(mapper.writeValueAsString(frame));
                } catch (Exception e) {
                    log.debug("[{}] 发送结束帧异常: {}", sessionId, e.getMessage());
                }
                currentWs.close(1000, "session ended");
            }
            log.info("[{}] 讯飞连接已关闭", sessionId);
        }

        public boolean isConnected() {
            return connected.get();
        }
    }
}
