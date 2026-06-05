package com.rttranslation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import lombok.Data;

/**
 * Deepgram ASR 配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "deepgram")
public class DeepgramProperties {
    private String apiKey;
    private String model = "nova-3";
    private int endpointingMs = 300;
}
