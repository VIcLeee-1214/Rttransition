package com.rttranslation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * AI 同声传译助手 - 主启动类
 */
@SpringBootApplication
@EnableAsync
public class RtTranslationApplication {

    public static void main(String[] args) {
        SpringApplication.run(RtTranslationApplication.class, args);
    }
}
