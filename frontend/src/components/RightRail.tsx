import { useEffect, useState } from 'react';
import {
  fetchActivities,
  fetchGoals,
  fetchMemories,
  fetchNotifications,
  fetchStatus,
  fetchTodaySchedule,
  fetchUserProfile,
  markNotificationRead,
  clearUserEmail,
  setEmailNotificationsEnabled,
  setUserEmail,
} from '../lib/api';
import {
  activityDotColor,
  categoryLabel,
  goalStatusLabel,
} from '../lib/format';
import type { ActivityItem, GoalItem, MemoryItem, NotificationItem, SystemStatus, TodaySchedule } from '../lib/types';

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
  const [email, setEmail] = useState('');
  const [emailEnabled, setEmailEnabled] = useState(true);
  const [emailLoading, setEmailLoading] = useState(true);
  const [emailSaving, setEmailSaving] = useState(false);
  const [emailNotice, setEmailNotice] = useState('');

  useEffect(() => {
    let alive = true;
    fetchStatus().then((s) => alive && setStatus(s));
    fetchGoals().then((g) => alive && setGoals(g));
    fetchMemories().then((m) => alive && setMemories(m));
    fetchActivities().then((a) => alive && setActivities(a));
    fetchNotifications().then((items) => alive && setNotifications(items));
    fetchTodaySchedule().then((schedule) => alive && setTodaySchedule(schedule));
    setEmailLoading(true);
    fetchUserProfile()
      .then((profile) => {
        if (!alive) return;
        setEmail(profile.email);
        setEmailEnabled(profile.emailNotificationsEnabled);
        setEmailNotice('');
      })
      .catch(() => alive && setEmailNotice('邮箱设置加载失败'))
      .finally(() => alive && setEmailLoading(false));
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

  const saveEmail = async () => {
    const normalized = email.trim();
    if (!normalized) {
      setEmailNotice('请输入邮箱地址');
      return;
    }
    setEmailSaving(true);
    setEmailNotice('');
    try {
      const result = await setUserEmail(normalized);
      if (!result.success) {
        setEmailNotice(result.error || '保存失败，请稍后重试');
        return;
      }
      setEmail(result.email || normalized);
      setEmailNotice('邮箱已保存');
    } catch {
      setEmailNotice('保存失败，请稍后重试');
    } finally {
      setEmailSaving(false);
    }
  };

  const removeEmail = async () => {
    setEmailSaving(true);
    setEmailNotice('');
    try {
      await clearUserEmail();
      setEmail('');
      setEmailNotice('绑定邮箱已清除');
    } catch {
      setEmailNotice('清除失败，请稍后重试');
    } finally {
      setEmailSaving(false);
    }
  };

  const toggleEmailNotifications = async () => {
    const next = !emailEnabled;
    setEmailSaving(true);
    setEmailNotice('');
    try {
      const result = await setEmailNotificationsEnabled(next);
      if (!result.success) throw new Error('update failed');
      setEmailEnabled(next);
      setEmailNotice(next ? '邮件提醒已开启' : '邮件提醒已关闭');
    } catch {
      setEmailNotice('更新失败，请稍后重试');
    } finally {
      setEmailSaving(false);
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
            <button
              key={item.id}
              type="button"
              onClick={async () => {
                if (item.status === 'UNREAD') {
                  await markNotificationRead(item.id);
                  setNotifications((current) => current?.map((entry) => entry.id === item.id ? { ...entry, status: 'READ' } : entry) ?? []);
                }
              }}
              className={`w-full rounded-lg border px-3 py-2.5 text-left ${item.status === 'UNREAD' ? 'border-brand/25 bg-brand-dim/50' : 'border-line/70 bg-white'}`}
            >
              <p className="flex items-center gap-2 text-[12.5px] font-medium text-ink">
                {item.status === 'UNREAD' && <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-brand" />}
                <span className="truncate">{item.title}</span>
              </p>
              <p className="mt-1 line-clamp-3 text-[11.5px] leading-relaxed text-ink-soft">{item.content}</p>
              <p className="mt-1 font-mono text-[10px] text-ink-faint">{new Date(toMillis(item.createdAt)).toLocaleString()}</p>
            </button>
          ))}
        </Section>

        <div className="mx-4 border-t border-line/70" />

        <Section title="邮件提醒">
          <div className="rounded-lg border border-line/80 bg-white px-3 py-3">
            <div className="flex items-center justify-between gap-3">
              <div>
                <p className="text-[12.5px] font-medium text-ink">同步发送到邮箱</p>
                <p className="mt-0.5 text-[10.5px] text-ink-faint">站内通知不受此开关影响</p>
              </div>
              <button
                type="button"
                role="switch"
                aria-checked={emailEnabled}
                aria-label="邮件提醒"
                disabled={emailLoading || emailSaving}
                onClick={() => void toggleEmailNotifications()}
                className={`relative h-6 w-11 shrink-0 rounded-full transition-colors disabled:opacity-50 ${emailEnabled ? 'bg-brand' : 'bg-line'}`}
              >
                <span className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow-sm transition-transform ${emailEnabled ? 'translate-x-5' : 'translate-x-0.5'}`} />
              </button>
            </div>
            <label htmlFor="notification-email" className="mt-3 block text-[10.5px] font-medium text-ink-soft">接收邮箱</label>
            <div className="mt-1.5 flex gap-1.5">
              <input
                id="notification-email"
                type="email"
                autoComplete="email"
                value={email}
                disabled={emailLoading || emailSaving}
                onChange={(event) => {
                  setEmail(event.target.value);
                  setEmailNotice('');
                }}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') void saveEmail();
                }}
                placeholder={emailLoading ? '加载中…' : 'name@example.com'}
                className="min-w-0 flex-1 rounded-md border border-line bg-canvas px-2.5 py-2 text-xs text-ink outline-none transition-colors placeholder:text-ink-faint focus:border-brand disabled:opacity-50"
              />
              <button
                type="button"
                disabled={emailLoading || emailSaving}
                onClick={() => void saveEmail()}
                className="rounded-md bg-brand px-2.5 py-2 text-xs font-medium text-white transition-colors hover:bg-brand-deep disabled:opacity-50"
              >
                保存
              </button>
            </div>
            <div className="mt-2 flex min-h-4 items-center justify-between gap-2">
              <p aria-live="polite" className="text-[10.5px] text-ink-faint">{emailNotice}</p>
              {email && (
                <button
                  type="button"
                  disabled={emailSaving}
                  onClick={() => void removeEmail()}
                  className="shrink-0 text-[10.5px] text-ink-faint hover:text-red-600 disabled:opacity-50"
                >
                  清除绑定
                </button>
              )}
            </div>
          </div>
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
