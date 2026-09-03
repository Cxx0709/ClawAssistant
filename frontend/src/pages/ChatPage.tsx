import { useCallback, useEffect, useRef, useState } from 'react';
import BrandMark from '../components/BrandMark';
import Markdown from '../components/Markdown';
import RightRail from '../components/RightRail';
import ToolTrace from '../components/ToolTrace';
import { fetchStatus, uploadArtifact } from '../lib/api';
import { consumeStream } from '../lib/sse';
import type { AppUser, Artifact, ChatMsg, StreamEvent, SystemStatus } from '../lib/types';

const SUGGESTIONS = [
  '帮我规划一趟周末杭州两日游',
  '我今天心情有点烦，能聊聊吗',
  '帮我创建一个「每周跑步 3 次」的目标',
  '记住：咖啡只喝中杯',
];

export default function ChatPage({ onHome, user, onLogout }: {
  onHome: () => void;
  user: AppUser;
  onLogout: () => void;
}) {
  const [messages, setMessages] = useState<ChatMsg[]>([]);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [railOpen, setRailOpen] = useState(false);
  const [railToken, setRailToken] = useState(0);
  const [status, setStatus] = useState<SystemStatus | null>(null);
  const [attachments, setAttachments] = useState<Artifact[]>([]);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState('');

  const abortRef = useRef<AbortController | null>(null);
  const streamIdRef = useRef<string | null>(null);
  const seqRef = useRef(0);
  const toolKeyRef = useRef(0);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const stickRef = useRef(true);

  // 顶栏状态点
  useEffect(() => {
    let alive = true;
    fetchStatus().then((s) => alive && setStatus(s));
    return () => {
      alive = false;
    };
  }, []);

  const scrollToBottom = useCallback((behavior: ScrollBehavior = 'auto') => {
    const el = scrollRef.current;
    if (el) el.scrollTo({ top: el.scrollHeight, behavior });
  }, []);

  const refreshRail = useCallback(() => setRailToken((t) => t + 1), []);

  // 用户是否停在底部；决定后续更新是否自动跟随滚动
  const onScroll = () => {
    const el = scrollRef.current;
    if (!el) return;
    stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 90;
  };

  // 消息/流式增量更新 → 自动跟随到底
  const last = messages[messages.length - 1];
  const watchKey = last
    ? `${messages.length}-${last.role}-${last.content.length}-${last.tools?.length ?? 0}-${!!last.streaming}-${!!last.errorText}`
    : 'idle';
  useEffect(() => {
    if (stickRef.current) scrollToBottom('smooth');
  }, [watchKey, scrollToBottom]);

  const patchStream = useCallback((patch: (m: ChatMsg) => ChatMsg) => {
    const id = streamIdRef.current;
    if (!id) return;
    setMessages((prev) => prev.map((m) => (m.id === id ? patch(m) : m)));
  }, []);

  const toggleTrace = useCallback((id: string) => {
    setMessages((prev) =>
      prev.map((m) => (m.id === id ? { ...m, traceOpen: !m.traceOpen } : m)),
    );
  }, []);

  const handleEvent = useCallback(
    (startedAt: number) => (evt: StreamEvent) => {
      switch (evt.type) {
        case 'skill':
          patchStream((m) => ({
            ...m,
            skills: m.skills && m.skills.includes(evt.name) ? m.skills : [...(m.skills ?? []), evt.name],
          }));
          break;
        case 'tool_start': {
          toolKeyRef.current += 1;
          const key = `t${toolKeyRef.current}`;
          patchStream((m) => ({
            ...m,
            tools: [
              ...(m.tools ?? []),
              { id: key, name: evt.name, skill: evt.skill ?? '', state: 'running' as const },
            ],
          }));
          break;
        }
        case 'tool_end':
          patchStream((m) => {
            const tools = m.tools ?? [];
            // 顺序执行：匹配同名里最后一个仍在运行的实例
            let idx = -1;
            for (let i = tools.length - 1; i >= 0; i--) {
              if (tools[i].state === 'running' && tools[i].name === evt.name) {
                idx = i;
                break;
              }
            }
            if (idx === -1) return m;
            const next = tools.slice();
            next[idx] = {
              ...next[idx],
              state: evt.ok ? 'ok' : 'err',
              durationMs: evt.durationMs,
              detail: evt.detail,
            };
            return { ...m, tools: next };
          });
          break;
        case 'text':
          patchStream((m) => ({ ...m, content: m.content + evt.content }));
          break;
        case 'done':
          // done 携带全文，以它为最终兜底（流式丢字也不漏）
          patchStream((m) => ({
            ...m,
            content: evt.reply || m.content,
            totalMs: Date.now() - startedAt,
            streaming: false,
            artifacts: evt.artifacts ?? [],
          }));
          refreshRail();
          break;
        case 'error':
          patchStream((m) => ({
            ...m,
            errorText: evt.message || '处理失败，请稍后再试',
            streaming: false,
          }));
          refreshRail();
          break;
      }
    },
    [patchStream, refreshRail],
  );

  const send = useCallback(
    (raw: string) => {
      const content = raw.trim();
      if ((!content && attachments.length === 0) || busy || uploading) return;
      const selectedAttachments = attachments;
      setText('');
      setAttachments([]);
      setUploadError('');

      seqRef.current += 1;
      const uid = `u${seqRef.current}`;
      const aid = `a${seqRef.current}`;
      const startedAt = Date.now();

      setMessages((prev) => [
        ...prev,
        { id: uid, role: 'user', content: content || '请处理这些附件', artifacts: selectedAttachments },
        { id: aid, role: 'assistant', content: '', tools: [], skills: [], streaming: true },
      ]);
      streamIdRef.current = aid;
      setBusy(true);

      const ctrl = new AbortController();
      abortRef.current = ctrl;

      consumeStream('/api/webchat/stream', {
        message: content,
        attachmentIds: selectedAttachments.map((item) => item.id),
      }, ctrl.signal, handleEvent(startedAt))
        .catch((err: unknown) => {
          const name = (err as Error)?.name;
          if (name === 'AbortError') return; // 用户主动停止
          patchStream((m) => ({
            ...m,
            errorText: (err as Error)?.message || '网络异常，请稍后再试',
            streaming: false,
          }));
          refreshRail();
        })
        .finally(() => {
          setBusy(false);
          abortRef.current = null;
          streamIdRef.current = null;
        });
    },
    [attachments, busy, handleEvent, patchStream, refreshRail, uploading],
  );

  const uploadFiles = useCallback(async (files: FileList | null) => {
    if (!files?.length || busy) return;
    setUploading(true);
    setUploadError('');
    try {
      const uploaded = await Promise.all(Array.from(files).map(uploadArtifact));
      setAttachments((current) => [...current, ...uploaded]);
    } catch (reason) {
      setUploadError((reason as Error)?.message || '附件上传失败');
    } finally {
      setUploading(false);
    }
  }, [busy]);

  const stop = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  const connected = status?.appReady;

  return (
    <div className="flex h-dvh flex-col bg-canvas text-ink">
      {/* ===== 顶栏 ===== */}
      <header className="flex h-[58px] shrink-0 items-center gap-3 border-b border-line px-3 sm:px-4">
        <button
          type="button"
          onClick={onHome}
          title="返回首页"
          className="flex h-9 w-9 items-center justify-center rounded-lg text-ink-soft transition-colors hover:bg-canvas-sub hover:text-ink"
        >
          <svg viewBox="0 0 24 24" className="h-[18px] w-[18px]" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M15 5l-7 7 7 7" />
          </svg>
        </button>

        <div className="flex items-center gap-2.5">
          <BrandMark size={28} />
          <div className="leading-tight">
            <p className="text-[13.5px] font-semibold">Claw Assistant</p>
            <p className="flex items-center gap-1 text-[11px] text-ink-faint">
              <span className={`inline-block h-1.5 w-1.5 rounded-full ${connected ? 'bg-[#34c759]' : 'bg-ink-faint'}`} />
              {connected ? 'Web 助手在线' : '初始化中'}
            </p>
          </div>
        </div>

        <span className="ml-auto hidden text-xs text-ink-faint sm:inline">{user.displayName}</span>
        <button
          type="button"
          onClick={() => setRailOpen((v) => !v)}
          aria-pressed={railOpen}
          className={`flex h-9 items-center gap-1.5 rounded-lg border px-3 text-[12.5px] font-medium transition-colors ${
            railOpen
              ? 'border-brand/30 bg-brand-dim text-brand-deep'
              : 'border-line text-ink-soft hover:bg-canvas-sub hover:text-ink'
          }`}
        >
          <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <rect x="3" y="4" width="18" height="16" rx="2.5" />
            <path d="M9 4v16M3 9h6M3 14h6" />
          </svg>
          信息
        </button>
        <button type="button" onClick={onLogout} className="h-9 px-2 text-xs text-ink-faint hover:text-ink">退出</button>
      </header>

      {/* ===== 主体 ===== */}
      <div className="relative flex min-h-0 flex-1">
        <main className="flex min-w-0 flex-1 flex-col">
          {/* 线程滚动区 */}
          <div ref={scrollRef} onScroll={onScroll} className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
            <div className="mx-auto w-full max-w-[760px] px-4 pb-10 pt-6 sm:px-6">
              {messages.length === 0 ? <EmptyState onPick={(s) => send(s)} /> : null}
              <MessageList
                messages={messages}
                onToggleTrace={toggleTrace}
              />
            </div>
          </div>

          {/* 输入区 */}
          <div className="shrink-0 border-t border-line/70 bg-gradient-to-t from-canvas via-canvas to-canvas/90 pb-3 pt-3">
            <div className="mx-auto w-full max-w-[760px] px-4 sm:px-6">
              <Composer
                text={text}
                busy={busy}
                onChange={setText}
                onSend={() => send(text)}
                onStop={stop}
                attachments={attachments}
                uploading={uploading}
                uploadError={uploadError}
                onFiles={uploadFiles}
                onRemove={(id) => setAttachments((items) => items.filter((item) => item.id !== id))}
              />
              <p className="mt-2 text-center text-[11px] text-ink-faint">
                Claw 也会犯错，重要信息请以官方渠道为准
              </p>
            </div>
          </div>
        </main>

        {/* 右侧信息抽屉（宽屏占位 / 窄屏悬浮） */}
        {railOpen && (
          <>
            <div
              className="fixed inset-0 z-30 bg-ink/20 backdrop-blur-[1px] lg:hidden"
              onClick={() => setRailOpen(false)}
            />
            <div className="fixed inset-y-0 right-0 z-40 w-[85vw] max-w-[340px] shadow-[-8px_0_30px_-18px_rgba(20,21,23,.25)] lg:static lg:z-auto lg:w-auto lg:max-w-none lg:shrink-0 lg:shadow-none">
              <RightRail refreshToken={railToken} />
            </div>
          </>
        )}
      </div>
    </div>
  );
}

/* ================= 子组件 ================= */

function EmptyState({ onPick }: { onPick: (s: string) => void }) {
  return (
    <div className="flex flex-col items-center pt-[14vh] text-center">
      <BrandMark size={56} />
      <h1 className="mt-5 text-xl font-semibold tracking-tight">今天想让我帮你做点什么？</h1>
      <p className="mt-1.5 text-sm text-ink-soft">
        会记忆、会规划、会主动跟进目标，也能读写图片、语音和文件
      </p>
      <div className="mt-8 flex w-full max-w-[520px] flex-col items-stretch gap-2 sm:flex-row sm:flex-wrap sm:justify-center">
        {SUGGESTIONS.map((s) => (
          <button
            key={s}
            type="button"
            onClick={() => onPick(s)}
            className="rounded-xl border border-line bg-white px-3.5 py-2.5 text-left text-[13px] text-ink-soft transition-all hover:border-brand/40 hover:text-ink hover:shadow-pop"
          >
            {s}
          </button>
        ))}
      </div>
    </div>
  );
}

function MessageList({
  messages,
  onToggleTrace,
}: {
  messages: ChatMsg[];
  onToggleTrace: (id: string) => void;
}) {
  return (
    <div className="space-y-6">
      {messages.map((m) =>
        m.role === 'user' ? (
          <div key={m.id} className="flex flex-col items-end gap-2">
            <p className="max-w-[82%] whitespace-pre-wrap rounded-2xl bg-bubble px-4 py-2.5 text-[14.5px] leading-relaxed text-ink sm:max-w-[68%]">{m.content}</p>
            <ArtifactList artifacts={m.artifacts ?? []} compact />
          </div>
        ) : (
          <div key={m.id} className="min-w-0">
            {m.errorText ? (
              <div className="flex items-start gap-2.5 rounded-xl border border-[#f3c8c8] bg-[#fdf3f3] px-3.5 py-3 text-[13.5px] text-[#c0392b]">
                <svg viewBox="0 0 24 24" className="mt-0.5 h-4 w-4 shrink-0" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                  <circle cx="12" cy="12" r="9" />
                  <path d="M12 8v5M12 16.5v.01" />
                </svg>
                <span>{m.errorText}</span>
              </div>
            ) : null}

            <ToolTrace
              tools={m.tools ?? []}
              skills={m.skills}
              running={!!m.streaming}
              open={!!m.traceOpen}
              totalMs={m.totalMs}
              onToggle={() => onToggleTrace(m.id)}
            />

            {m.streaming && !m.content && !m.errorText ? (
              <TypingDots />
            ) : (
              m.content && <Markdown content={m.content} />
            )}
            <ArtifactList artifacts={m.artifacts ?? []} />
          </div>
        ),
      )}
    </div>
  );
}

function ArtifactList({ artifacts, compact = false }: { artifacts: Artifact[]; compact?: boolean }) {
  if (artifacts.length === 0) return null;
  return (
    <div className={`mt-3 grid gap-2 ${compact ? 'max-w-[82%]' : 'sm:grid-cols-2'}`}>
      {artifacts.map((artifact) => artifact.kind === 'IMAGE' ? (
        <a key={artifact.id} href={artifact.url} target="_blank" rel="noreferrer" className="overflow-hidden rounded-xl border border-line bg-white">
          <img src={artifact.url} alt={artifact.description || artifact.fileName} className="max-h-72 w-full object-contain" />
        </a>
      ) : artifact.kind === 'AUDIO' ? (
        <div key={artifact.id} className="rounded-xl border border-line bg-white p-3">
          <p className="mb-2 truncate text-xs text-ink-soft">{artifact.fileName}</p>
          <audio controls preload="metadata" src={artifact.url} className="h-9 w-full" />
        </div>
      ) : (
        <a key={artifact.id} href={artifact.url} download={artifact.fileName} className="flex items-center gap-2 rounded-xl border border-line bg-white px-3 py-2.5 text-sm text-brand-deep hover:border-brand/50">
          <span aria-hidden="true">↧</span><span className="truncate">{artifact.fileName}</span>
        </a>
      ))}
    </div>
  );
}

function TypingDots() {
  return (
    <div className="mt-2 flex items-center gap-1.5 pl-1">
      {[0, 1, 2].map((i) => (
        <span
          key={i}
          className="h-1.5 w-1.5 rounded-full bg-ink-faint animate-blink"
          style={{ animationDelay: `${i * 180}ms` }}
        />
      ))}
      <span className="ml-1 text-xs text-ink-faint">思考中…</span>
    </div>
  );
}

function Composer({
  text,
  busy,
  onChange,
  onSend,
  onStop,
  attachments,
  uploading,
  uploadError,
  onFiles,
  onRemove,
}: {
  text: string;
  busy: boolean;
  onChange: (v: string) => void;
  onSend: () => void;
  onStop: () => void;
  attachments: Artifact[];
  uploading: boolean;
  uploadError: string;
  onFiles: (files: FileList | null) => void;
  onRemove: (id: string) => void;
}) {
  const ref = useRef<HTMLTextAreaElement | null>(null);
  const fileRef = useRef<HTMLInputElement | null>(null);
  const canSend = (text.trim().length > 0 || attachments.length > 0) && !uploading;

  const autoGrow = () => {
    const el = ref.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      if (!busy && canSend) onSend();
    }
  };

  return (
    <div className="rounded-[24px] border border-line bg-white p-1.5 shadow-composer transition-shadow focus-within:border-brand/50 focus-within:shadow-pop">
      {attachments.length > 0 && (
        <div className="flex flex-wrap gap-1.5 px-2 pb-1.5 pt-1">
          {attachments.map((item) => (
            <span key={item.id} className="flex max-w-[230px] items-center gap-1.5 rounded-lg bg-canvas-sub px-2 py-1 text-xs text-ink-soft">
              <span className="truncate">{item.fileName}</span>
              <button type="button" onClick={() => onRemove(item.id)} className="text-ink-faint hover:text-ink" aria-label={`移除 ${item.fileName}`}>×</button>
            </span>
          ))}
        </div>
      )}
      {uploadError && <p className="px-3 pb-1 text-xs text-[#c0392b]">{uploadError}</p>}
      <div className="flex items-end gap-1.5">
      <input
        ref={fileRef}
        type="file"
        multiple
        className="hidden"
        onChange={(event) => { onFiles(event.target.files); event.target.value = ''; }}
      />
      <button
        type="button"
        onClick={() => fileRef.current?.click()}
        disabled={busy || uploading}
        title="添加图片、语音或文件"
        className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-ink-soft transition hover:bg-canvas-sub disabled:opacity-40"
      >
        {uploading ? '…' : '+'}
      </button>
      <textarea
        ref={ref}
        rows={1}
        value={text}
        placeholder={busy ? 'Agent 正在处理…' : '给 Claw 发消息…'}
        onChange={(e) => {
          onChange(e.target.value);
          autoGrow();
        }}
        onKeyDown={onKeyDown}
        disabled={busy}
        className="max-h-[160px] min-w-0 flex-1 resize-none bg-transparent py-[7px] text-[14.5px] leading-relaxed text-ink outline-none placeholder:text-ink-faint disabled:opacity-60"
      />
      {busy ? (
        <button
          type="button"
          onClick={onStop}
          title="停止生成"
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full border border-line bg-white text-ink-soft transition-colors hover:border-[#e5484d]/40 hover:text-[#e5484d]"
        >
          <svg viewBox="0 0 24 24" className="h-4 w-4" fill="currentColor">
            <rect x="7" y="7" width="10" height="10" rx="1.6" />
          </svg>
        </button>
      ) : (
        <button
          type="button"
          onClick={onSend}
          disabled={!canSend}
          title="发送"
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-brand text-white transition-all enabled:hover:bg-brand-deep disabled:opacity-35"
        >
          <svg viewBox="0 0 24 24" className="h-[18px] w-[18px]" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M12 19V5M6 11l6-6 6 6" />
          </svg>
        </button>
      )}
      </div>
    </div>
  );
}
