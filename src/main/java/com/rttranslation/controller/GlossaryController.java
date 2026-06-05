package com.rttranslation.controller;

import com.rttranslation.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/glossary")
public class GlossaryController {

    private final SessionService sessionService;

    public GlossaryController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** 获取会话术语表 */
    @GetMapping("/{sessionId}")
    public ResponseEntity<Map<String, String>> getGlossary(@PathVariable String sessionId) {
        return ResponseEntity.ok(sessionService.getGlossary(sessionId));
    }

    /** 批量导入术语（用户可预导入专业领域术语） */
    @PostMapping("/{sessionId}")
    public ResponseEntity<Map<String, String>> importTerms(
            @PathVariable String sessionId,
            @RequestBody Map<String, String> terms) {
        sessionService.updateGlossary(sessionId, terms);
        log.info("会话 {} 导入 {} 条术语", sessionId, terms.size());
        return ResponseEntity.ok(sessionService.getGlossary(sessionId));
    }

    /** 删除单条术语 */
    @DeleteMapping("/{sessionId}/{term}")
    public ResponseEntity<Void> deleteTerm(
            @PathVariable String sessionId,
            @PathVariable String term) {
        Map<String, String> glossary = sessionService.getGlossary(sessionId);
        glossary.remove(term);
        sessionService.updateGlossary(sessionId, glossary);
        return ResponseEntity.ok().build();
    }
}
