# AI 同声传译助手 (RtTranslation)

基于 Spring Boot 的 AI 同声传译 Web 应用。通过麦克风实时采集外语音频，
经 Deepgram Nova-3 语音识别 + GPT-4.1 智能翻译，以中文字幕形式实时呈现，
并具备自动纠错能力。

## 技术栈

- **后端**: Spring Boot 3.2 + JDK 17
- **ASR**: Deepgram Nova-3（流式 WebSocket）
- **翻译**: OpenAI GPT-4.1（上下文感知 + 纠错）
- **存储**: Redis（会话上下文、术语表）
- **前端**: Thymeleaf + 原生 JS（Web Audio API + WebSocket + SSE）

## 本地开发

### 前置条件
- JDK 17+
- Maven 3.8+
- Redis 7+（可用 `docker run -d -p 6379:6379 redis:7-alpine`）
- Deepgram API Key（https://console.deepgram.com）
- OpenAI API Key

### 配置
复制 `.env.example` 为 `.env` 并填入 API Key，或在 IDEA 中设置环境变量。

### 启动 Redis
```bash
docker run -d -p 6379:6379 redis:7-alpine
```

### 启动应用
```bash
mvn spring-boot:run
```

或在 IDEA 中直接运行 `RtTranslationApplication.main()`。

打开 http://localhost:8080 即可使用。

## Docker 部署
```bash
cp .env.example .env   # 填入 API Key
docker compose up --build
```
访问 http://localhost:8080

## 项目结构
```
src/main/java/com/rttranslation/
├── RtTranslationApplication.java   # 启动类
├── config/                         # 配置类（Deepgram、OpenAI、WebSocket）
├── controller/                     # REST API + 页面控制器
├── websocket/                      # WebSocket 音频接收处理器
├── service/                        # 核心业务（ASR、翻译、SSE推送、会话管理）
└── model/                          # 数据模型（DTO）

src/main/resources/
├── application.yml                 # 应用配置
├── templates/index.html            # 页面模板
└── static/                         # CSS + JS
```
