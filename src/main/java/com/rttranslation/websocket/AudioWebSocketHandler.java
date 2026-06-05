package com.rttranslation.websocket;

import com.rttranslation.model.SubtitleEntry;
import com.rttranslation.model.TranslationResult;
import com.rttranslation.service.AsrService;
import com.rttranslation.service.SessionService;
import com.rttranslation.service.SseService;
import com.rttranslation.service.TranslationService;
import com.rttranslation.model.SseEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * 音频 WebSocket 处理器
 *
 * <p>驱动 ASR → 流式翻译 → SSE 推送的完整管线。</p>
 *
 * <p>修复要点：
 * <ul>
 *   <li>#2 sentenceId 统一由 TranslationService.generateSentenceId 生成</li>
 *   <li>#3 token 累积只依赖 streamTranslate 返回值，handler 不再二次 StringBuilder</li>
 *   <li>#6 线程池改为有界（核心4、最大16、队列100）</li>
 *   <li>#1 final 翻译完成后异步触发回顾纠错</li>
 * </ul>
 */
@Slf4j
@Component
public class AudioWebSocketHandler extends BinaryWebSocketHandler {

    private final AsrService asrService;
    private final TranslationService translationService;
    private final SessionService sessionService;
    private final SseService sseService;

    private final Map<String, AsrService.DeepgramConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> previewFutures = new ConcurrentHashMap<>();
    private final Map<String, String> lastPreviewText = new ConcurrentHashMap<>();

    // #6 有界线程池：核心4线程，最大16线程，队列100，拒绝策略丢弃最旧
    private final ExecutorService previewExecutor = new ThreadPoolExecutor(
            4, 16, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            r -> { Thread t = new Thread(r, "preview-pool"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.DiscardOldestPolicy()
    );
    private final ExecutorService translateExecutor = new ThreadPoolExecutor(
            4, 16, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            r -> { Thread t = new Thread(r, "translate-pool"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public AudioWebSocketHandler(AsrService asrService,
                                  TranslationService translationService,
                                  SessionService sessionService,
                                  SseService sseService) {
        this.asrService = asrService;
        this.translationService = translationService;
        this.sessionService = sessionService;
        this.sseService = sseService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = extractSessionId(session);
        if (sessionId == null) {
            session.close(CloseStatus.BAD_DATA.withReason("无法解析 sessionId"));
            return;
        }

        Optional<Map<String, String>> sessionMeta = sessionService.getSession(sessionId);
        if (sessionMeta.isEmpty()) {
            session.close(CloseStatus.BAD_DATA.withReason("会话不存在"));
            return;
        }

        String sourceLang = sessionMeta.get().getOrDefault("sourceLang", "en");

        AsrService.DeepgramConnection dgConn = asrService.start(sessionId, sourceLang,
                (type, text) -> {
                    try {
                        handleAsrResult(sessionId, sourceLang, type, text);
                    } catch (Exception e) {
                        log.error("[{}] 处理 ASR 结果异常: {}", sessionId, e.getMessage(), e);
                    }
                });

        connections.put(sessionId, dgConn);
        log.info("[{}] WebSocket 已连接，ASR 引擎已启动", sessionId);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        String sessionId = extractSessionId(session);
        if (sessionId == null) return;

        AsrService.DeepgramConnection dgConn = connections.get(sessionId);
        if (dgConn != null) {
            dgConn.sendAudio(message.getPayload().array());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = extractSessionId(session);
        if (sessionId == null) return;

        AsrService.DeepgramConnection dgConn = connections.remove(sessionId);
        if (dgConn != null) dgConn.close();

        Future<?> previewFuture = previewFutures.remove(sessionId);
        if (previewFuture != null) previewFuture.cancel(true);

        lastPreviewText.remove(sessionId);
        translationService.cleanup(sessionId);

        log.info("[{}] WebSocket 已断开: {}", sessionId, status);
    }

    /**
     * 核心：处理 ASR 识别结果
     */
    private void handleAsrResult(String sessionId, String sourceLang,
                                  String type, String text) {

        if ("interim".equals(type)) {
            // 推送英文原文预览
            sseService.publish(sessionId, SseEvent.interim(text));

            // 去重：相同文本不重复预翻译
            if (text.equals(lastPreviewText.get(sessionId))) return;
            lastPreviewText.put(sessionId, text);

            // 取消之前未完成的预翻译
            Future<?> oldFuture = previewFutures.remove(sessionId);
            if (oldFuture != null) oldFuture.cancel(true);

            Future<?> future = previewExecutor.submit(() -> {
                try {
                    String preview = translationService.previewTranslate(sessionId, sourceLang, text);
                    if (!preview.isBlank()) {
                        sseService.publish(sessionId, SseEvent.interimTranslation(preview));
                    }
                } catch (Exception e) {
                    log.debug("[{}] 预翻译被取消或失败: {}", sessionId, e.getMessage());
                }
            });
            previewFutures.put(sessionId, future);
            return;
        }

        if ("final".equals(type)) {
            // 取消预翻译
            Future<?> previewFuture = previewFutures.remove(sessionId);
            if (previewFuture != null) previewFuture.cancel(true);
            lastPreviewText.remove(sessionId);

            // #2 统一由 TranslationService 生成 sentenceId
            String sentenceId = translationService.generateSentenceId(sessionId);
            long timestamp = System.currentTimeMillis();

            // 推送 translation_start
            sseService.publish(sessionId, SseEvent.translationStart(sentenceId, text, timestamp));

            // 流式翻译
            translateExecutor.submit(() -> {
                try {
                    // #3 token 累积完全由 streamTranslate 内部管理
                    //    handler 只做 token 推送，不再二次 StringBuilder
                    String translation = translationService.streamTranslate(
                            sessionId, sourceLang, text,
                            token -> sseService.publish(sessionId, SseEvent.token(sentenceId, token))
                    );

                    // 推送 translation_end（携带完整译文做最终确认）
                    sseService.publish(sessionId, SseEvent.translationEnd(sentenceId, translation));

                    // 写入历史
                    sessionService.appendTranscript(sessionId, text);
                    sessionService.appendTranslation(sessionId, SubtitleEntry.builder()
                            .sentenceId(sentenceId)
                            .original(text)
                            .translation(translation)
                            .timestamp(timestamp)
                            .build());

                    // #1 检查是否需要触发回顾纠错
                    if (translationService.shouldReview(sessionId)) {
                        previewExecutor.submit(() -> {
                            try {
                                List<TranslationResult.Correction> corrections =
                                        translationService.reviewAndCorrect(sessionId, sourceLang);
                                for (TranslationResult.Correction corr : corrections) {
                                    sseService.publish(sessionId, SseEvent.correction(
                                            corr.getSentenceId(),
                                            corr.getPreviousTranslation(),
                                            corr.getCorrectedTranslation(),
                                            corr.getReason()
                                    ));
                                }
                            } catch (Exception e) {
                                log.error("[{}] 回顾纠错异常: {}", sessionId, e.getMessage());
                            }
                        });
                    }

                } catch (Exception e) {
                    log.error("[{}] 流式翻译失败: {}", sessionId, e.getMessage());
                    sseService.publish(sessionId, SseEvent.error("翻译失败: " + e.getMessage()));
                }
            });
        }
    }

    private String extractSessionId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) return null;
        String path = uri.getPath();
        String[] parts = path.split("/");
        return parts.length >= 4 ? parts[parts.length - 1] : null;
    }
}
