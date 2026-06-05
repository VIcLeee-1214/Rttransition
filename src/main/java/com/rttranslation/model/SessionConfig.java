package com.rttranslation.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话配置（前端创建会话时传入）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionConfig {
    /** 源语言：en, ja, ko, fr, de, es */
    private String sourceLang = "en";
    /** 目标语言：zh-CN */
    private String targetLang = "zh-CN";
}
