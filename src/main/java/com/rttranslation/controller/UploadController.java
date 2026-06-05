package com.rttranslation.controller;

import com.rttranslation.model.SessionConfig;
import com.rttranslation.service.SessionService;
import com.rttranslation.service.SseService;
import com.rttranslation.service.TranslationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/upload")
public class UploadController {

    private final SessionService sessionService;
    private final SseService sseService;
    private final TranslationService translationService;

    public UploadController(SessionService sessionService, SseService sseService,
                            TranslationService translationService) {
        this.sessionService = sessionService;
        this.sseService = sseService;
        this.translationService = translationService;
    }

    /**
     * 上传音视频文件进行翻译
     * POST /api/upload
     *
     * Note: 实际实现需要将音频发送给 Deepgram 的 batch/pre-recorded API。
     * 这里提供接口框架，完整的音频转码和 Deepgram batch API 集成需后续补充。
     */
    @PostMapping
    public Map<String, String> uploadAudio(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sourceLang", defaultValue = "en") String sourceLang) {

        String sessionId = sessionService.createSession(new SessionConfig(sourceLang, "zh-CN"));

        // TODO: 将文件保存到临时目录，调用 Deepgram pre-recorded transcription API
        // 目前返回 sessionId，前端可以连接 SSE 监听进度
        log.info("收到上传文件: {}, sessionId: {}", file.getOriginalFilename(), sessionId);

        return Map.of(
            "sessionId", sessionId,
            "status", "uploaded",
            "message", "文件已接收，翻译处理中"
        );
    }
}
