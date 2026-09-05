import { useEffect, useRef, useState } from 'react';
import type { ChatMsg } from '../lib/types';
import { executionLabel, executionRecords, executionState } from '../lib/execution';
import { fmtDuration } from '../lib/format';
import ToolTrace from '../components/ToolTrace';

interface AgentRadarPageProps {
  messages: ChatMsg[];
  conversationTitle: string;
  loading: boolean;
  error: string;
  canRefresh: boolean;
  hasOlder: boolean;
  loadingOlder: boolean;
  onRefresh: () => void;
  onLoadOlder: () => void;
  onBack: () => void;
}

export default function AgentRadarPage({ messages, conversationTitle, loading, error, canRefresh,
  hasOlder, loadingOlder, onRefresh, onLoadOlder, onBack }: AgentRadarPageProps) {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const rootRef = useRef<HTMLDivElement>(null);
  const backRef = useRef<HTMLButtonElement>(null);
  const records = executionRecords(messages);
  const activeCount = records.filter(({ message }) => executionState(message) === 'running').length;

  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    backRef.current?.focus();
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); onBack(); }
      if (event.key !== 'Tab') return;
      const controls = rootRef.current?.querySelectorAll<HTMLElement>(
        'button:not(:disabled), a[href], select, input, [tabindex="0"]',
      );
      if (!controls?.length) return;
      const first = controls[0];
      const last = controls[controls.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault(); last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault(); first.focus();
      }
    };
    document.addEventListener('keydown', handleKey);
    return () => {
      document.removeEventListener('keydown', handleKey);
      previous?.focus({ preventScroll: true });
    };
  }, [onBack]);

  return (
    <div ref={rootRef} role="dialog" aria-modal="true" aria-labelledby="radar-title"
      className="fixed inset-0 z-[80] flex flex-col bg-canvas text-ink">
      <header className="shrink-0 border-b border-line px-4 py-4 sm:px-8">
        <div className="mx-auto flex max-w-4xl flex-wrap items-center justify-between gap-3">
          <div className="flex min-w-0 items-center gap-3">
            <button ref={backRef} type="button" onClick={onBack}
              className="shrink-0 rounded-lg border border-line px-3 py-2 text-sm hover:bg-canvas-sub focus-visible:outline-brand">
              ← 返回聊天
            </button>
            <div className="min-w-0">
              <h1 id="radar-title" className="text-lg font-semibold">Agent 雷达</h1>
              <p className="max-w-[45vw] truncate text-xs text-ink-soft sm:max-w-md">{conversationTitle}</p>
            </div>
          </div>
          <button type="button" onClick={onRefresh} disabled={!canRefresh || loading || loadingOlder}
            title={!canRefresh ? '当前执行中的状态会随聊天更新' : '重新读取当前会话最近的执行记录'}
            className="rounded-lg border border-line px-3 py-2 text-sm hover:bg-canvas-sub disabled:cursor-not-allowed disabled:opacity-50">
            {loading ? '正在读取…' : '刷新记录'}
          </button>
        </div>
      </header>
      <main className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
        <div className="mx-auto max-w-4xl px-4 py-6 sm:px-8">
          <p role="status" className="mb-2 text-sm text-ink-soft">
            {activeCount > 0 ? `${activeCount} 个任务执行中 · 跟随当前对话更新` : '当前会话的执行记录'}
          </p>
          <p className="mb-6 text-xs text-ink-faint">展示已加载消息中的记录，点击任务查看工具调用和结果。</p>
          {error && <div role="alert" className="mb-4 rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">
            {error}。可以点击“刷新记录”重试。
          </div>}
          {loading && <p role="status" className="py-8 text-sm text-ink-soft">正在读取执行记录…</p>}
          {!loading && !error && records.length === 0 && (
            <div className="border-y border-line py-16 text-center">
              <h2 className="font-medium">还没有执行记录</h2>
              <p className="mt-2 text-sm text-ink-soft">返回聊天发送一条消息，执行过程会显示在这里。</p>
            </div>
          )}
          {!loading && <div className="divide-y divide-line border-y border-line">
            {records.map(({ message, request }) => {
              const id = message.runId || message.id;
              const expanded = selectedId === id;
              const state = executionState(message);
              const color = state === 'failed' ? 'text-red-700' : state === 'running' ? 'text-brand' : 'text-ink-soft';
              return <section key={id} className="py-1">
                <button type="button" onClick={() => setSelectedId(expanded ? null : id)}
                  aria-expanded={expanded} aria-controls={`execution-${id}`}
                  className="flex w-full min-w-0 items-start gap-3 rounded-lg px-2 py-4 text-left hover:bg-canvas-sub focus-visible:outline-brand">
                  <span aria-hidden="true" className={`mt-1 text-xs ${color}`}>{expanded ? '−' : '+'}</span>
                  <div className="min-w-0 flex-1">
                    <h2 className="break-words text-sm font-medium">{request}</h2>
                    <p className={`mt-2 break-words text-xs ${color}`}>{executionLabel(message)}</p>
                    <p className="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-xs text-ink-faint">
                      <span>{message.createdAt ? new Date(message.createdAt).toLocaleString('zh-CN', { hour12: false }) : '时间未记录'}</span>
                      <span>{message.tools?.length ?? 0} 次工具调用</span>
                      {message.totalMs != null && <span>耗时 {fmtDuration(message.totalMs)}</span>}
                    </p>
                  </div>
                </button>
                {expanded && <div id={`execution-${id}`} className="space-y-4 px-2 pb-5 sm:pl-7">
                  {message.errorText && <p className="break-words rounded-lg bg-red-50 p-3 text-sm text-red-700">{message.errorText}</p>}
                  {(message.tools?.length ?? 0) > 0
                    ? <ToolTrace tools={message.tools ?? []} running={state === 'running'} open totalMs={message.totalMs} onToggle={() => {}} />
                    : <p className="text-sm text-ink-soft">本轮没有记录到工具调用。</p>}
                  {message.content && <div>
                    <h3 className="mb-2 text-xs font-medium text-ink-soft">{state === 'running' ? '当前回复' : '回复内容'}</h3>
                    <p className="whitespace-pre-wrap break-words text-sm leading-relaxed">{message.content}</p>
                  </div>}
                </div>}
              </section>;
            })}
          </div>}
          {hasOlder && <button type="button" onClick={onLoadOlder} disabled={loadingOlder || loading}
            className="mt-6 rounded-lg border border-line px-4 py-2 text-sm hover:bg-canvas-sub disabled:opacity-50">
            {loadingOlder ? '正在加载…' : '加载更早记录'}
          </button>}
        </div>
      </main>
    </div>
  );
}
