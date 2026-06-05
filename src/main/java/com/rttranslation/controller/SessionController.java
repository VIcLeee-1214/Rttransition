package com.rttranslation.controller;

import com.rttranslation.model.SessionConfig;
import com.rttranslation.service.SessionService;
import com.rttranslation.service.SseService;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 会话管理 REST API
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    private final SessionService sessionService;
    private final SseService sseService;

    public SessionController(SessionService sessionService, SseService sseService) {
        this.sessionService = sessionService;
        this.sseService = sseService;
    }

    /**
     * 创建同传会话
     * POST /api/session
     */
    @PostMapping
    public ResponseEntity<SessionResponse> createSession(@RequestBody SessionConfig config) {
        String sessionId = sessionService.createSession(config);
        return ResponseEntity.ok(new SessionResponse(sessionId, config, Instant.now().toString()));
    }

    /**
     * 查询会话信息
     * GET /api/session/{sessionId}
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<?> getSession(@PathVariable String sessionId) {
        Optional<Map<String, String>> meta = sessionService.getSession(sessionId);
        if (meta.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(meta.get());
    }

    /**
     * 结束会话
     * DELETE /api/session/{sessionId}
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> endSession(@PathVariable String sessionId) {
        sseService.closeAll(sessionId);
        sessionService.deleteSession(sessionId);
        return ResponseEntity.ok().build();
    }

    // ---- 响应 DTO ----

    @Data
    @AllArgsConstructor
    public static class SessionResponse {
        private String sessionId;
        private SessionConfig config;
        private String createdAt;
    }
}
