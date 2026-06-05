package com.rttranslation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import lombok.Data;

/**
 * OpenAI 翻译配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "openai")
public class OpenAiProperties {
    private String apiKey;
    private String baseUrl = "https://api.openai.com/v1";
    private String model = "gpt-4.1";
    private double temperature = 0.3;
}
