package com.rttranslation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rttranslation.model.SessionConfig;
import com.rttranslation.model.SubtitleEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private ListOperations<String, String> listOps;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForList()).thenReturn(listOps);
        sessionService = new SessionService(redis);
    }

    @Test
    void createSession_shouldStoreMetaInRedis() {
        SessionConfig config = new SessionConfig("en", "zh-CN");
        String sessionId = sessionService.createSession(config);

        assertNotNull(sessionId);
        assertEquals(12, sessionId.length());
        verify(valueOps).set(eq("session:" + sessionId + ":meta"), anyString());
    }

    @Test
    void getSession_existingSession_shouldReturnMeta() {
        when(valueOps.get("session:abc123:meta"))
                .thenReturn("{\"sourceLang\":\"en\",\"targetLang\":\"zh-CN\",\"createdAt\":\"2024-01-01T00:00:00Z\"}");

        Optional<Map<String, String>> result = sessionService.getSession("abc123");

        assertTrue(result.isPresent());
        assertEquals("en", result.get().get("sourceLang"));
    }

    @Test
    void getSession_nonExisting_shouldReturnEmpty() {
        when(valueOps.get("session:nonexist:meta")).thenReturn(null);

        Optional<Map<String, String>> result = sessionService.getSession("nonexist");

        assertTrue(result.isEmpty());
    }

    @Test
    void appendTranscript_shouldUseJacksonSerialization() {
        sessionService.appendTranscript("abc123", "Hello \"world\"\nwith newline");

        verify(listOps).rightPush(eq("session:abc123:transcript"), argThat(json ->
                json.contains("Hello") && json.contains("world") && !json.contains("\n")
        ));
    }

    @Test
    void getTranslationHistory_shouldReturnParsedEntries() throws Exception {
        SubtitleEntry entry = SubtitleEntry.builder()
                .sentenceId("s1").original("Hello").translation("你好").timestamp(1000L).build();
        ObjectMapper mapper = new ObjectMapper();
        when(listOps.size("session:abc123:translations")).thenReturn(1L);
        when(listOps.range("session:abc123:translations", 0, -1))
                .thenReturn(List.of(mapper.writeValueAsString(entry)));

        List<SubtitleEntry> history = sessionService.getTranslationHistory("abc123", 20);

        assertEquals(1, history.size());
        assertEquals("s1", history.get(0).getSentenceId());
        assertEquals("你好", history.get(0).getTranslation());
    }

    @Test
    void updateGlossary_shouldMergeWithExisting() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        when(valueOps.get("session:abc123:glossary"))
                .thenReturn(mapper.writeValueAsString(Map.of("neural network", "神经网络")));

        sessionService.updateGlossary("abc123", Map.of("transformer", "Transformer 模型"));

        verify(valueOps).set(eq("session:abc123:glossary"), argThat(json ->
                json.contains("neural network") && json.contains("transformer")
        ));
    }
}
