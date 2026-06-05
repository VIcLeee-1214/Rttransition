package com.rttranslation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE 推送事件（统一封装）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SseEvent {
    /**
     * SSE 推送事件（统一封装）
     *
     * <p>支持的事件类型：
     * <ul>
     *   <li>interim - ASR 中间识别结果（英文原文预览）</li>
     *   <li>interim_translation - interim 阶段的快速中文预翻译</li>
     *   <li>translation_start - 正式翻译开始（前端创建字幕占位）</li>
     *   <li>token - 翻译 token 流式推送（一个字一个字出来）</li>
     *   <li>translation_end - 翻译完成（含完整结果 + 纠错）</li>
     *   <li>correction - 历史翻译纠错</li>
     *   <li>error - 错误</li>
     * </ul>
     */
    private String type;
    /** 句子 ID */
    private String sentenceId;
    /** 原文 */
    private String original;
    /** 完整译文（translation_end 时携带） */
    private String translation;
    /** 单个 token 文本（token 事件） */
    private String token;
    /** 时间戳 */
    private Long timestamp;
    /** interim 文本 */
    private String text;
    /** 预翻译文本（interim_translation 事件） */
    private String preview;
    /** 修正前译文 */
    private String previousTranslation;
    /** 修正后译文 */
    private String correctedTranslation;
    /** 修正原因 */
    private String reason;
    /** 错误信息 */
    private String message;

    // ===== 快捷工厂方法 =====

    public static SseEvent interim(String text) {
        return SseEvent.builder().type("interim").text(text).build();
    }

    /** interim 阶段的快速预翻译 */
    public static SseEvent interimTranslation(String preview) {
        return SseEvent.builder().type("interim_translation").preview(preview).build();
    }

    /** 正式翻译开始（前端据此创建字幕占位） */
    public static SseEvent translationStart(String sentenceId, String original, long timestamp) {
        return SseEvent.builder()
                .type("translation_start")
                .sentenceId(sentenceId)
                .original(original)
                .timestamp(timestamp)
                .build();
    }

    /** 流式推送一个翻译 token */
    public static SseEvent token(String sentenceId, String tokenText) {
        return SseEvent.builder()
                .type("token")
                .sentenceId(sentenceId)
                .token(tokenText)
                .build();
    }

    /** 翻译完成（携带完整结果用于最终确认） */
    public static SseEvent translationEnd(String sentenceId, String translation) {
        return SseEvent.builder()
                .type("translation_end")
                .sentenceId(sentenceId)
                .translation(translation)
                .build();
    }

    /** 兼容旧的一次性翻译事件（回退用） */
    public static SseEvent translation(String sentenceId, String original,
                                        String translation, long timestamp) {
        return SseEvent.builder()
                .type("translation")
                .sentenceId(sentenceId)
                .original(original)
                .translation(translation)
                .timestamp(timestamp)
                .build();
    }

    public static SseEvent correction(String sentenceId, String previous,
                                       String corrected, String reason) {
        return SseEvent.builder()
                .type("correction")
                .sentenceId(sentenceId)
                .previousTranslation(previous)
                .correctedTranslation(corrected)
                .reason(reason)
                .build();
    }

    public static SseEvent error(String message) {
        return SseEvent.builder().type("error").message(message).build();
    }
}
