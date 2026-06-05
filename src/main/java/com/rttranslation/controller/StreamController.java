package com.rttranslation.controller;

import com.rttranslation.service.SseService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 流式推送端点
 *
 * <p>前端通过 EventSource 连接此地址，接收翻译结果的实时推送。</p>
 * <p>URL: GET /api/stream/{sessionId}</p>
 */
@RestController
@RequestMapping("/api/stream")
public class StreamController {

    private final SseService sseService;

    public StreamController(SseService sseService) {
        this.sseService = sseService;
    }

    @GetMapping(value = "/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String sessionId) {
        return sseService.subscribe(sessionId);
    }
}
