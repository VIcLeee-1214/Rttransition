package com.rttranslation.controller;

import com.rttranslation.model.SubtitleEntry;
import com.rttranslation.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final SessionService sessionService;

    public ExportController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * 导出 SRT 格式字幕
     * GET /api/export/{sessionId}/srt
     */
    @GetMapping("/{sessionId}/srt")
    public ResponseEntity<byte[]> exportSrt(@PathVariable String sessionId) {
        List<SubtitleEntry> translations = sessionService.getTranslationHistory(sessionId, 10000);
        List<String> transcripts = sessionService.getTranscriptHistory(sessionId, 10000);

        StringBuilder srt = new StringBuilder();
        for (int i = 0; i < translations.size(); i++) {
            SubtitleEntry entry = translations.get(i);
            srt.append(i + 1).append("\n");
            srt.append(formatSrtTime(entry.getTimestamp())).append(" --> ")
               .append(formatSrtTime(entry.getTimestamp() + 3000)).append("\n");
            // 原文 + 译文双行
            if (i < transcripts.size()) {
                srt.append(transcripts.get(i)).append("\n");
            }
            srt.append(entry.getTranslation()).append("\n\n");
        }

        byte[] bytes = srt.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"subtitles.srt\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }

    /**
     * 导出 WebVTT 格式字幕
     * GET /api/export/{sessionId}/vtt
     */
    @GetMapping("/{sessionId}/vtt")
    public ResponseEntity<byte[]> exportVtt(@PathVariable String sessionId) {
        List<SubtitleEntry> translations = sessionService.getTranslationHistory(sessionId, 10000);
        List<String> transcripts = sessionService.getTranscriptHistory(sessionId, 10000);

        StringBuilder vtt = new StringBuilder("WEBVTT\n\n");
        for (int i = 0; i < translations.size(); i++) {
            SubtitleEntry entry = translations.get(i);
            vtt.append(formatVttTime(entry.getTimestamp())).append(" --> ")
               .append(formatVttTime(entry.getTimestamp() + 3000)).append("\n");
            if (i < transcripts.size()) {
                vtt.append(transcripts.get(i)).append("\n");
            }
            vtt.append(entry.getTranslation()).append("\n\n");
        }

        byte[] bytes = vtt.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"subtitles.vtt\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }

    private String formatSrtTime(long ms) {
        long h = ms / 3600000;
        long m = (ms % 3600000) / 60000;
        long s = (ms % 60000) / 1000;
        long milli = ms % 1000;
        return String.format("%02d:%02d:%02d,%03d", h, m, s, milli);
    }

    private String formatVttTime(long ms) {
        long h = ms / 3600000;
        long m = (ms % 3600000) / 60000;
        long s = (ms % 60000) / 1000;
        long milli = ms % 1000;
        return String.format("%02d:%02d:%02d.%03d", h, m, s, milli);
    }
}
