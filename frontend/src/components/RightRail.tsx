import { useEffect, useState } from 'react';
import {
  fetchActivities,
  fetchGoals,
  fetchMemories,
  fetchNotifications,
  fetchStatus,
  fetchTodaySchedule,
  fetchTasks,
  cancelTask,
  markNotificationRead,
  deleteNotification,
} from '../lib/api';
import {
  activityDotColor,
  categoryLabel,
  goalStatusLabel,
} from '../lib/format';
import type { ActivityItem, GoalItem, MemoryItem, NotificationItem, ScheduledTaskItem, SystemStatus, TodaySchedule } from '../lib/types';

/** 每次对话回合结束 / 抽屉打开时递增，触发重新拉取 */
export interface RailProps {
  refreshToken: number;
}

function Section({ title, children, empty }: { title: string; children?: React.ReactNode; empty?: React.ReactNode }) {
  return (
    <section className="px-4 py-5">
      <h3 className="section-title mb-3">{title}</h3>
      {children ? <div className="space-y-2.5">{children}</div> : <p className="text-xs text-ink-faint">{empty}</p>}
    </section>
  );
}

/** 后端时间戳单位不统一：Instant 序列化出来是 epoch 秒，部分字段是毫秒。统一转毫秒。 */
function toMillis(v: number | string): number {
  const n = typeof v === 'number' ? v : Number(v);
  return Number.isFinite(n) && n < 1e12 ? n * 1000 : n;
}

export default function RightRail({ refreshToken }: RailProps) {
  const [status, setStatus] = useState<SystemStatus | null>(null);
  const [goals, setGoals] = useState<GoalItem[] | null>(null);
  const [memories, setMemories] = useState<MemoryItem[] | null>(null);
  const [activities, setActivities] = useState<ActivityItem[] | null>(null);
  const [notifications, setNotifications] = useState<NotificationItem[] | null>(null);
  const [todaySchedule, setTodaySchedule] = useState<TodaySchedule | null>(null);
  const [reminders, setReminders] = useState<ScheduledTaskItem[] | null>(null);

  useEffect(() => {
    let alive = true;
    fetchStatus().then((s) => alive && setStatus(s));
    fetchGoals().then((g) => alive && setGoals(g));
    fetchMemories().then((m) => alive && setMemories(m));
    fetchActivities().then((a) => alive && setActivities(a));
    fetchNotifications().then((items) => alive && setNotifications(items));
    fetchTodaySchedule().then((schedule) => alive && setTodaySchedule(schedule));
    fetchTasks({ type: 'REMINDER' }).then((items) => alive && setReminders(items.filter((t) => t.status === 'ACTIVE' || t.status === 'RUNNING')));
    return () => {
      alive = false;
    };
  }, [refreshToken]);

  useEffect(() => {
    const source = new EventSource('/api/notifications/stream');
    source.addEventListener('notification', (event) => {
      try {
        const item = JSON.parse((event as MessageEvent).data) as NotificationItem;
        setNotifications((current) => [item, ...(current ?? []).filter((old) => old.id !== item.id)]);
      } catch {
        // 忽略格式不完整的单条事件，下次刷新会从持久化列表补齐。
      }
    });
    return () => source.close();
  }, []);

  const connected = status?.appReady;

  const handleCancelReminder = async (id: number) => {
    try {
      await cancelTask(id);
      setReminders((prev) => (prev ? prev.filter((r) => r.id !== id) : prev));
    } catch {
      // ignore
    }
  };

  const handleDeleteNotification = async (id: number) => {
    try {
      await deleteNotification(id);
      setNotifications((prev) => (prev ? prev.filter((n) => n.id !== id) : prev));
    } catch {
      // ignore
    }
  };

  return (
    <aside className="flex h-full w-full flex-col overflow-hidden border-l border-line bg-canvas-sub/60">
      {/* 状态条 */}
      <div className="flex items-center gap-2.5 border-b border-line px-4 py-3">
        <span className="relative flex h-2 w-2">
          {connected && (
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-[#34c759] opacity-40" />
          )}
          <span
            className={`relative inline-flex h-2 w-2 rounded-full ${connected ? 'bg-[#34c759]' : 'bg-ink-faint'}`}
          />
        </span>
        <div className="min-w-0">
          <p className="text-[13px] font-medium leading-tight text-ink">
            {connected ? 'Web 助手在线' : '服务初始化中'}
          </p>
          <p className="font-mono text-[10.5px] text-ink-faint">{connected ? 'READY' : 'STARTING'}</p>
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto">
        <Section title="通知" empty="暂无通知">
          {todaySchedule && (
            <div className="rounded-lg border border-brand/20 bg-white px-3 py-2.5">
              <div className="flex items-baseline justify-between gap-2">
                <p className="text-[12.5px] font-medium text-ink">📅 今日课表</p>
                {todaySchedule.week > 0 && (
                  <span className="shrink-0 text-[10.5px] text-ink-faint">第{todaySchedule.week}周</span>
                )}
              </div>
              {!todaySchedule.calendarConfigured ? (
                <p className="mt-1.5 text-[11.5px] text-ink-soft">请先设置学期起始日期</p>
              ) : todaySchedule.items.length === 0 ? (
                <p className="mt-1.5 text-[11.5px] text-ink-soft">今天没有课程安排</p>
              ) : (
                <div className="mt-2 space-y-2">
                  {todaySchedule.items.map((course, index) => (
                    <div key={`${course.courseName}-${course.period}-${index}`} className="text-[11.5px] leading-relaxed text-ink-soft">
                      <p className="font-medium text-ink">{course.courseName} <span className="font-normal text-brand-deep">{course.period}</span></p>
                      {(course.classroom || course.teacher) && (
                        <p className="text-[10.5px] text-ink-faint">{[course.classroom, course.teacher].filter(Boolean).join(' · ')}</p>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
          {(notifications ?? []).filter((item) => item.source !== 'COURSE_REMINDER').slice(0, 12).map((item) => (
            <div
              key={item.id}
              className={`group relative w-full rounded-lg border px-3 py-2.5 ${item.status === 'UNREAD' ? 'border-brand/25 bg-brand-dim/50' : 'border-line/70 bg-white'}`}
            >
              <button
                type="button"
                onClick={async () => {
                  if (item.status === 'UNREAD') {
                    await markNotificationRead(item.id);
                    setNotifications((current) => current?.map((entry) => entry.id === item.id ? { ...entry, status: 'READ' } : entry) ?? []);
                  }
                }}
                className="block w-full text-left"
              >
                <p className="flex items-center gap-2 text-[12.5px] font-medium text-ink">
                  {item.status === 'UNREAD' && <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-brand" />}
                  <span className="truncate">{item.title}</span>
                </p>
                <p className="mt-1 line-clamp-3 text-[11.5px] leading-relaxed text-ink-soft">{item.content}</p>
                <p className="mt-1 font-mono text-[10px] text-ink-faint">{new Date(toMillis(item.createdAt)).toLocaleString()}</p>
              </button>
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  void handleDeleteNotification(item.id);
                }}
                className="absolute right-2 top-2 rounded-md p-1 text-ink-faint opacity-0 transition-all hover:bg-red-50 hover:text-red-600 group-hover:opacity-100"
                aria-label="删除通知"
                title="删除通知"
              >
                <svg viewBox="0 0 24 24" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                  <path d="M18 6 6 18M6 6l12 12" />
                </svg>
              </button>
            </div>
          ))}
        </Section>

        <div className="mx-4 border-t border-line/70" />

        <Section title="目标" empty="暂无进行中的目标">
          {(goals ?? []).map((g) => {
            const pct = Math.max(0, Math.min(100, Number(g.progress) || 0));
            return (
              <div key={g.id} className="rounded-lg border border-line/80 bg-white px-3 py-2.5">
                <p className="text-[13px] font-medium leading-snug text-ink">{g.title}</p>
                <div className="mt-2 flex items-center gap-2">
                  <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-line/80">
                    <div
                      className="h-full rounded-full bg-brand transition-[width] duration-500"
                      style={{ width: `${pct}%` }}
                    />
                  </div>
                  <span className="font-mono text-[10.5px] tabular-nums text-ink-faint">{pct}%</span>
                </div>
                <div className="mt-1.5 flex items-center gap-2 text-[10.5px] text-ink-faint">
                  <span className="rounded bg-line/60 px-1 py-px font-medium text-ink-soft">
                    {goalStatusLabel(g.status)}
                  </span>
                  {g.deadline ? <span>截止 {g.deadline}</span> : null}
                </div>
              </div>
            );
          })}
        </Section>

        <div className="mx-4 border-t border-line/70" />

        <Section title="定时提醒" empty="暂无定时提醒">
          {(reminders ?? []).map((r) => (
            <div key={r.id} className="group flex items-start gap-2 rounded-lg bg-white px-3 py-2 shadow-[0_1px_0_rgba(20,21,23,.04)]">
              <div className="min-w-0 flex-1">
                <p className="text-[12.5px] leading-snug text-ink">⏰ {r.content}</p>
                <p className="mt-0.5 font-mono text-[10.5px] tabular-nums text-ink-faint">
                  {r.nextExecuteTime || r.executeTime}
                  {r.repeatType && r.repeatType !== 'NONE' && r.repeatType !== 'ONCE' ? ' · ' + r.repeatType : ''}
                </p>
              </div>
              <button
                type="button"
                onClick={() => void handleCancelReminder(r.id)}
                className="shrink-0 rounded-md p-1 text-ink-soft transition-all hover:bg-red-50 hover:text-red-600"
                aria-label="删除提醒"
                title="删除提醒"
              >
                <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round">
                  <path d="M18 6 6 18M6 6l12 12" />
                </svg>
              </button>
            </div>
          ))}
        </Section>

        <div className="mx-4 border-t border-line/70" />

        <Section title="我的记忆" empty="暂无记忆">
          <a href="?memories" target="_blank" rel="noopener noreferrer" className="block text-xs text-brand-deep hover:underline">查看和管理全部记忆 ↗</a>
          {(memories ?? []).slice(0, 8).map((m) => (
            <div key={m.id} className="rounded-lg bg-white px-3 py-2 shadow-[0_1px_0_rgba(20,21,23,.04)]">
              <p className="line-clamp-2 text-[12.5px] leading-snug text-ink-soft">{m.content}</p>
              <p className="mt-1 flex items-center gap-1 text-[10.5px] text-ink-faint">
                <span className="rounded bg-canvas-sub px-1 py-px">{categoryLabel(m.category)}</span>
                <a href={`?memories&memoryId=${encodeURIComponent(m.id)}`} target="_blank" rel="noopener noreferrer" className="text-brand-deep hover:underline">查看详情</a>
              </p>
            </div>
          ))}
        </Section>

        <div className="mx-4 border-t border-line/70" />

        <Section title="最近活动" empty="暂无活动">
          {(activities ?? []).slice(0, 12).map((a, i) => (
            <div key={i} className="flex items-start gap-2.5">
              <span className={`mt-[5px] h-1.5 w-1.5 shrink-0 rounded-full ${activityDotColor(a.color)}`} />
              <div className="min-w-0 flex-1">
                <p className="line-clamp-2 text-[12.5px] leading-snug text-ink-soft">{a.text}</p>
                <p className="mt-0.5 font-mono text-[10.5px] tabular-nums text-ink-faint">{a.time}</p>
              </div>
            </div>
          ))}
        </Section>
      </div>
    </aside>
  );
}
