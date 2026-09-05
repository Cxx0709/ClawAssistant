import { useEffect, useMemo, useRef, useState } from 'react';
import { markNotificationRead } from '../lib/api';
import { timeAgo } from '../lib/format';
import type { NotificationItem } from '../lib/types';

interface ActivityTimelineProps {
  notifications: NotificationItem[];
  loading: boolean;
  hasMore?: boolean;
  loadingMore?: boolean;
  onLoadMore?: () => void;
}

function dayKey(ts: number): string {
  const date = new Date(ts);
  const now = new Date();
  if (date.toDateString() === now.toDateString()) return '今天';
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (date.toDateString() === yesterday.toDateString()) return '昨天';
  return `${date.getMonth() + 1}月${date.getDate()}日`;
}

/** 雷达页③区：Agent 的发现 —— 通知时间线，按 今天/昨天/更早 分组，进入视口自动标已读。 */
export default function ActivityTimeline({ notifications, loading, hasMore, loadingMore, onLoadMore }: ActivityTimelineProps) {
  const rowRefs = useRef<Map<number, HTMLDivElement>>(new Map());
  const [readIds, setReadIds] = useState<Set<number>>(new Set());

  const groups = useMemo(() => {
    const map = new Map<string, NotificationItem[]>();
    for (const item of notifications) {
      const key = dayKey(item.createdAt * 1000);
      const list = map.get(key);
      if (list) list.push(item);
      else map.set(key, [item]);
    }
    return [...map.entries()];
  }, [notifications]);

  // 未读项进入视口后静默标记已读（本地去重，失败不提示）
  useEffect(() => {
    if (notifications.length === 0) return;
    const observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        const id = Number((entry.target as HTMLElement).dataset.nid);
        const item = notifications.find(n => n.id === id);
        if (!item || item.status === 'READ' || readIds.has(id)) continue;
        setReadIds(prev => new Set(prev).add(id));
        void markNotificationRead(id).catch(() => {
          setReadIds(prev => { const next = new Set(prev); next.delete(id); return next; });
        });
      }
    }, { rootMargin: '0px 0px -10% 0px' });
    rowRefs.current.forEach(el => observer.observe(el));
    return () => observer.disconnect();
  }, [notifications, readIds]);

  if (loading) {
    return <p role="status" className="py-8 text-sm text-ink-soft">正在读取 Agent 的发现…</p>;
  }

  if (notifications.length === 0) {
    return (
      <div className="rounded-2xl border border-dashed border-line py-12 text-center">
        <p className="text-sm font-medium text-ink">Agent 还没有新发现</p>
        <p className="mt-1.5 text-xs text-ink-soft">在聊天里让 Agent 帮你盯点什么，结果会汇报到这里。</p>
      </div>
    );
  }

  return (
    <div className="relative">
      <span aria-hidden="true" className="absolute bottom-3 left-[7px] top-2 w-px bg-line" />
      <div className="space-y-6">
        {groups.map(([label, items]) => (
          <section key={label}>
            <h3 className="mb-2 pl-6 text-xs font-medium text-ink-faint">{label}</h3>
            <div className="space-y-1">
              {items.map(item => {
                const unread = item.status === 'UNREAD' && !readIds.has(item.id);
                return (
                  <div key={item.id} data-nid={item.id} ref={el => { if (el) rowRefs.current.set(item.id, el); }}
                    className="relative flex gap-3 rounded-xl px-2 py-2.5 transition-colors hover:bg-canvas-sub">
                    <span aria-hidden="true"
                      className={`absolute left-[3.5px] top-[19px] h-2 w-2 rounded-full ${unread ? 'bg-brand' : 'bg-line'}`} />
                    <span className="ml-4 w-10 shrink-0 pt-0.5 text-right font-mono text-[11px] tabular-nums text-ink-faint">
                      {new Date(item.createdAt * 1000).toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit' })}
                    </span>
                    <div className="min-w-0 flex-1">
                      <p className={`break-words text-sm leading-relaxed ${unread ? 'text-ink' : 'text-ink-soft'}`}>
                        {item.title}
                      </p>
                      {item.content && item.content !== item.title && (
                        <p className="mt-0.5 break-words text-xs leading-relaxed text-ink-faint">{item.content}</p>
                      )}
                      <p className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-[11px] text-ink-faint">
                        <span>{item.source}</span>
                        <span>·</span>
                        <span>{timeAgo(item.createdAt * 1000)}</span>
                        {item.priority >= 3 && <span className="rounded bg-[#fdeaea] px-1 text-[#e5484d]">重要</span>}
                      </p>
                    </div>
                  </div>
                );
              })}
            </div>
          </section>
        ))}
      </div>
      {hasMore && (
        <button type="button" onClick={onLoadMore} disabled={loadingMore}
          className="mt-5 ml-6 rounded-lg border border-line px-4 py-1.5 text-xs text-ink-soft hover:bg-canvas-sub disabled:opacity-50 focus-visible:outline-brand">
          {loadingMore ? '正在加载…' : '更早的发现'}
        </button>
      )}
    </div>
  );
}
