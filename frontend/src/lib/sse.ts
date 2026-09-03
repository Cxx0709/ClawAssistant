import type { StreamEvent } from './types';
import { apiFetch } from './api';

/**
 * 用 fetch 发起 POST SSE 流并逐帧解析（EventSource 不支持 POST，故手写）。
 *
 * 帧格式：相邻 data 行以空行分隔；每帧解析一行 JSON 事件。
 * 连接结束（服务端 complete / 网络断开 / abort）时 promise resolve，不抛错。
 *
 * @param onEvent 每收到一帧 JSON 事件回调
 * @returns 在流结束或中止时 resolve 的 Promise
 */
export async function consumeStream(
  url: string,
  body: unknown,
  signal: AbortSignal,
  onEvent: (evt: StreamEvent) => void,
): Promise<void> {
  const res = await apiFetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify(body),
    signal,
  });

  if (!res.ok) {
    let message = `请求失败（HTTP ${res.status}）`;
    try {
      const data = await res.json();
      message = (data && (data.message || data.error)) || message;
    } catch {
      /* 非 JSON 错误体，保留默认文案 */
    }
    throw new Error(message);
  }

  const reader = res.body!.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';

  const dispatch = (block: string) => {
    const lines = block.split(/\r?\n/);
    const dataParts: string[] = [];
    for (const line of lines) {
      const trimmed = line.startsWith('data:') ? line.slice(5) : '';
      const content = trimmed.trim();
      if (content && content !== '[DONE]') dataParts.push(content);
    }
    if (dataParts.length === 0) return;
    const payload = dataParts.join('\n');
    try {
      const evt = JSON.parse(payload) as StreamEvent;
      onEvent(evt);
    } catch {
      // 忽略无法解析的帧（保持连接继续）
    }
  };

  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let sep = buffer.indexOf('\n\n');
    while (sep !== -1) {
      dispatch(buffer.slice(0, sep));
      buffer = buffer.slice(sep + 2);
      sep = buffer.indexOf('\n\n');
    }
  }
  // 末尾残留（无空行结尾）也处理
  if (buffer.trim().length > 0) dispatch(buffer);
}
