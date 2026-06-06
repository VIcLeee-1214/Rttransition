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
    private String baseUrl = "https://api.deepseek.com/v1";
    private String model = "deepseek-chat";
    private double temperature = 0.3;
}
