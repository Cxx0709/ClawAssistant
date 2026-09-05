import type { PendingToolInfo } from '../lib/types';

interface PendingConfirmationProps {
  pending: PendingToolInfo;
  busy: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

/**
 * 高风险工具确认卡片：当 SafetyPolicy 拦截了 create/update/cancel 定时任务等
 * 高风险工具并生成待确认操作后，在输入区上方展示，提供「执行 / 取消」按钮。
 */
export default function PendingConfirmation({ pending, busy, onConfirm, onCancel }: PendingConfirmationProps) {
  return (
    <div className="mb-2 flex items-start gap-3 rounded-xl border border-[#e7c26f] bg-[#fdf8ec] px-3.5 py-3">
      <svg
        viewBox="0 0 24 24"
        className="mt-0.5 h-4 w-4 shrink-0 text-[#b47a1f]"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        <path d="M12 9v4M12 16.5v.01" />
        <path d="M10.3 3.9 2.6 17a2 2 0 0 0 1.7 3h15.4a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z" />
      </svg>
      <div className="min-w-0 flex-1">
        <p className="text-[13.5px] text-ink">
          需要你确认：<span className="font-semibold">{pending.displayName || pending.toolName.replace(/_/g, ' ')}</span>
        </p>
        {pending.argsPreview && (
          <p className="mt-0.5 truncate font-mono text-[11.5px] text-ink-soft">{pending.argsPreview}</p>
        )}
        <p className="mt-0.5 text-[11.5px] text-ink-faint">该操作属于高风险操作，需人工确认后才执行，5 分钟内有效。</p>
        <div className="mt-2 flex gap-2">
          <button
            type="button"
            onClick={onConfirm}
            disabled={busy}
            className="rounded-lg bg-brand px-3 py-1.5 text-[12.5px] font-medium text-white transition-colors hover:bg-brand-deep disabled:opacity-50"
          >
            {busy ? '处理中…' : '执行'}
          </button>
          <button
            type="button"
            onClick={onCancel}
            disabled={busy}
            className="rounded-lg border border-line bg-white px-3 py-1.5 text-[12.5px] font-medium text-ink-soft transition-colors hover:text-ink disabled:opacity-50"
          >
            取消
          </button>
        </div>
      </div>
    </div>
  );
}
