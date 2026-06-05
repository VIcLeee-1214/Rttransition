package com.rttranslation.config;

import com.rttranslation.websocket.AudioWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置 - 注册音频 WebSocket 端点
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AudioWebSocketHandler audioHandler;

    public WebSocketConfig(AudioWebSocketHandler audioHandler) {
        this.audioHandler = audioHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // /ws/audio/{sessionId} - 接收前端上传的 PCM 音频
        registry.addHandler(audioHandler, "/ws/audio/{sessionId}")
                .setAllowedOrigins("*");
    }
}
