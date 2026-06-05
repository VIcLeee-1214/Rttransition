package com.rttranslation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rttranslation.config.OpenAiProperties;
import com.rttranslation.config.TranslationProperties;
import com.rttranslation.model.SubtitleEntry;
import com.rttranslation.model.TranslationResult;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * 翻译引擎 - 支持流式翻译 + 纠错 + 快速预翻译 + 周期回顾
 *
 * <p>四种能力：
 * <ul>
 *   <li>{@link #streamTranslate} - 流式翻译（含 token 推送），翻译完成后自动触发纠错</li>
 *   <li>{@link #previewTranslate} - 快速预翻译（gpt-4o-mini），用于 interim</li>
 *   <li>{@link #reviewAndCorrect} - 周期性回顾纠错（每 N 句触发）</li>
 *   <li>{@link #generateSentenceId} - 统一的 sentenceId 生成</li>
 * </ul>
 */
@Slf4j
@Service
public class TranslationService {

    private final OpenAiProperties openAiConfig;
    private final TranslationProperties transConfig;
    private final SessionService sessionService;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 每个会话的句子计数器（sentenceId 唯一来源） */
    private final Map<String, Integer> counters = new HashMap<>();

    /** 每个会话的回顾计数器 */
    private final Map<String, Integer> reviewCounters = new HashMap<>();

    private static final String PREVIEW_MODEL = "gpt-4o-mini";

    private static final Map<String, String> LANG_NAMES = Map.of(
            "en", "英语", "ja", "日语", "ko", "韩语",
            "fr", "法语", "de", "德语", "es", "西班牙语"
    );

    public TranslationService(OpenAiProperties openAiConfig,
                               TranslationProperties transConfig,
                               SessionService sessionService) {
        this.openAiConfig = openAiConfig;
        this.transConfig = transConfig;
        this.sessionService = sessionService;
        this.httpClient = new OkHttpClient.Builder()
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    // ==================== #2 统一的 sentenceId 生成 ====================

    /**
     * 为指定会话生成下一个 sentenceId。
     * 这是整个系统中 sentenceId 的唯一来源，避免多处生成导致 ID 冲突。
     */
    public String generateSentenceId(String sessionId) {
        int next = counters.merge(sessionId, 1, Integer::sum);
        return "s" + next;
    }

    // ==================== 流式翻译（用于 final result） ====================

    /**
     * 流式翻译一句话。
     * <p>
     * 策略：先流式输出纯文本译文（低延迟），流结束后异步触发纠错检查。
     * sentenceId 由调用方（AudioWebSocketHandler）通过 {@link #generateSentenceId} 生成后传入。
     *
     * @param sessionId  会话 ID
     * @param sourceLang 源语言
     * @param text       待翻译文本
     * @param onToken    每个 token 的回调
     * @return 流式累积的完整译文（不含纠错，纠错通过 reviewAndCorrect 异步处理）
     */
    public String streamTranslate(String sessionId, String sourceLang,
                                   String text, Consumer<String> onToken) throws IOException {
        String prompt = buildStreamingPrompt(sessionId, sourceLang, text);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", openAiConfig.getModel());
        body.put("temperature", openAiConfig.getTemperature());
        body.put("stream", true);
        body.put("messages", List.of(
                Map.of("role", "system", "content",
                        "你是专业同声传译员。直接输出译文，不加任何解释、标记或格式。"),
                Map.of("role", "user", "content", prompt)
        ));

        String requestBody = mapper.writeValueAsString(body);

        Request request = new Request.Builder()
                .url(openAiConfig.getBaseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + openAiConfig.getApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                .build();

        // 唯一累积点：只在 streamTranslate 内部累积，调用方不再二次累积
        StringBuilder fullTranslation = new StringBuilder();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String error = response.body() != null ? response.body().string() : "无响应体";
                throw new IOException("OpenAI API 调用失败: " + response.code() + " - " + error);
            }

            ResponseBody responseBody = response.body();
            if (responseBody == null) throw new IOException("OpenAI API 响应体为空");

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody.byteStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) break;

                    try {
                        JsonNode chunk = mapper.readTree(data);
                        String content = chunk.path("choices").path(0)
                                .path("delta").path("content").asText(null);
                        if (content != null && !content.isEmpty()) {
                            fullTranslation.append(content);
                            onToken.accept(content);
                        }
                    } catch (Exception ignored) {
                        // 跳过无法解析的 SSE chunk
                    }
                }
            }
        }

        // #1 更新回顾计数器，到达阈值时触发纠错
        int count = reviewCounters.merge(sessionId, 1, Integer::sum);
        if (count >= transConfig.getReviewInterval()) {
            reviewCounters.put(sessionId, 0);
            log.info("[{}] 达到回顾阈值（{}句），将在后台触发纠错审查", sessionId, count);
            // 回顾由 AudioWebSocketHandler 异步调用 reviewAndCorrect
        }

        return fullTranslation.toString();
    }

    // ==================== #1 周期性回顾纠错 ====================

    /**
     * 回顾最近 N 轮翻译，检查并纠正历史错误。
     * 由 AudioWebSocketHandler 在句子计数达到 reviewInterval 时异步调用。
     *
     * @return 纠错列表（可能为空）
     */
    public List<TranslationResult.Correction> reviewAndCorrect(String sessionId, String sourceLang) {
        String langName = LANG_NAMES.getOrDefault(sourceLang, "英语");

        List<String> transcripts = sessionService.getTranscriptHistory(sessionId, 20);
        List<SubtitleEntry> translations = sessionService.getTranslationHistory(sessionId, 20);

        if (transcripts.isEmpty() || translations.isEmpty()) {
            return Collections.emptyList();
        }

        // 构建回顾 prompt
        StringBuilder historyBuilder = new StringBuilder();
        for (int i = 0; i < Math.min(transcripts.size(), translations.size()); i++) {
            SubtitleEntry entry = translations.get(i);
            historyBuilder.append(String.format(
                    "第%d句 (ID: %s)\n  原文: %s\n  译文: %s\n\n",
                    i + 1,
                    entry.getSentenceId() != null ? entry.getSentenceId() : "unknown",
                    transcripts.get(i),
                    entry.getTranslation()
            ));
        }

        String prompt = String.format("""
                你是专业的%s→中文同声传译审校员。
                请审查以下最近的翻译记录，找出并纠正其中的错误。
                
                重点检查：
                1. 同音词/近音词识别错误（如 their/there, affect/effect）
                2. 专业术语翻译不一致
                3. 语义理解偏差
                4. 漏译或过度翻译
                
                严格以 JSON 格式输出：
                { "corrections": [
                    { "sentenceId": "句子ID",
                      "previousTranslation": "原译文",
                      "correctedTranslation": "修正后译文",
                      "reason": "修正原因" }
                ]}
                
                如果没有需要修正的内容，输出 {"corrections": []}
                
                ---
                翻译记录：
                %s""", langName, historyBuilder);

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", PREVIEW_MODEL);
            body.put("temperature", 0.2);
            body.put("response_format", Map.of("type", "json_object"));
            body.put("messages", List.of(
                    Map.of("role", "system", "content", "你是专业翻译审校员，严格以JSON格式输出。"),
                    Map.of("role", "user", "content", prompt)
            ));

            Request request = new Request.Builder()
                    .url(openAiConfig.getBaseUrl() + "/chat/completions")
                    .header("Authorization", "Bearer " + openAiConfig.getApiKey())
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(mapper.writeValueAsString(body),
                            MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("[{}] 回顾纠错 API 调用失败: {}", sessionId, response.code());
                    return Collections.emptyList();
                }
                JsonNode root = mapper.readTree(response.body().string());
                String content = root.path("choices").path(0)
                        .path("message").path("content").asText("{}");
                TranslationResult result = mapper.readValue(content, TranslationResult.class);
                List<TranslationResult.Correction> corrections =
                        result.getCorrections() != null ? result.getCorrections() : Collections.emptyList();
                log.info("[{}] 回顾纠错完成，发现 {} 处修正", sessionId, corrections.size());
                return corrections;
            }
        } catch (Exception e) {
            log.error("[{}] 回顾纠错异常: {}", sessionId, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ==================== 快速预翻译（用于 interim result） ====================

    /**
     * 快速预翻译 - 用 gpt-4o-mini 做轻量翻译，只返回纯译文文本。
     */
    public String previewTranslate(String sessionId, String sourceLang, String text) throws IOException {
        String langName = LANG_NAMES.getOrDefault(sourceLang, "英语");

        String prompt = String.format(
                "将以下%s翻译成中文，只输出译文，不要任何解释或格式：\n%s",
                langName, text);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", PREVIEW_MODEL);
        body.put("temperature", 0.3);
        body.put("max_tokens", 500);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));

        Request request = new Request.Builder()
                .url(openAiConfig.getBaseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + openAiConfig.getApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(mapper.writeValueAsString(body),
                        MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("[{}] 预翻译失败: {}", sessionId, response.code());
                return "";
            }
            JsonNode root = mapper.readTree(response.body().string());
            return root.path("choices").path(0).path("message").path("content").asText("");
        }
    }

    // ==================== Prompt 构建 ====================

    /**
     * 流式翻译 prompt（纯文本输出，含上下文和术语表）
     */
    private String buildStreamingPrompt(String sessionId, String sourceLang, String currentText) {
        String langName = LANG_NAMES.getOrDefault(sourceLang, "英语");

        List<String> transcripts = sessionService.getTranscriptHistory(
                sessionId, transConfig.getMaxContextTurns());
        List<SubtitleEntry> translations = sessionService.getTranslationHistory(
                sessionId, transConfig.getMaxContextTurns());
        Map<String, String> glossary = sessionService.getGlossary(sessionId);

        StringBuilder context = new StringBuilder();
        for (int i = 0; i < Math.min(transcripts.size(), translations.size()); i++) {
            context.append(transcripts.get(i)).append(" → ")
                    .append(translations.get(i).getTranslation()).append("\n");
        }

        StringBuilder glossaryText = new StringBuilder();
        if (!glossary.isEmpty()) {
            glossaryText.append("\n术语表（请保持一致）：");
            glossary.forEach((k, v) -> glossaryText.append(k).append("=").append(v).append("，"));
        }

        return String.format("""
                你是专业的%s→中文同声传译员。请翻译以下文本为流畅的中文。
                要求：口语化、自然，专业术语保持一致。直接输出译文，不要任何解释。%s%s
                原文：%s""",
                langName,
                context.isEmpty() ? "" : "\n之前翻译过：\n" + context,
                glossaryText,
                currentText);
    }

    // ==================== 清理 ====================

    public void cleanup(String sessionId) {
        counters.remove(sessionId);
        reviewCounters.remove(sessionId);
    }

    /**
     * 检查是否到达回顾阈值
     */
    public boolean shouldReview(String sessionId) {
        return reviewCounters.getOrDefault(sessionId, 0) == 0
                && counters.getOrDefault(sessionId, 0) > 0;
    }
}
