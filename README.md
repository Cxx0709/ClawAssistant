# Claw Assistant — 纯 Web 智能助理

Claw Assistant 是一个由 Spring Boot 与 React 驱动的多用户 Web Agent。它通过 ReAct 与 LLM tool-calling 调度天气、地图、旅行、课表、文件、图像、语音、长期记忆、目标跟进和信息订阅等能力。

## 这版包含什么

- Web 账户体系：首次启动创建管理员，后续可注册多个独立账户；密码使用 BCrypt 保存，会话由 Spring Security 管理。
- 租户隔离：对话、长期记忆、目标、活动、附件、生成产物和通知均按应用用户 ID 隔离。
- 完整多模态：浏览器可上传图片、音频和文件；Agent 生成的图片、语音和文件直接在对话中预览或下载。
- 站内通知：定时任务、课程提醒、动漫和信息猎手统一写入持久化 outbox，并通过 SSE 实时送到通知中心。
- 旧数据接管：首次创建的账户会认领旧 `wechat_users` 中最早用户的租户 ID，因此原对话、记忆、目标和文件无需全表重写。
- 无渠道依赖：运行时不包含微信 SDK、扫码登录、轮询、消息路由或微信发送逻辑。

## 技术栈

- Java 21、Spring Boot 3.3、Spring Security、Spring JDBC、SQLite
- React 18、TypeScript、Vite、Tailwind CSS
- 阿里云百炼 Qwen/OpenAI-compatible LLM、DashScope Vision/ASR/TTS/Image
- Qdrant（长期记忆与 Skill 知识库）

## 本地运行

1. 复制并填写配置：

   ```powershell
   Copy-Item src/main/resources/application.properties.example src/main/resources/application.properties
   $env:DASHSCOPE_API_KEY = '你的北京地域百炼 API Key'
   ```

2. 构建前端（产物会写入 Spring Boot 的静态目录）：

   ```powershell
   Set-Location frontend
   npm install
   npm run build
   Set-Location ..
   ```

3. 启动后端：

   ```powershell
   mvn spring-boot:run
   ```

4. 打开 `http://localhost:8080`。首次进入会要求创建首个账户；如存在旧数据库，该账户自动接管旧 owner 数据。

## 关键配置

```properties
spring.datasource.url=jdbc:sqlite:data/claw.db
legacy.owner-db-path=./data/claw-bot.db
artifact.storage-path=./data/artifacts
spring.servlet.multipart.max-file-size=25MB
spring.servlet.multipart.max-request-size=100MB
```

LLM、视觉、语音、图片、地图、搜索与 Qdrant 等 API 配置见 [`application.properties.example`](src/main/resources/application.properties.example)。不要提交真实密钥。
默认文本模型为北京地域百炼 `qwen3.6-plus`，使用 `DASHSCOPE_API_KEY`；该 Key 必须与 Base URL 地域一致。

## 主要链路

```text
Browser
  ├─ Spring Security session + CSRF
  ├─ WebChatController / SSE
  │    └─ ChatApplicationService
  │         └─ ReActAgentExecutor → SkillRouter → ExecutionLoop → Tools
  ├─ ArtifactController → tenant-owned binary storage
  └─ NotificationController → SQLite outbox → per-user SSE
```

## 验证

```powershell
mvn test
Set-Location frontend
npm run typecheck
npm run build
```
