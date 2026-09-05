import { useState } from 'react';
import { confirmPendingTool, cancelPendingTool } from '../lib/api';
import type { PendingToolInfo } from '../lib/types';

interface PendingConfirmCardProps {
  pending: PendingToolInfo;
  onResolved: () => void;
}

/** 雷达页①区：Agent 请求人工确认的高风险操作，确认/取消后整卡消失。 */
export default function PendingConfirmCard({ pending, onResolved }: PendingConfirmCardProps) {
  const [busy, setBusy] = useState<'confirm' | 'cancel' | null>(null);
  const [error, setError] = useState('');

  const act = async (kind: 'confirm' | 'cancel') => {
    setBusy(kind);
    setError('');
    try {
      if (kind === 'confirm') await confirmPendingTool();
      else await cancelPendingTool();
      onResolved();
    } catch (err) {
      setError(err instanceof Error ? err.message : '操作失败，请稍后再试');
      setBusy(null);
    }
  };

  return (
    <section aria-label="待你处理"
      className="rounded-2xl border border-[#f0e3c8] bg-[#fdf8ee] p-4 sm:p-5">
      <div className="flex flex-wrap items-center gap-2">
        <span aria-hidden="true" className="flex h-6 w-6 items-center justify-center rounded-full bg-[#FBF0DB] text-[#B97A14]">
          <svg viewBox="0 0 24 24" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round">
            <circle cx="12" cy="12" r="9" />
            <path d="M12 7v5l3 3" />
          </svg>
        </span>
        <h2 className="text-sm font-medium text-ink">待你处理</h2>
        {pending.expireAt && <Countdown expireAt={pending.expireAt} />}
      </div>
      <p className="mt-2.5 text-sm leading-relaxed text-ink">
        Agent 请求执行 <span className="font-medium">{pending.displayName}</span>，需要你确认。
      </p>
      {pending.argsPreview && (
        <p className="mt-1.5 break-words rounded-lg bg-white/70 px-3 py-2 text-xs leading-relaxed text-ink-soft">
          {pending.argsPreview}
        </p>
      )}
      {error && <p role="alert" className="mt-2 text-xs text-red-700">{error}</p>}
      <div className="mt-3 flex items-center gap-2">
        <button type="button" disabled={busy != null} onClick={() => void act('confirm')}
          className="rounded-lg bg-brand px-4 py-1.5 text-sm font-medium text-white transition-colors hover:bg-brand-deep disabled:cursor-not-allowed disabled:opacity-50 focus-visible:outline-brand">
          {busy === 'confirm' ? '正在确认…' : '确认执行'}
        </button>
        <button type="button" disabled={busy != null} onClick={() => void act('cancel')}
          className="rounded-lg border border-line bg-white px-4 py-1.5 text-sm text-ink-soft transition-colors hover:bg-canvas-sub disabled:cursor-not-allowed disabled:opacity-50 focus-visible:outline-brand">
          取消
        </button>
      </div>
    </section>
  );
}

/** 过期倒计时（ISO 时间 → "约 N 分钟后过期"），过期后只是文案变化，后端会自行失效。 */
function Countdown({ expireAt }: { expireAt: string }) {
  const ts = new Date(expireAt).getTime();
  if (Number.isNaN(ts)) return null;
  const diff = ts - Date.now();
  const label = diff <= 0
    ? '即将过期'
    : diff < 3_600_000
      ? `${Math.max(1, Math.floor(diff / 60_000))} 分钟后过期`
      : `${Math.floor(diff / 3_600_000)} 小时后过期`;
  return <span className="rounded-md bg-[#FBF0DB] px-1.5 py-0.5 text-[11px] text-[#B97A14]">{label}</span>;
}
