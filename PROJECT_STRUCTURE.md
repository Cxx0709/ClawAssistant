# Claw Assistant 项目结构

```text
frontend/                         React + TypeScript Web 客户端
  src/components/                 对话、工具轨迹、通知/目标/记忆侧栏
  src/pages/                      登录/注册、首页、聊天页
  src/lib/                        API、SSE 与共享类型

src/main/java/com/youkeda/exercise/claw/
  agent/                          ReAct 执行、上下文、计划、技能路由
  ai/                             LLM、视觉、图像、语音、检索客户端
  artifact/                       上传件与生成产物的租户隔离存储
  identity/                       应用账户、用户资料、执行身份上下文
  notification/                   SQLite 通知 outbox 与用户 SSE
  web/                            认证、聊天、附件、通知 REST/SSE 入口
  feature/                        旅行、校园、文件、任务、目标、Scout 等业务
  tool/                           暴露给 LLM 的工具适配层
  skill/                          Skill 定义与注册
  infrastructure/                文档解析及通用基础设施

src/main/resources/
  static/                         Vite 生产构建产物
  prompts/                        Agent 与 Skill 提示词
  config/                         Skill 定义和确定性触发规则
  application.properties.example 配置模板

src/test/java/                    单元、集成、架构与租户隔离测试
```

## 请求链路

```text
Spring Security session
  → WebChatController
  → ChatApplicationService
  → ReActAgentExecutor
  → SkillRouter / ExecutionLoop / ToolExecutor
  → text SSE + GeneratedArtifact[]
```

上传的图片、音频和文件先进入 `ArtifactService`，再由 `ChatApplicationService` 分别调用视觉、ASR 或文档解析服务。工具生成的二进制结果通过 request-scoped `ArtifactCollector` 回到同一轮 Web 响应。

定时任务与主动推荐不再调用外部消息通道，而是写入 `notification_outbox`；在线用户由 `NotificationStreamService` 实时收到 SSE，离线用户下次登录仍可看到未读记录。

## 用户与旧数据

`app_user.id` 是全项目统一的租户键。首次设置账户时，`LegacyOwnerImporter` 可以读取旧数据库中最早的 `wechat_users.user_id` 并将其作为首个 Web 账户 ID，以保留原有对话、记忆、目标、课表和文件归属。该读取器仅用于一次性兼容，不属于运行时消息通道。
