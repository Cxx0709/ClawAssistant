import { useState } from 'react';
import { countdownFromDateString, repeatLabel } from '../lib/format';
import { cancelTask, pauseTask, resumeTask } from '../lib/api';
import type { ScheduledTaskItem } from '../lib/types';

interface WatchTaskCardProps {
  task: ScheduledTaskItem;
  onChanged: () => void;
}

/** 雷达页②区盯守卡：AGENT 型定时任务，展示节奏 + 下次执行倒计时 + 暂停/恢复/取消。 */
export default function WatchTaskCard({ task, onChanged }: WatchTaskCardProps) {
  const [busy, setBusy] = useState(false);
  const paused = task.status === 'PAUSED';
  const failed = task.status === 'FAILED' || task.failureCount > 0;

  const act = async (kind: 'pause' | 'resume' | 'cancel') => {
    setBusy(true);
    const ok = kind === 'pause' ? await pauseTask(task.id)
      : kind === 'resume' ? await resumeTask(task.id)
      : await cancelTask(task.id);
    setBusy(false);
    if (ok) onChanged();
  };

  return (
    <div className={`group relative overflow-hidden rounded-2xl border p-4 transition-colors ${
      paused ? 'border-line bg-canvas-sub' : 'border-line bg-canvas hover:bg-canvas-sub'
    }`}>
      <span aria-hidden="true"
        className={`absolute left-0 top-0 h-full w-1 ${paused ? 'bg-ink-faint/40' : failed ? 'bg-[#e5484d]' : 'bg-brand'}`} />
      <div className="flex items-start justify-between gap-2">
        <div className="flex min-w-0 items-center gap-2">
          <Heartbeat paused={paused} failed={failed} />
          <h3 className="min-w-0 truncate text-sm font-medium text-ink" title={task.content}>{task.content}</h3>
        </div>
        {failed && <span className="shrink-0 rounded-md bg-[#fdeaea] px-1.5 py-0.5 text-[11px] text-[#e5484d]">连续失败 {task.failureCount} 次</span>}
      </div>
      <p className="mt-2.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-ink-soft">
        <span>{repeatLabel(task.repeatType, task.repeatInterval)}</span>
        {task.repeatType !== 'NONE' && task.repeatType !== 'ONCE' && task.nextExecuteTime && (
          <span className="font-mono tabular-nums">{countdownFromDateString(task.nextExecuteTime)}汇报</span>
        )}
        {task.repeatType === 'NONE' && task.executeTime && (
          <span className="font-mono tabular-nums">{formatShort(task.executeTime)}</span>
        )}
        {paused && <span className="text-ink-faint">已暂停</span>}
      </p>
      <div className="mt-3 flex items-center gap-2 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
        {paused ? (
          <CardButton label="恢复" disabled={busy} onClick={() => void act('resume')} />
        ) : (
          <CardButton label="暂停" disabled={busy} onClick={() => void act('pause')} />
        )}
        <CardButton label="取消盯守" disabled={busy} onClick={() => void act('cancel')} />
      </div>
    </div>
  );
}

function CardButton({ label, disabled, onClick }: { label: string; disabled: boolean; onClick: () => void }) {
  return (
    <button type="button" disabled={disabled} onClick={onClick}
      className="rounded-md border border-line px-2 py-1 text-[11px] text-ink-soft transition-colors hover:bg-canvas-sub disabled:cursor-not-allowed disabled:opacity-50 focus-visible:outline-brand">
      {label}
    </button>
  );
}

function Heartbeat({ paused, failed }: { paused: boolean; failed: boolean }) {
  return (
    <span className="relative flex h-2.5 w-2.5 shrink-0" aria-hidden="true">
      {!paused && !failed && <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-brand/40" />}
      <span className={`relative inline-flex h-2.5 w-2.5 rounded-full ${
        failed ? 'bg-[#e5484d]' : paused ? 'bg-ink-faint' : 'bg-brand'
      }`} />
    </span>
  );
}

function formatShort(value: string): string {
  const ts = new Date(value.replace(' ', 'T'));
  if (Number.isNaN(ts.getTime())) return value;
  return ts.toLocaleString('zh-CN', { hour12: false, month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}
