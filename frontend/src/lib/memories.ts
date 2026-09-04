import { apiFetch } from './api';

export const memoryCategories = {
  PREFERENCE: '个人偏好', RULE: '交流习惯', FACT: '个人信息', GOAL: '目标计划', EXPERIENCE: '经历经验',
} as const;
export type MemoryCategory = keyof typeof memoryCategories;
export interface PersonalMemory {
  id: string;
  category: MemoryCategory;
  content: string;
  evidence: string;
  source: 'AUTO' | 'MANUAL';
  createdAt: string;
  updatedAt: string;
  disabled: boolean;
  sourceConversationId: string | null;
}
export interface MemoryCollection { items: PersonalMemory[]; enabled: boolean }
export interface MemoryChange { id: number; action: 'ADDED' | 'UPDATED'; memory: PersonalMemory }

export async function memoryRequest<T>(path = '', init: RequestInit = {}): Promise<T> {
  const response = await apiFetch(`/api/memories${path}`, {
    ...init, headers: { 'Content-Type': 'application/json', ...init.headers },
    signal: init.signal ? AbortSignal.any([init.signal, AbortSignal.timeout(45000)]) : AbortSignal.timeout(45000),
  });
  if (!response.ok) {
    const messages: Record<number, string> = {
      400: '请检查内容和分类，记忆最多支持 500 字',
      401: '登录已过期，请重新登录',
      404: '这条记忆或变更已不存在，请刷新列表',
      409: '记忆已被更新，请刷新后再操作',
      503: '记忆服务暂时不可用，请稍后重试',
    };
    throw new Error(messages[response.status] || '操作未完成，请稍后重试');
  }
  if (!response.headers.get('content-type')?.includes('application/json')) {
    throw new Error('记忆接口尚未就绪，请更新并重启后端');
  }
  return response.json() as Promise<T>;
}

export const getMemories = (signal?: AbortSignal) => memoryRequest<MemoryCollection>('', { signal });
export const getMemoryChanges = (conversationId: string, signal?: AbortSignal) =>
  memoryRequest<MemoryChange[]>(`/changes?conversationId=${encodeURIComponent(conversationId)}`, { signal });
export const undoMemoryChange = (id: number) => memoryRequest(`/changes/${id}/undo`, { method: 'POST' });

export function memoryDate(date: string) {
  return new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric', year: 'numeric' }).format(new Date(date));
}
