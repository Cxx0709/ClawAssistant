import { useEffect, useState } from 'react';
import BrandMark from '../components/BrandMark';
import { fetchStatus } from '../lib/api';
import type { SystemStatus } from '../lib/types';
import type { AppUser } from '../lib/types';

const FEATURES = [
  { icon: 'chat', title: '浏览器即入口', desc: '文本 / 图片 / 语音 / 文件，在一个工作台里直接处理' },
  { icon: 'brain', title: 'ReAct 智能内核', desc: '自主思考、调用 40+ 工具，遇到不确定会反问澄清' },
  { icon: 'pencil', title: '长期记忆', desc: '记住你的口味、规则与重要事实，下次不用重说' },
  { icon: 'flag', title: '目标跟进', desc: '把心愿变成可执行目标，定期检查进度并主动汇报' },
  { icon: 'map', title: '真实世界能力', desc: '天气 / 出行 / 旅行 / 课表 / 文件等生活场景全覆盖' },
  { icon: 'bell', title: '主动触达', desc: '该出发、该提醒的事，它会自己来找你' },
];

const FEATURE_ICONS: Record<string, React.ReactNode> = {
  chat: (
    <path d="M4 6h13a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H9l-4 4v-4H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Z" />
  ),
  brain: (
    <path d="M12 3a3 3 0 0 1 3 3 3 3 0 0 1 3 3 3 3 0 0 1 0 6 3 3 0 0 1-3 3 3 3 0 0 1-3 3 3 3 0 0 1-3-3 3 3 0 0 1-3-3 3 3 0 0 1 0-6 3 3 0 0 1 3-3 3 3 0 0 1 3-3Z" />
  ),
  pencil: (
    <path d="M4 20h4L19.5 8.5a2.1 2.1 0 0 0-3-3L5 17v3ZM14 6l3 3" />
  ),
  flag: (
    <path d="M5 21V4M5 4h11l-2.5 3.5L16 11H5" />
  ),
  map: (
    <>
      <path d="M3 6l6-3 6 3 6-3v15l-6 3-6-3-6 3V6Z" />
      <path d="M9 3v15M15 6v15" />
    </>
  ),
  bell: (
    <path d="M18 8a6 6 0 0 0-12 0c0 7-3 8-3 8h18s-3-1-3-8M10.5 20a2 2 0 0 0 3 0" />
  ),
};

function FeatureIcon({ name }: { name: string }) {
  return (
    <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-brand-dim text-brand-deep">
      <svg
        viewBox="0 0 24 24"
        className="h-[18px] w-[18px]"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        {FEATURE_ICONS[name]}
      </svg>
    </span>
  );
}

export default function Landing({ onStart, onVisualization, user, onLogout }: {
  onStart: () => void;
  onVisualization: () => void;
  user: AppUser;
  onLogout: () => void;
}) {
  const [status, setStatus] = useState<SystemStatus | null>(null);
  const [busyPick, setBusyPick] = useState(false);

  useEffect(() => {
    let alive = true;
    fetchStatus().then((s) => alive && setStatus(s));
    return () => {
      alive = false;
    };
  }, []);

  const connected = status?.appReady;
  const goalCount = status?.activeGoalCount ?? 0;

  return (
    <div className="flex min-h-dvh flex-col overflow-x-hidden bg-canvas text-ink">
      {/* ===== 顶栏 ===== */}
      <header className="mx-auto flex w-full max-w-5xl items-center justify-between px-5 py-5">
        <div className="flex items-center gap-2.5">
          <BrandMark size={34} />
          <span className="text-[15px] font-semibold tracking-tight">Claw Assistant</span>
        </div>
        <div className="flex items-center gap-3">
          <span className="hidden text-sm text-ink-soft sm:inline">{user.displayName}</span>
          <button
            type="button"
            onClick={onVisualization}
            className="text-[13px] text-ink-faint hover:text-ink transition-colors flex items-center gap-1"
            title="查看和管理我的记忆"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
            </svg>
            <span className="hidden sm:inline">我的记忆</span>
          </button>
          <button type="button" onClick={onLogout} className="text-[13px] text-ink-faint hover:text-ink">退出</button>
          <button
            type="button"
            disabled={busyPick}
            onClick={() => {
              setBusyPick(true);
              onStart();
            }}
            className="rounded-full bg-brand px-5 py-2 text-[13.5px] font-medium text-white transition-all hover:bg-brand-deep disabled:opacity-70"
          >
            立即体验
          </button>
        </div>
      </header>

      {/* ===== Hero ===== */}
      <main className="mx-auto w-full max-w-3xl flex-1 px-5 pt-[6vh] text-center">
        <div className="mx-auto w-fit animate-fadeup">
          <BrandMark size={64} />
        </div>
        <p className="mt-7 text-[13px] font-semibold uppercase tracking-[0.18em] text-brand-deep">
          浏览器里的私人智能体
        </p>
        <h1 className="mt-3 text-4xl font-bold leading-[1.15] tracking-tight sm:text-[46px]">
          把复杂任务交给
          <span className="whitespace-nowrap">你的智能助理</span>
        </h1>
        <p className="mx-auto mt-5 max-w-xl text-[15.5px] leading-relaxed text-ink-soft">
          Claw 会像人一样记住你们聊过的事、执行复杂任务、规划长期目标，
          并在需要时主动找你。你现在只需直接告诉它。
        </p>

        <div className="mt-9 flex items-center justify-center gap-3">
          <button
            type="button"
            onClick={onStart}
            className="group inline-flex items-center gap-2 rounded-full bg-brand px-7 py-3 text-[15px] font-medium text-white transition-all hover:bg-brand-deep hover:shadow-pop"
          >
            开始对话
            <svg
              viewBox="0 0 24 24"
              className="h-4 w-4 transition-transform group-hover:translate-x-0.5"
              fill="none"
              stroke="currentColor"
              strokeWidth="2.2"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <path d="M5 12h14M13 6l6 6-6 6" />
            </svg>
          </button>
          <button
            type="button"
            onClick={onStart}
            className="inline-flex items-center gap-2 rounded-full border border-line bg-white px-6 py-3 text-[14.5px] text-ink-soft transition-colors hover:bg-canvas-sub hover:text-ink"
          >
            问个问题试试
          </button>
        </div>

        {/* 在线状态小行 */}
        <p className="mt-8 flex items-center justify-center gap-2 text-[12.5px] text-ink-faint">
          <span className="relative flex h-2 w-2">
            {connected && (
              <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-[#34c759] opacity-40" />
            )}
            <span
              className={`relative inline-flex h-2 w-2 rounded-full ${connected ? 'bg-[#34c759]' : 'bg-ink-faint'}`}
            />
          </span>
          {connected
            ? `Web 助手已就绪${goalCount > 0 ? ` · 追踪 ${goalCount} 个目标` : ''}`
            : '服务正在初始化'}
        </p>
      </main>

      {/* ===== 能力 ===== */}
      <section className="mx-auto w-full max-w-4xl px-5 pb-20 pt-10">
        <div className="grid gap-x-10 gap-y-7 sm:grid-cols-2">
          {FEATURES.map((f) => (
            <div key={f.title} className="flex items-start gap-3.5">
              <FeatureIcon name={f.icon} />
              <div>
                <h3 className="text-[15px] font-semibold">{f.title}</h3>
                <p className="mt-0.5 text-[13.5px] leading-relaxed text-ink-soft">{f.desc}</p>
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* ===== 页脚 ===== */}
      <footer className="border-t border-line py-6 text-center text-[12px] text-ink-faint">
        Claw Assistant · 会记忆 · 会执行 · 会主动跟进
      </footer>
    </div>
  );
}
