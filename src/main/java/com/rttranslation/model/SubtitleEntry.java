package com.rttranslation.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 字幕条目
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubtitleEntry {
    /** 唯一标识（如 s1, s2, s3...） */
    private String sentenceId;
    /** ASR 识别原文 */
    private String original;
    /** 翻译结果 */
    private String translation;
    /** 时间戳（毫秒） */
    private long timestamp;
}
