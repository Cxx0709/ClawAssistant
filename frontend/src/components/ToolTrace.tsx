import { fmtDuration } from '../lib/format';
import type { ToolItem } from '../lib/types';

interface ToolTraceProps {
  tools: ToolItem[];
  skills?: string[];
  /** 流式进行中：始终展开，实时更新 */
  running: boolean;
  /** 用户手动展开/收起 */
  open: boolean;
  totalMs?: number;
  onToggle: () => void;
}

function SpinnerIcon() {
  return (
    <svg viewBox="0 0 24 24" className="h-3.5 w-3.5 animate-spin" aria-hidden="true">
      <circle cx="12" cy="12" r="9" fill="none" stroke="#10a37f" strokeOpacity="0.25" strokeWidth="3" />
      <path d="M21 12a9 9 0 0 0-9-9" fill="none" stroke="#10a37f" strokeWidth="3" strokeLinecap="round" />
    </svg>
  );
}

function CheckIcon() {
  return (
    <span className="trace-icon bg-brand-dim text-brand-deep">
      <svg viewBox="0 0 24 24" className="h-[10px] w-[10px]" fill="none" stroke="currentColor" strokeWidth="3.2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M4.5 12.5l5 5 10-11" />
      </svg>
    </span>
  );
}

function CrossIcon() {
  return (
    <span className="trace-icon bg-[#fdeaea] text-[#e5484d]">
      <svg viewBox="0 0 24 24" className="h-[10px] w-[10px]" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round">
        <path d="M6 6l12 12M18 6L6 18" />
      </svg>
    </span>
  );
}

function StackIcon({ className }: { className?: string }) {
  return (
    <span className={`${className ?? ''} text-ink-faint`}>
      <svg viewBox="0 0 24 24" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        <path d="M12 3l9 5-9 5-9-5 9-5Z" />
        <path d="M3 13l9 5 9-5" />
      </svg>
    </span>
  );
}

function ChevronIcon({ open }: { open: boolean }) {
  return (
    <span className={`text-ink-faint transition-transform duration-200 ${open ? 'rotate-90' : ''}`}>
      <svg viewBox="0 0 24 24" className="h-3 w-3" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
        <path d="M9 5l7 7-7 7" />
      </svg>
    </span>
  );
}

/** 单行工具条目：出现 → 运行中 → ✓/✗ + 耗时。 */
function ToolRow({ tool }: { tool: ToolItem }) {
  const name = tool.name === 'image_recognition' ? '图片识别' : tool.name.replace(/_/g, ' ');
  return (
    <div className="trace-row trace-row-enter">
      {tool.state === 'running' ? (
        <span className="trace-icon"><SpinnerIcon /></span>
      ) : tool.state === 'ok' ? (
        <CheckIcon />
      ) : (
        <CrossIcon />
      )}
      <span className="min-w-0 flex-1">
        <span className="flex items-baseline gap-2">
          <span className="font-mono text-[12.5px] font-medium text-ink">{name}</span>
          <span className="truncate font-mono text-[11px] text-ink-faint">{tool.skill}</span>
        </span>
        {tool.detail && tool.state === 'err' && (
          <span className="mt-0.5 line-clamp-2 block text-xs leading-snug text-ink-soft">{tool.detail}</span>
        )}
      </span>
      <span className="ml-auto pl-3 font-mono text-[11px] tabular-nums text-ink-faint">
        {tool.state === 'running' ? '运行中…' : fmtDuration(tool.durationMs)}
      </span>
    </div>
  );
}

/**
 * Agent 工具调用时间线：
 * 流式中逐条实时出现（运行态 spinner）；结束后若未手动展开，收成一行摘要，可点开展开回看。
 */
export default function ToolTrace({ tools, running, open, totalMs, onToggle }: ToolTraceProps) {
  // A routing event is not a tool call (nor evidence of image recognition).
  if (tools.length === 0) return null;
  const skills = [...new Set(tools.map((tool) => tool.skill).filter((skill) => skill && skill !== 'common'))];

  const expanded = open || running;
  const errCount = tools.filter((t) => t.state === 'err').length;

  // 收起态：摘要一行
  if (!expanded) {
    return (
      <button
        type="button"
        onClick={onToggle}
        className="trace-row group w-full cursor-pointer text-left transition-colors hover:bg-canvas-sub"
        aria-expanded="false"
      >
        <StackIcon />
        <span className="text-[12.5px] text-ink-soft">
          运行了 <span className="font-semibold text-ink">{tools.length}</span> 个工具
          {errCount > 0 && (
            <>
              ，<span className="font-semibold text-[#e5484d]">{errCount}</span> 个失败
            </>
          )}
          {totalMs != null && <span className="font-mono text-ink-faint"> · {fmtDuration(totalMs)}</span>}
        </span>
        <ChevronIcon open={false} />
      </button>
    );
  }

  return (
    <div className="overflow-hidden rounded-xl border border-line/80 bg-canvas-sub">
      {skills.length > 0 && (
        <div className="flex flex-wrap items-center gap-1.5 border-b border-line/70 px-3 pb-2 pt-2.5">
          {skills.map((s) => (
            <span
              key={s}
              className="inline-flex items-center gap-1 rounded-md bg-brand-dim px-1.5 py-0.5 text-[11px] font-medium text-brand-deep"
            >
              <svg viewBox="0 0 24 24" className="h-3 w-3" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M12 2l2.1 6.5L21 9l-5 4 1.8 6.5L12 15l-5.8 4.5L8 13l-5-4 6.9-.5L12 2Z" />
              </svg>
              {s}
            </span>
          ))}
        </div>
      )}
      <div className="px-1 py-1">
        {tools.map((t) => (
          <ToolRow key={t.id} tool={t} />
        ))}
      </div>
    </div>
  );
}
