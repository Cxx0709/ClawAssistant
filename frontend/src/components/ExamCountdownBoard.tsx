import { useCallback, useEffect, useState } from 'react';
import { fetchUpcomingExams } from '../lib/api';
import type { ExamItem } from '../lib/types';
import EmptyState from './WorkspaceEmptyState';

interface ExamCountdownBoardProps {
  refreshToken?: number;
}

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

function parseExamDate(value: string): Date | null {
  const date = new Date(`${value}T00:00:00`);
  return Number.isNaN(date.getTime()) ? null : date;
}

function dateLabel(value: string): string {
  const date = parseExamDate(value);
  if (!date) return value;
  return `${date.getMonth() + 1}月${date.getDate()}日 · ${WEEKDAYS[date.getDay()]}`;
}

function compactDate(value: string): { month: string; day: string } {
  const date = parseExamDate(value);
  if (!date) return { month: '考试', day: '--' };
  return { month: `${date.getMonth() + 1}月`, day: String(date.getDate()).padStart(2, '0') };
}

function urgency(daysLeft: number) {
  if (daysLeft <= 7) {
    return {
      accent: 'text-[#ff8c7a]',
      dot: 'bg-[#ff8c7a]',
      soft: 'bg-[#ff8c7a]/12 text-[#a33b2f]',
    };
  }
  if (daysLeft <= 14) {
    return {
      accent: 'text-[#f2c46d]',
      dot: 'bg-[#f2c46d]',
      soft: 'bg-[#f3c96f]/20 text-[#845b12]',
    };
  }
  return {
    accent: 'text-[#b9b7ff]',
    dot: 'bg-[#aaa7ff]',
    soft: 'bg-brand-dim text-brand-deep',
  };
}

function ExamBadge({ exam, inverted = false }: { exam: ExamItem; inverted?: boolean }) {
  return (
    <span className={`shrink-0 rounded-md px-2 py-1 text-[10px] font-semibold tracking-[.08em] ${
      inverted ? 'bg-white/10 text-white/72 ring-1 ring-inset ring-white/10' : 'bg-white text-ink-soft ring-1 ring-inset ring-line'
    }`}>
      {exam.examTypeDisplay}
    </span>
  );
}

function ClockIcon() {
  return (
    <svg viewBox="0 0 20 20" aria-hidden="true" className="h-4 w-4" fill="none">
      <circle cx="10" cy="10" r="7.25" stroke="currentColor" strokeWidth="1.5" />
      <path d="M10 5.8v4.5l2.8 1.7" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function PinIcon() {
  return (
    <svg viewBox="0 0 20 20" aria-hidden="true" className="h-4 w-4" fill="none">
      <path d="M15.5 8.2c0 4.1-5.5 8-5.5 8s-5.5-3.9-5.5-8a5.5 5.5 0 1 1 11 0Z" stroke="currentColor" strokeWidth="1.5" />
      <circle cx="10" cy="8.2" r="1.8" stroke="currentColor" strokeWidth="1.5" />
    </svg>
  );
}

export default function ExamCountdownBoard({ refreshToken = 0 }: ExamCountdownBoardProps) {
  const [items, setItems] = useState<ExamItem[] | null>(null);
  const [error, setError] = useState(false);
  const [retryToken, setRetryToken] = useState(0);

  const load = useCallback(() => {
    let active = true;
    setError(false);
    fetchUpcomingExams()
      .then((next) => { if (active) setItems(next); })
      .catch(() => { if (active) { setItems(null); setError(true); } });
    return () => { active = false; };
  }, []);

  useEffect(() => load(), [load, refreshToken, retryToken]);

  if (error) {
    return (
      <div className="flex flex-col items-center justify-center gap-3 py-12 text-center">
        <p className="text-sm text-ink-soft">考试安排加载失败</p>
        <button
          type="button"
          onClick={() => setRetryToken((value) => value + 1)}
          className="rounded-lg border border-line bg-white px-3 py-1.5 text-xs font-medium text-brand-deep transition-all hover:border-brand/30 hover:bg-brand-dim focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/30 active:translate-y-px"
        >
          重试
        </button>
      </div>
    );
  }

  if (items === null) return <ExamBoardSkeleton />;
  if (items.length === 0) return <EmptyState icon="📅" text="还没有考试安排，发我考试安排文件即可导入" />;

  const [nextExam, ...laterExams] = items;
  return (
    <div className="space-y-6">
      <HeroExam exam={nextExam} />
      {laterExams.length > 0 && (
        <section aria-labelledby="later-exams-title">
          <div className="mb-3 flex items-end justify-between">
            <div>
              <p className="text-[10px] font-semibold tracking-[.14em] text-ink-faint">UP NEXT</p>
              <h4 id="later-exams-title" className="mt-0.5 text-sm font-semibold tracking-[-.01em] text-ink">之后的考试</h4>
            </div>
            <span className="text-[11px] tabular-nums text-ink-faint">共 {laterExams.length} 场</span>
          </div>
          <div className="overflow-hidden rounded-[18px] bg-[#f7f7f8] ring-1 ring-inset ring-black/[.035]">
            {laterExams.map((exam, index) => (
              <ExamRow key={exam.id} exam={exam} last={index === laterExams.length - 1} />
            ))}
          </div>
        </section>
      )}
    </div>
  );
}

function HeroExam({ exam }: { exam: ExamItem }) {
  const colors = urgency(exam.daysLeft);
  const time = [exam.startTime, exam.endTime].filter(Boolean).join('–');
  const countdownLabel = exam.daysLeft === 0 ? '今天' : String(exam.daysLeft);

  return (
    <article className="relative isolate overflow-hidden rounded-[24px] bg-[#202027] px-5 pb-5 pt-5 text-white shadow-[0_22px_50px_-30px_rgba(35,32,62,.75)]">
      <div className="pointer-events-none absolute -right-16 -top-20 -z-10 h-52 w-52 rounded-full bg-brand/25 blur-3xl" />
      <div className="pointer-events-none absolute -bottom-20 -left-16 -z-10 h-40 w-40 rounded-full bg-white/[.055] blur-2xl" />

      <header className="flex items-center justify-between gap-3">
        <span className="flex items-center gap-2 text-[10px] font-semibold tracking-[.14em] text-white/55">
          <span className={`h-1.5 w-1.5 rounded-full ${colors.dot} ${exam.daysLeft === 0 ? 'animate-pulse' : ''}`} />
          NEXT EXAM
        </span>
        <ExamBadge exam={exam} inverted />
      </header>

      <div className="mt-7">
        <p className="text-[11px] text-white/45">距下一场考试</p>
        <div className="mt-1 flex items-end gap-2.5">
          <span className={`font-mono text-[64px] font-semibold leading-[.9] tracking-[-.075em] tabular-nums ${colors.accent}`}>
            {countdownLabel}
          </span>
          <span className="pb-1 text-sm font-medium text-white/72">{exam.daysLeft === 0 ? '开考' : '天'}</span>
        </div>
        <h3 className="mt-5 text-balance text-[21px] font-semibold leading-tight tracking-[-.025em] text-white">
          {exam.courseName}
        </h3>
        <p className="mt-1.5 text-xs font-medium text-white/48">{dateLabel(exam.examDate)}</p>
      </div>

      <div className="relative my-5 border-t border-dashed border-white/15" aria-hidden="true">
        <span className="absolute -left-7 -top-2.5 h-5 w-5 rounded-full bg-canvas" />
        <span className="absolute -right-7 -top-2.5 h-5 w-5 rounded-full bg-canvas" />
      </div>

      <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-xs">
        <div className="min-w-0">
          <dt className="flex items-center gap-1.5 text-white/38"><ClockIcon />考试时间</dt>
          <dd className="mt-1.5 truncate font-mono font-medium tabular-nums text-white/85">{time || '待定'}</dd>
        </div>
        <div className="min-w-0">
          <dt className="flex items-center gap-1.5 text-white/38"><PinIcon />考试地点</dt>
          <dd className="mt-1.5 truncate font-medium text-white/85">{exam.location || '待定'}</dd>
        </div>
      </dl>

      {exam.seatNumber && (
        <div className="mt-4 flex items-center justify-between rounded-xl bg-white/[.065] px-3 py-2.5 ring-1 ring-inset ring-white/[.06]">
          <span className="text-[11px] text-white/42">座位号</span>
          <span className="font-mono text-xs font-semibold tracking-[.08em] text-white/90">{exam.seatNumber}</span>
        </div>
      )}
    </article>
  );
}

function ExamRow({ exam, last }: { exam: ExamItem; last: boolean }) {
  const [expanded, setExpanded] = useState(false);
  const colors = urgency(exam.daysLeft);
  const expandable = Boolean(exam.notes?.trim());
  const date = compactDate(exam.examDate);

  return (
    <button
      type="button"
      disabled={!expandable}
      aria-expanded={expandable ? expanded : undefined}
      onClick={() => expandable && setExpanded((value) => !value)}
      className={`group relative block w-full px-3.5 py-3.5 text-left transition-colors duration-200 hover:bg-white/75 focus-visible:z-10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand/35 active:bg-white disabled:cursor-default ${!last ? 'border-b border-black/[.055]' : ''}`}
    >
      <span className="flex items-center gap-3">
        <span className="flex h-12 w-11 shrink-0 flex-col items-center justify-center rounded-xl bg-white shadow-[0_4px_14px_-10px_rgba(20,21,23,.45)] ring-1 ring-inset ring-black/[.045]">
          <span className="text-[9px] font-semibold tracking-[.08em] text-ink-faint">{date.month}</span>
          <span className="font-mono text-lg font-semibold leading-5 tracking-[-.04em] tabular-nums text-ink">{date.day}</span>
        </span>

        <span className="min-w-0 flex-1">
          <span className="flex items-center gap-2">
            <span className="truncate text-[13px] font-semibold tracking-[-.01em] text-ink">{exam.courseName}</span>
            <ExamBadge exam={exam} />
          </span>
          <span className="mt-1 block truncate text-[11px] text-ink-faint">
            {[exam.startTime, exam.location].filter(Boolean).join(' · ') || '时间地点待定'}
          </span>
        </span>

        <span className="flex shrink-0 items-center gap-1.5">
          <span className={`rounded-lg px-2 py-1 font-mono text-[11px] font-semibold tabular-nums ${colors.soft}`}>
            {exam.daysLeft === 0 ? '今天' : `D−${exam.daysLeft}`}
          </span>
          {expandable && (
            <svg viewBox="0 0 16 16" aria-hidden="true" className={`h-3.5 w-3.5 text-ink-faint transition-transform duration-200 ${expanded ? 'rotate-180' : ''}`} fill="none">
              <path d="m4 6 4 4 4-4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          )}
        </span>
      </span>

      {expanded && (
        <span className="ml-14 mt-3 block border-t border-black/[.055] pt-3 text-xs leading-relaxed text-ink-soft">
          {exam.notes}
        </span>
      )}
    </button>
  );
}

function ExamBoardSkeleton() {
  return (
    <div className="animate-pulse space-y-6" aria-label="正在加载考试安排">
      <div className="h-[22rem] rounded-[24px] bg-[#202027]/10" />
      <div>
        <div className="mb-3 h-8 w-24 rounded-lg bg-canvas-sub" />
        <div className="overflow-hidden rounded-[18px] bg-canvas-sub p-3">
          <div className="h-14 rounded-xl bg-white" />
          <div className="mt-2 h-14 rounded-xl bg-white" />
        </div>
      </div>
    </div>
  );
}
