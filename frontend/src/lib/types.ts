// ===== SSE 事件协议（与后端 WebChatStreamService 对齐） =====

export type StreamEvent =
  | { type: 'run'; runId: string; userMessageId: string; assistantMessageId: string }
  | { type: 'skill'; name: string }
  | { type: 'tool_start'; name: string; skill?: string }
  | { type: 'tool_end'; name: string; skill?: string; ok: boolean; durationMs?: number; detail?: string }
  | { type: 'tool_trace'; item: ToolItem }
  | { type: 'text'; content: string }
  | { type: 'done'; reply: string; silent?: boolean; artifacts?: Artifact[] }
  | { type: 'error'; message: string };

// ===== 对话模型 =====

export interface PendingToolInfo {
  id: string;
  toolName: string;
  displayName: string;
  argsPreview?: string;
  expireAt?: string;
  traceId?: string;
}

export type ToolState = 'running' | 'ok' | 'err' | 'WAIT_CONFIRM';

export interface ToolItem {
  id: string;
  name: string;
  skill: string;
  state: ToolState;
  durationMs?: number;
  detail?: string;
  traceId?: string;
  confirmPayload?: string;
  eventType?: 'APPEND' | 'UPDATE';
}

export interface ChatMsg {
  id: string;
  createdAt?: number;
  role: 'user' | 'assistant';
  /** 用户文本或助手文本 */
  content: string;
  tools?: ToolItem[];
  /** 本轮命中的技能（实时浮现，用于时间线上方的小标签） */
  skills?: string[];
  /** 助手消息正在流式生成中 */
  streaming?: boolean;
  /** 工具 trace 是否展开（结束态可点开回看；缺省 = 收起为摘要行） */
  traceOpen?: boolean;
  /** 本轮耗时汇总（毫秒），用于收起态文案 */
  totalMs?: number;
  /** 出错时的展示文案（替代正文渲染错误条） */
  errorText?: string;
  artifacts?: Artifact[];
  runId?: string;
  status?: 'COMPLETED' | 'STREAMING' | 'FAILED' | 'CANCELLED';
}

export interface Conversation {
  id: string;
  title: string;
  pinned: boolean;
  archived: boolean;
  lastMessagePreview: string;
  createdAt: number;
  updatedAt: number;
  deletedAt?: number | null;
}

export interface HistoryMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  attachments?: Artifact[];
  artifacts?: Artifact[];
  tools?: ToolItem[];
  skills?: string[];
  status: 'COMPLETED' | 'STREAMING' | 'FAILED' | 'CANCELLED';
  runId?: string;
  errorText?: string;
  totalMs?: number;
  createdAt: number;
  updatedAt: number;
}

export interface ConversationPage { items: Conversation[]; nextCursor?: string | null }
export interface MessagePage { items: HistoryMessage[]; nextCursor?: string | null }

export interface Artifact {
  id: string;
  kind: 'BOARD' | 'IMAGE' | 'AUDIO' | 'FILE';
  mimeType: string;
  fileName: string;
  size: number;
  description?: string;
  url: string;
  /** BOARD 类型的结构化数据 */
  boardData?: BoardView;
}

export interface BoardView {
  title: string;            // 「杭州两日行程」
  stats: string;            // 「步行 12.4 km · 用餐 3 顿 · 预算约 ¥860」
  days: {
    label: string;
    items: {
      title: string;
      time: string;
      status: 'done' | 'adjusted' | 'added';
      note?: string;
    }[];
  }[];
}

// ===== 右侧信息栏数据（/api/webchat/*） =====

export interface GoalItem {
  id: number;
  title: string;
  successCriteria?: string;
  deadline?: string;
  status: string;
  progress: number;
}

export interface MemoryItem {
  id: string;
  category: string;
  content: string;
  topicKey?: string;
  importance: number;
}

export interface ActivityItem {
  time: string;
  text: string;
  color: string;
}

export interface SystemStatus {
  appReady: boolean;
  activeGoalCount: number;
}

export interface AppUser {
  id: string;
  username: string;
  displayName: string;
}

export interface NotificationItem {
  id: number;
  source: string;
  title: string;
  content: string;
  priority: number;
  actionPayload?: string;
  status: 'UNREAD' | 'READ';
  /** 后端把 Instant 序列化成 epoch 秒（如 1788531403.027），前端需按秒转毫秒 */
  createdAt: number;
}
