import type {
  ActivityItem,
  AppUser,
  Artifact,
  GoalItem,
  MemoryItem,
  NotificationItem,
  PendingToolInfo,
  ScheduledTaskItem,
  SystemStatus,
  Conversation,
  AiRole,
  HistoryMessage,
  ConversationPage,
  MessagePage,
  ExamItem,
  TodaySchedule,
} from './types';

let csrfToken = '';
let csrfHeader = 'X-XSRF-TOKEN';
let csrfRequest: Promise<void> | null = null;

export async function ensureCsrf(): Promise<void> {
  if (csrfToken) return;
  if (!csrfRequest) {
    csrfRequest = (async () => {
      const res = await fetch('/api/auth/csrf', {
        credentials: 'same-origin',
        cache: 'no-store',
      });
      if (!res.ok) throw new Error('无法建立安全会话');
      const data = (await res.json()) as { token: string; headerName: string };
      csrfToken = data.token;
      csrfHeader = data.headerName;
    })().finally(() => {
      csrfRequest = null;
    });
  }
  await csrfRequest;
}

export async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const method = (init.method ?? 'GET').toUpperCase();
  const mutation = !['GET', 'HEAD', 'OPTIONS'].includes(method);

  const execute = async (): Promise<Response> => {
    const headers = new Headers(init.headers);
    if (mutation) {
      await ensureCsrf();
      headers.set(csrfHeader, csrfToken);
    }
    return fetch(path, { ...init, headers, credentials: 'same-origin' });
  };

  let res = await execute();
  if (res.status === 403 && mutation && !init.signal?.aborted) {
    // Authentication rotates Spring Security's CSRF token. Refresh once so the
    // first action after login does not fail with a stale pre-login token.
    csrfToken = '';
    csrfRequest = null;
    await ensureCsrf();
    res = await execute();
  }
  return res;
}

async function getJson<T>(path: string): Promise<T> {
  const res = await apiFetch(path, { headers: { Accept: 'application/json' } });
  if (!res.ok) throw new Error(`请求失败（HTTP ${res.status}）`);
  return (await res.json()) as T;
}

export async function fetchSetupStatus(): Promise<boolean> {
  const data = await getJson<{ setupRequired: boolean }>('/api/auth/setup-status');
  return data.setupRequired;
}

export async function setupAccount(username: string, password: string, displayName: string): Promise<void> {
  const res = await apiFetch('/api/auth/setup', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, displayName }),
  });
  if (!res.ok) throw new Error((await readError(res)) || '初始化失败');
}

export async function registerAccount(username: string, password: string, displayName: string): Promise<void> {
  const res = await apiFetch('/api/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, displayName }),
  });
  if (!res.ok) throw new Error((await readError(res)) || '注册失败');
}

export async function login(username: string, password: string): Promise<AppUser> {
  const body = new URLSearchParams({ username, password });
  const res = await apiFetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });
  if (!res.ok) throw new Error((await readError(res)) || '用户名或密码错误');
  csrfToken = '';
  csrfRequest = null;
  await ensureCsrf();
  return fetchCurrentUser();
}

export async function logout(): Promise<void> {
  try {
    await apiFetch('/api/auth/logout', { method: 'POST' });
  } finally {
    csrfToken = '';
    csrfRequest = null;
  }
}

export async function fetchCurrentUser(): Promise<AppUser> {
  return getJson<AppUser>('/api/auth/me');
}

export async function uploadArtifact(file: File, signal?: AbortSignal): Promise<Artifact> {
  const body = new FormData();
  body.append('file', file);
  const res = await apiFetch('/api/artifacts', { method: 'POST', body, signal });
  if (!res.ok) throw new Error((await readError(res)) || '上传失败');
  return (await res.json()) as Artifact;
}

export async function fetchArtifactPreview(id: string): Promise<{ fileName: string; mimeType: string; content: string }> {
  return getJson(`/api/artifacts/${encodeURIComponent(id)}/preview`);
}

export async function fetchGoals(): Promise<GoalItem[]> {
  return getJson<GoalItem[]>('/api/webchat/goals').catch(() => []);
}

export async function fetchMemories(): Promise<MemoryItem[]> {
  return getJson<MemoryItem[]>('/api/webchat/memories').catch(() => []);
}

export async function fetchActivities(): Promise<ActivityItem[]> {
  return getJson<ActivityItem[]>('/api/webchat/activities').catch(() => []);
}

export async function fetchStatus(): Promise<SystemStatus | null> {
  return getJson<SystemStatus>('/api/webchat/status').catch(() => null);
}

export async function fetchUpcomingExams(): Promise<ExamItem[]> {
  const data = await getJson<{ items: ExamItem[] }>('/api/workspace/exams');
  return data.items;
}

export async function fetchTodaySchedule(): Promise<TodaySchedule | null> {
  return getJson<TodaySchedule>('/api/workspace/today-courses').catch(() => null);
}

export async function fetchConversationPage(options: {
  archived?: boolean; deleted?: boolean; q?: string; cursor?: string; limit?: number;
} = {}): Promise<ConversationPage> {
  const params = new URLSearchParams({
    archived: String(!!options.archived),
    deleted: String(!!options.deleted),
    limit: String(options.limit ?? 30),
  });
  if (options.q?.trim()) params.set('q', options.q.trim());
  if (options.cursor) params.set('cursor', options.cursor);
  return getJson<ConversationPage>(`/api/webchat/conversations?${params}`);
}

export async function fetchConversations(archived = false): Promise<Conversation[]> {
  return (await fetchConversationPage({ archived, limit: 100 })).items;
}

export async function createConversation(roleId?: string): Promise<Conversation> {
  const body = roleId ? JSON.stringify({ roleId }) : undefined;
  const res = await apiFetch('/api/webchat/conversations', {
    method: 'POST',
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body,
  });
  if (!res.ok) throw new Error((await readError(res)) || '新建对话失败');
  return (await res.json()) as Conversation;
}

export async function updateConversationRole(conversationId: string, roleId: string | null): Promise<Conversation> {
  const res = await apiFetch(`/api/webchat/conversations/${encodeURIComponent(conversationId)}/role`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ roleId }),
  });
  if (!res.ok) throw new Error((await readError(res)) || '更新角色失败');
  return (await res.json()) as Conversation;
}

// ===== AI 角色相关 API =====
export async function fetchRoles(): Promise<AiRole[]> {
  return getJson<AiRole[]>('/api/roles');
}

export async function createRole(data: {
  name: string;
  avatar?: string;
  personality?: string;
  background?: string;
  speakingStyle?: string;
  catchphrase?: string;
}): Promise<AiRole> {
  const res = await apiFetch('/api/roles', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error((await readError(res)) || '创建角色失败');
  return (await res.json()) as AiRole;
}

export async function updateRole(id: string, data: {
  name: string;
  avatar?: string;
  personality?: string;
  background?: string;
  speakingStyle?: string;
  catchphrase?: string;
}): Promise<AiRole> {
  const res = await apiFetch(`/api/roles/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error((await readError(res)) || '更新角色失败');
  return (await res.json()) as AiRole;
}

export async function deleteRole(id: string): Promise<void> {
  const res = await apiFetch(`/api/roles/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!res.ok) throw new Error((await readError(res)) || '删除角色失败');
}

/** 用角色的声音合成语音（TTS），返回音频 blob */
export async function synthesizeRoleVoice(roleId: string, text: string): Promise<Blob> {
  const res = await apiFetch(`/api/roles/${encodeURIComponent(roleId)}/tts`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text }),
  });
  if (!res.ok) throw new Error((await readError(res)) || '语音合成失败');
  return await res.blob();
}

export async function fetchConversationMessages(id: string, before?: string): Promise<MessagePage> {
  const suffix = before ? `?limit=50&before=${encodeURIComponent(before)}` : '?limit=50';
  return getJson<MessagePage>(`/api/webchat/conversations/${encodeURIComponent(id)}/messages${suffix}`);
}

export async function fetchRun(id: string): Promise<HistoryMessage> {
  return getJson<HistoryMessage>(`/api/webchat/runs/${encodeURIComponent(id)}`);
}

export async function updateConversation(
  id: string,
  patch: { title?: string; pinned?: boolean; archived?: boolean; deleted?: boolean },
): Promise<Conversation> {
  const res = await apiFetch(`/api/webchat/conversations/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
  if (!res.ok) throw new Error((await readError(res)) || '更新对话失败');
  return (await res.json()) as Conversation;
}

export async function deleteConversation(id: string): Promise<void> {
  const res = await apiFetch(`/api/webchat/conversations/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
  if (!res.ok) throw new Error((await readError(res)) || '删除对话失败');
}

export async function purgeConversation(id: string): Promise<void> {
  const res = await apiFetch(`/api/webchat/conversations/${encodeURIComponent(id)}/purge`, {
    method: 'DELETE',
  });
  if (!res.ok) throw new Error((await readError(res)) || '永久删除失败');
}

export async function exportConversation(id: string, title: string): Promise<void> {
  const res = await apiFetch(`/api/webchat/conversations/${encodeURIComponent(id)}/export`);
  if (!res.ok) throw new Error((await readError(res)) || '导出失败');
  const url = URL.createObjectURL(await res.blob());
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `${title.replace(/[\\/:*?"<>|]/g, '_') || '知行对话'}.json`;
  anchor.click();
  URL.revokeObjectURL(url);
}

export async function importConversationFile(file: File): Promise<Conversation> {
  let source: { title?: string; conversation?: { title?: string }; messages?: HistoryMessage[] };
  try { source = JSON.parse(await file.text()) as typeof source; }
  catch { throw new Error('请选择有效的 JSON 对话文件'); }
  if (!Array.isArray(source.messages)) throw new Error('文件中没有可导入的消息');
  const res = await apiFetch('/api/webchat/conversations/import', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      title: source.title || source.conversation?.title || file.name.replace(/\.json$/i, ''),
      messages: source.messages.map((message) => ({ role: message.role, content: message.content })),
    }),
  });
  if (!res.ok) throw new Error((await readError(res)) || '导入失败');
  return (await res.json()) as Conversation;
}

export async function fetchNotifications(): Promise<NotificationItem[]> {
  return getJson<NotificationItem[]>('/api/notifications').catch(() => []);
}

export async function markNotificationRead(id: number): Promise<void> {
  await apiFetch(`/api/notifications/${id}/read`, { method: 'POST' });
}

export async function markAllNotificationsRead(): Promise<void> {
  await apiFetch('/api/notifications/read-all', { method: 'POST' }).catch(() => undefined);
}

export async function deleteNotification(id: number): Promise<boolean> {
  try {
    const res = await apiFetch(`/api/notifications/${id}`, { method: 'DELETE' });
    const data = await res.json() as { deleted?: boolean };
    return data.deleted === true;
  } catch {
    return false;
  }
}

// ===== 定时/盯守任务（Agent 雷达页） =====

export async function fetchTasks(options: { type?: 'REMINDER' | 'AGENT' } = {}): Promise<ScheduledTaskItem[]> {
  const params = new URLSearchParams();
  if (options.type) params.set('type', options.type);
  const suffix = params.size > 0 ? `?${params}` : '';
  return getJson<ScheduledTaskItem[]>(`/api/tasks${suffix}`).catch(() => []);
}

export async function pauseTask(id: number): Promise<boolean> {
  const res = await apiFetch(`/api/tasks/${id}/pause`, { method: 'POST' });
  if (!res.ok) return false;
  return ((await res.json()) as { updated: boolean }).updated;
}

export async function resumeTask(id: number): Promise<boolean> {
  const res = await apiFetch(`/api/tasks/${id}/resume`, { method: 'POST' });
  if (!res.ok) return false;
  return ((await res.json()) as { updated: boolean }).updated;
}

export async function cancelTask(id: number): Promise<boolean> {
  const res = await apiFetch(`/api/tasks/${id}/cancel`, { method: 'POST' });
  if (!res.ok) return false;
  return ((await res.json()) as { updated: boolean }).updated;
}

// ===== 待确认的高风险工具（Phase 5 前端确认） =====

export interface PendingToolResponse {
  pending: PendingToolInfo | null;
}

export interface PendingActResponse {
  status: string;
  reply: string;
  rawResult?: string;
  /** trace id linking to the WAIT_CONFIRM tool trace, for in-place update */
  traceId?: string;
  /** target trace state after confirm/cancel: confirm -> ok, cancel -> err */
  traceState?: 'ok' | 'err';
}

export async function fetchPendingTool(): Promise<PendingToolResponse> {
  return getJson<PendingToolResponse>('/api/tools/pending').catch(() => ({ pending: null }));
}

export async function confirmPendingTool(): Promise<PendingActResponse> {
  const res = await apiFetch('/api/tools/pending/confirm', { method: 'POST' });
  if (!res.ok) throw new Error((await readError(res)) || '确认失败，请稍后再试');
  return (await res.json()) as PendingActResponse;
}

export async function cancelPendingTool(): Promise<PendingActResponse> {
  const res = await apiFetch('/api/tools/pending/cancel', { method: 'POST' });
  if (!res.ok) throw new Error((await readError(res)) || '取消失败，请稍后再试');
  return (await res.json()) as PendingActResponse;
}

// ============ 用户资料（邮箱提醒设置） ============

export interface UserProfileResponse {
  email: string;
  emailNotificationsEnabled: boolean;
  notificationsEnabled: boolean;
}

export async function fetchUserProfile(): Promise<UserProfileResponse> {
  return getJson<UserProfileResponse>('/api/profile');
}

export async function setUserEmail(email: string): Promise<{ success: boolean; email?: string; error?: string }> {
  const res = await apiFetch('/api/profile/email', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email }),
  });
  return await res.json();
}

export async function clearUserEmail(): Promise<{ success: boolean }> {
  const res = await apiFetch('/api/profile/email', { method: 'DELETE' });
  return await res.json();
}

export async function setEmailNotificationsEnabled(enabled: boolean): Promise<{ success: boolean; emailNotificationsEnabled?: boolean }> {
  const res = await apiFetch('/api/profile/email-notifications', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled }),
  });
  return await res.json();
}

async function readError(res: Response): Promise<string> {
  try {
    const data = (await res.json()) as { error?: string; message?: string; detail?: string };
    return data.error || data.message || data.detail || '';
  } catch {
    return '';
  }
}

// ===== 生成图片看板（换风格重画） =====

export type ImageStyleKey = 'SHINKAI' | 'FLAT' | 'INK' | 'REALISTIC';

/** 与后端 ImageBoardController.ImageStyle 枚举对齐 */
export const IMAGE_STYLE_OPTIONS: { key: ImageStyleKey; label: string }[] = [
  { key: 'SHINKAI', label: '新海诚风' },
  { key: 'FLAT', label: '扁平插画风' },
  { key: 'INK', label: '水墨' },
  { key: 'REALISTIC', label: '写实' },
];

export async function fetchArtifacts(limit = 50): Promise<Artifact[]> {
  return getJson<Artifact[]>(`/api/artifacts?limit=${limit}`).catch(() => []);
}

/** 以某张图的原 prompt 换风格重画；生成约 20-60s，无内建超时（后端 90s 兜底） */
export async function generateStyledImage(body: {
  sourceArtifactId?: string;
  prompt?: string;
  style?: ImageStyleKey;
}): Promise<Artifact> {
  const res = await apiFetch('/api/workspace/images/generate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const data = (await res.json().catch(() => ({}))) as { artifact?: Artifact; error?: string };
  if (!res.ok || !data.artifact) throw new Error(data.error || `生成失败（HTTP ${res.status}）`);
  return data.artifact;
}

// ============ 工作台 ============

export interface BoardItemResponse {
  seq: number;
  title: string;
  time: string;
  status: string;
  note?: string;
}

export interface DayGroupResponse {
  label: string;
  items: BoardItemResponse[];
}

export interface BoardResponse {
  optionId: string;
  title: string;
  stats: string;
  days: DayGroupResponse[];
}

// 获取工作台看板列表
export async function getWorkspaceBoards(): Promise<BoardResponse[]> {
  try {
    const res = await apiFetch('/api/workspace/boards');
    if (!res.ok) return [];
    return await res.json();
  } catch {
    return [];
  }
}

// 更新看板行程项
export async function updateBoardItem(
  optionId: string,
  day: string,
  seq: number,
  updates: Partial<{ title: string; time: string; status: string; note: string }>
): Promise<{ status: string; updated: BoardItemResponse }> {
  const res = await apiFetch(`/api/workspace/boards/${encodeURIComponent(optionId)}/items`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ day, seq, ...updates }),
  });
  return await res.json();
}
