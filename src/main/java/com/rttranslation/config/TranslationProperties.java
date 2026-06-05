package com.rttranslation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import lombok.Data;

/**
 * 翻译业务参数配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "translation")
public class TranslationProperties {
    private int maxContextTurns = 20;
    private int reviewInterval = 20;
    private double sentenceBufferTimeout = 5.0;
}
