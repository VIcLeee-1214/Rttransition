package com.rttranslation.service;

import com.rttranslation.model.SessionConfig;
import com.rttranslation.model.SubtitleEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 会话上下文管理 - 基于 Redis 存储会话状态、识别历史、翻译历史和术语表
 */
@Slf4j
@Service
public class SessionService {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    public SessionService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ==================== 会话 CRUD ====================

    /**
     * 创建会话，返回 sessionId
     */
    public String createSession(SessionConfig config) {
        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try {
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("sourceLang", config.getSourceLang());
            meta.put("targetLang", config.getTargetLang());
            meta.put("createdAt", Instant.now().toString());
            redis.opsForValue().set(metaKey(sessionId), mapper.writeValueAsString(meta));
        } catch (JsonProcessingException e) {
            log.error("序列化会话元信息失败: {}", e.getMessage());
        }
        log.info("创建会话: {}", sessionId);
        return sessionId;
    }

    /**
     * 获取会话元信息
     */
    public Optional<Map<String, String>> getSession(String sessionId) {
        String raw = redis.opsForValue().get(metaKey(sessionId));
        if (raw == null) return Optional.empty();
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> map = mapper.readValue(raw, Map.class);
            return Optional.of(map);
        } catch (JsonProcessingException e) {
            log.error("解析会话元信息失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 删除会话及其所有关联数据
     */
    public void deleteSession(String sessionId) {
        Set<String> keys = new HashSet<>();
        try (var cursor = redis.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match("session:" + sessionId + ":*")
                        .build())) {
            cursor.forEachRemaining(keys::add);
        }
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
        log.info("删除会话: {}，清理了 {} 个 key", sessionId, keys.size());
    }

    // ==================== 识别原文历史 ====================

    public void appendTranscript(String sessionId, String text) {
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("text", text);
            entry.put("timestamp", System.currentTimeMillis());
            redis.opsForList().rightPush(transcriptKey(sessionId), mapper.writeValueAsString(entry));
        } catch (JsonProcessingException e) {
            log.error("序列化识别原文失败: {}", e.getMessage());
        }
    }

    public List<String> getTranscriptHistory(String sessionId, int limit) {
        String key = transcriptKey(sessionId);
        Long size = redis.opsForList().size(key);
        if (size == null || size == 0) return Collections.emptyList();

        long start = Math.max(0, size - limit);
        List<String> items = redis.opsForList().range(key, start, -1);
        return items != null ? items : Collections.emptyList();
    }

    // ==================== 翻译历史 ====================

    public void appendTranslation(String sessionId, SubtitleEntry entry) {
        try {
            redis.opsForList().rightPush(translationKey(sessionId), mapper.writeValueAsString(entry));
        } catch (JsonProcessingException e) {
            log.error("序列化翻译结果失败: {}", e.getMessage());
        }
    }

    public List<SubtitleEntry> getTranslationHistory(String sessionId, int limit) {
        String key = translationKey(sessionId);
        Long size = redis.opsForList().size(key);
        if (size == null || size == 0) return Collections.emptyList();

        long start = Math.max(0, size - limit);
        List<String> items = redis.opsForList().range(key, start, -1);
        if (items == null) return Collections.emptyList();

        return items.stream()
                .map(json -> {
                    try {
                        return mapper.readValue(json, SubtitleEntry.class);
                    } catch (JsonProcessingException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // ==================== 动态术语表 ====================

    @SuppressWarnings("unchecked")
    public Map<String, String> getGlossary(String sessionId) {
        String raw = redis.opsForValue().get(glossaryKey(sessionId));
        if (raw == null) return new HashMap<>();
        try {
            return mapper.readValue(raw, Map.class);
        } catch (JsonProcessingException e) {
            return new HashMap<>();
        }
    }

    public void updateGlossary(String sessionId, Map<String, String> newTerms) {
        Map<String, String> current = getGlossary(sessionId);
        current.putAll(newTerms);
        try {
            redis.opsForValue().set(glossaryKey(sessionId), mapper.writeValueAsString(current));
        } catch (JsonProcessingException e) {
            log.error("保存术语表失败: {}", e.getMessage());
        }
    }

    // ==================== Redis Key 工具 ====================

    private String metaKey(String sessionId) {
        return "session:" + sessionId + ":meta";
    }

    private String transcriptKey(String sessionId) {
        return "session:" + sessionId + ":transcript";
    }

    private String translationKey(String sessionId) {
        return "session:" + sessionId + ":translations";
    }

    private String glossaryKey(String sessionId) {
        return "session:" + sessionId + ":glossary";
    }

}
