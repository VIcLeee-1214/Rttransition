package com.rttranslation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rttranslation.model.SseEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;

/**
 * SSE 事件推送服务
 *
 * <p>管理每个会话的 SSE 订阅者（SseEmitter），
 * 并提供统一的事件推送方法。翻译/纠错结果通过此服务推送给前端。</p>
 */
@Slf4j
@Service
public class SseService {

    /** { sessionId -> [SseEmitter, ...] } */
    private final Map<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 为指定会话注册一个 SSE 订阅者
     */
    public SseEmitter subscribe(String sessionId) {
        // 超时设为 24 小时（同传场景可能持续很久）
        SseEmitter emitter = new SseEmitter(24 * 60 * 60 * 1000L);

        subscribers.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        // 连接关闭或超时时自动移除
        Runnable cleanup = () -> {
            subscribers.getOrDefault(sessionId, List.of()).remove(emitter);
            log.debug("[{}] SSE 订阅者断开", sessionId);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        log.info("[{}] SSE 订阅者连接，当前 {} 个", sessionId,
                subscribers.get(sessionId).size());
        return emitter;
    }

    /**
     * 向指定会话的所有订阅者推送事件
     */
    public void publish(String sessionId, SseEvent event) {
        List<SseEmitter> emitters = subscribers.get(sessionId);
        if (emitters == null || emitters.isEmpty()) return;

        String eventType = event.getType();
        String data;
        try {
            data = mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("序列化 SSE 事件失败: {}", e.getMessage());
            return;
        }

        List<SseEmitter> dead = new java.util.ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventType)
                        .data(data));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }

    /**
     * 会话结束时关闭所有订阅者
     */
    public void closeAll(String sessionId) {
        List<SseEmitter> emitters = subscribers.remove(sessionId);
        if (emitters != null) {
            emitters.forEach(SseEmitter::complete);
        }
    }
}
