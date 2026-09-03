import type {
  ActivityItem,
  AppUser,
  Artifact,
  GoalItem,
  MemoryItem,
  NotificationItem,
  SystemStatus,
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

export async function uploadArtifact(file: File): Promise<Artifact> {
  const body = new FormData();
  body.append('file', file);
  const res = await apiFetch('/api/artifacts', { method: 'POST', body });
  if (!res.ok) throw new Error((await readError(res)) || '上传失败');
  return (await res.json()) as Artifact;
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

export async function fetchNotifications(): Promise<NotificationItem[]> {
  return getJson<NotificationItem[]>('/api/notifications').catch(() => []);
}

export async function markNotificationRead(id: number): Promise<void> {
  await apiFetch(`/api/notifications/${id}/read`, { method: 'POST' });
}

async function readError(res: Response): Promise<string> {
  try {
    const data = (await res.json()) as { error?: string; message?: string; detail?: string };
    return data.error || data.message || data.detail || '';
  } catch {
    return '';
  }
}
