import { useCallback, useEffect, useRef, useState } from 'react';
import {
  fetchNotifications,
  fetchPendingTool,
  fetchTasks,
  markAllNotificationsRead,
} from '../lib/api';
import type { NotificationItem, PendingToolInfo, ScheduledTaskItem } from '../lib/types';
import PendingConfirmCard from '../components/PendingConfirmCard';
import WatchTaskCard from '../components/WatchTaskCard';
import ActivityTimeline from '../components/ActivityTimeline';

interface AgentRadarPageProps {
  onBack: () => void;
  /** 跳到调试视图（执行追踪） */
  onOpenDebug: () => void;
}

const REFRESH_MS = 20_000;

/** Agent 雷达（客户版）：待你处理 → 正在盯守 → Agent 的发现。 */
export default function AgentRadarPage({ onBack, onOpenDebug }: AgentRadarPageProps) {
  const rootRef = useRef<HTMLDivElement>(null);
  const backRef = useRef<HTMLButtonElement>(null);
  const [pending, setPending] = useState<PendingToolInfo | null>(null);
  const [watchTasks, setWatchTasks] = useState<ScheduledTaskItem[]>([]);
  const [reminders, setReminders] = useState<ScheduledTaskItem[]>([]);
  const [notifications, setNotifications] = useState<NotificationItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [refreshTick, setRefreshTick] = useState(0);

  const refresh = useCallback(() => setRefreshTick(value => value + 1), []);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    void (async () => {
      const [pendingRes, tasks, notificationsRes] = await Promise.all([
        fetchPendingTool(),
        fetchTasks(),
        fetchNotifications(),
      ]);
      if (!alive) return;
      setPending(pendingRes.pending);
      setWatchTasks(tasks.filter(task => task.taskType === 'AGENT'
        && (task.status === 'ACTIVE' || task.status === 'RUNNING' || task.status === 'PAUSED' || task.status === 'FAILED')));
      setReminders(tasks.filter(task => task.taskType === 'REMINDER' && (task.status === 'ACTIVE' || task.status === 'RUNNING')));
      setNotifications(notificationsRes);
      setError('');
      setLoading(false);
    })();
    return () => { alive = false; };
  }, [refreshTick]);

  // 打开期间低频轮询，盯守状态与通知近实时
  useEffect(() => {
    const timer = window.setInterval(refresh, REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [refresh]);

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

  const unreadCount = notifications.filter(item => item.status === 'UNREAD').length;

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
              <h1 id="radar-title" className="flex items-center gap-2 text-lg font-semibold">
                Agent 雷达
                <LiveDot active={watchTasks.some(task => task.status !== 'PAUSED')} />
              </h1>
              <p className="text-xs text-ink-soft">Agent 正在替你盯着的事，都汇总在这里</p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            {unreadCount > 0 && (
              <button type="button" onClick={() => { void markAllNotificationsRead().then(refresh); }}
                className="rounded-lg border border-line px-3 py-2 text-xs text-ink-soft hover:bg-canvas-sub focus-visible:outline-brand">
                全部已读（{unreadCount}）
              </button>
            )}
            <button type="button" onClick={refresh} disabled={loading}
              className="rounded-lg border border-line px-3 py-2 text-sm hover:bg-canvas-sub disabled:cursor-not-allowed disabled:opacity-50 focus-visible:outline-brand">
              {loading ? '刷新中…' : '刷新'}
            </button>
          </div>
        </div>
      </header>

      <main className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
        <div className="mx-auto max-w-4xl space-y-8 px-4 py-6 sm:px-8">
          {error && <div role="alert" className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">
            {error}。请点击"刷新"重试。
          </div>}

          {/* ① 待你处理 */}
          {pending && <PendingConfirmCard pending={pending} onResolved={refresh} />}

          {/* ② 正在盯守 */}
          <section aria-labelledby="watch-title">
            <h2 id="watch-title" className="mb-3 text-sm font-medium text-ink">正在盯守</h2>
            {loading && <p role="status" className="py-4 text-sm text-ink-soft">正在读取盯守任务…</p>}
            {!loading && watchTasks.length === 0 && (
              <div className="rounded-2xl border border-dashed border-line px-4 py-10 text-center">
                <p className="text-sm font-medium text-ink">还没有盯守中的任务</p>
                <p className="mt-1.5 text-xs leading-relaxed text-ink-soft">
                  回到聊天对 Agent 说「帮我盯一下……」，<br className="sm:hidden" />
                  比如天气、快递、考试成绩，它就会定时替你查看并汇报。
                </p>
              </div>
            )}
            {!loading && watchTasks.length > 0 && (
              <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                {watchTasks.map(task => (
                  <WatchTaskCard key={task.id} task={task} onChanged={refresh} />
                ))}
              </div>
            )}
            {!loading && reminders.length > 0 && (
              <div className="mt-4 rounded-2xl border border-line/70 bg-canvas-sub px-4 py-3">
                <h3 className="text-xs font-medium text-ink-faint">即将提醒</h3>
                <ul className="mt-2 space-y-1.5">
                  {reminders.slice(0, 5).map(task => (
                    <li key={task.id} className="flex min-w-0 items-baseline gap-3 text-xs">
                      <span className="shrink-0 font-mono tabular-nums text-ink-faint">{shortTime(task.executeTime || task.nextExecuteTime)}</span>
                      <span className="min-w-0 truncate text-ink-soft">{task.content}</span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </section>

          {/* ③ Agent 的发现 */}
          <section aria-labelledby="discover-title">
            <h2 id="discover-title" className="mb-3 text-sm font-medium text-ink">Agent 的发现</h2>
            <ActivityTimeline
              notifications={notifications}
              loading={loading}
            />
          </section>

          <p className="border-t border-line/60 pt-4 text-center">
            <button type="button" onClick={onOpenDebug}
              className="text-[11px] text-ink-faint underline-offset-2 hover:text-ink-soft hover:underline focus-visible:outline-brand">
              开发者视图 · 查看执行追踪
            </button>
          </p>
        </div>
      </main>
    </div>
  );
}

function LiveDot({ active }: { active: boolean }) {
  return (
    <span className="relative flex h-2 w-2" aria-hidden="true">
      {active && <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-brand/40" />}
      <span className={`relative inline-flex h-2 w-2 rounded-full ${active ? 'bg-brand' : 'bg-ink-faint'}`} />
    </span>
  );
}

function shortTime(value?: string | null): string {
  if (!value) return '--:--';
  const ts = new Date(value.replace(' ', 'T'));
  if (Number.isNaN(ts.getTime())) return value;
  return ts.toLocaleString('zh-CN', { hour12: false, month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}
