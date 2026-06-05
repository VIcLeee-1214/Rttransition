package com.rttranslation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * LLM 翻译返回结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TranslationResult {
    private String translation;
    private List<Correction> corrections;
    private Map<String, String> newTerms;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Correction {
        /** 被修正的句子 ID */
        private String sentenceId;
        /** 之前的译文 */
        private String previousTranslation;
        /** 修正后的译文 */
        private String correctedTranslation;
        /** 修正原因 */
        private String reason;
    }
}
