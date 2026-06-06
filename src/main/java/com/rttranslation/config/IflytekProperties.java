package com.rttranslation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import lombok.Data;

/**
 * 讯飞实时语音识别配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "iflytek")
public class IflytekProperties {
    private String appId;
    private String apiKey;
    private String apiSecret;
    /** 方言/语种: mandarin（中文）, en_us（英文）, mand_en_a（中英混合） */
    private String accent = "en_us";
}
