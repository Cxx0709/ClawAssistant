import { useEffect, useMemo, useState } from 'react';
import ArtifactCard from './ArtifactCard';
import type { Artifact } from '../lib/types';
import ExamCountdownBoard from './ExamCountdownBoard';
import ImageBoard from './ImageBoard';
import Markdown from './Markdown';
import { fetchArtifactPreview } from '../lib/api';
import { normalizeDocumentPreview } from '../lib/documentPreview';
import WorkspaceEmptyState from './WorkspaceEmptyState';

export { default as EmptyState } from './WorkspaceEmptyState';

type TabKey = 'images' | 'files' | 'exams';

const TABS: { key: TabKey; label: string; icon: string }[] = [
  { key: 'images', label: '生成图片', icon: '🎨' },
  { key: 'files', label: '文件', icon: '📁' },
  { key: 'exams', label: '考试倒计时', icon: '📅' },
];

export default function WorkspaceCanvas({
  artifacts,
  refreshToken = 0,
  focusedArtifactId = null,
  fileGeneration = { active: false, startedAt: 0 },
}: {
  artifacts: Artifact[];
  refreshToken?: number;
  focusedArtifactId?: string | null;
  fileGeneration?: { active: boolean; startedAt: number };
}) {
  const [activeTab, setActiveTab] = useState<TabKey>('images');
  const [selectedFileId, setSelectedFileId] = useState<string | null>(focusedArtifactId);
  const visibleArtifacts = artifacts.filter((artifact) =>
    activeTab === 'images' ? artifact.kind === 'IMAGE' : artifact.kind !== 'IMAGE',
  );
  const files = useMemo(() => artifacts.filter((artifact) => artifact.kind === 'FILE'), [artifacts]);
  const selectedFile = files.find((artifact) => artifact.id === selectedFileId) ?? files[0];

  useEffect(() => {
    if (!focusedArtifactId) return;
    setActiveTab('files');
    setSelectedFileId(focusedArtifactId);
  }, [focusedArtifactId]);

  useEffect(() => {
    if (fileGeneration.active) setActiveTab('files');
  }, [fileGeneration.active, fileGeneration.startedAt]);

  return (
    <div className="flex h-full flex-col bg-[var(--color-canvas)]">
      {/* Header */}
      <div className="flex items-center justify-between border-b border-[var(--color-border)] px-4 py-3">
        <h3 className="text-sm font-semibold text-[var(--color-text)]">工作台</h3>
        <span className="flex items-center gap-1.5 text-xs text-[var(--color-text-muted)]">
          <span className="inline-block h-2 w-2 animate-pulse rounded-full bg-[var(--color-ok)]" />
          实时联动
        </span>
      </div>

      {/* Tabs */}
      <div className="flex border-b border-[var(--color-border)]">
        {TABS.map((tab) => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            className={`flex-1 px-3 py-2.5 text-xs font-medium transition-colors ${
              activeTab === tab.key
                ? 'border-b-2 border-[var(--color-accent)] text-[var(--color-accent)]'
                : 'text-[var(--color-text-muted)] hover:text-[var(--color-text)]'
            }`}
          >
            <span className="mr-1">{tab.icon}</span>
            {tab.label}
          </button>
        ))}
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto p-4">
        {activeTab === 'exams' ? (
          <ExamCountdownBoard refreshToken={refreshToken} />
        ) : activeTab === 'images' ? (
          <ImageBoard
            artifacts={artifacts.filter((artifact) => artifact.kind === 'IMAGE')}
            refreshToken={refreshToken}
            focusedArtifactId={focusedArtifactId}
          />
        ) : activeTab === 'files' && fileGeneration.active ? (
          <FileGeneratingBoard startedAt={fileGeneration.startedAt} />
        ) : activeTab === 'files' && selectedFile ? (
          <FileBoard
            files={files}
            selected={selectedFile}
            onSelect={(artifact) => setSelectedFileId(artifact.id)}
          />
        ) : visibleArtifacts.length > 0 ? (
          <div className="flex flex-col gap-3">
            {visibleArtifacts.map((artifact) => (
              <ArtifactCard key={artifact.id} artifact={artifact} />
            ))}
          </div>
        ) : (
          <WorkspaceEmptyState icon="📁" text="相关的文件会显示在这里" />
        )}
      </div>
    </div>
  );
}

const GENERATION_STAGES = [
  { label: '整理内容结构', detail: '分析主题与章节层级' },
  { label: '撰写正文', detail: '生成段落、列表与示例' },
  { label: '排版文档', detail: '应用标题与正文样式' },
  { label: '打包文件', detail: '准备预览和下载' },
];

function FileGeneratingBoard({ startedAt }: { startedAt: number }) {
  const [elapsed, setElapsed] = useState(() => Math.max(0, Date.now() - startedAt));

  useEffect(() => {
    setElapsed(Math.max(0, Date.now() - startedAt));
    const timer = window.setInterval(() => setElapsed(Math.max(0, Date.now() - startedAt)), 500);
    return () => window.clearInterval(timer);
  }, [startedAt]);

  const activeStage = Math.min(GENERATION_STAGES.length - 1, Math.floor(elapsed / 3500));
  const lineCount = Math.min(12, 3 + Math.floor(elapsed / 850));

  return (
    <section className="overflow-hidden rounded-xl bg-white shadow-[0_18px_50px_-36px_rgba(20,41,36,.45)] ring-1 ring-line">
      <header className="border-b border-line bg-canvas-sub/70 px-4 pb-3 pt-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <div className="mb-1 flex items-center gap-2">
              <span className="relative flex h-2.5 w-2.5">
                <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-brand opacity-40" />
                <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-brand" />
              </span>
              <span className="text-[11px] font-semibold tracking-[.12em] text-brand-deep">正在生成文件</span>
            </div>
            <h4 className="text-[15px] font-semibold tracking-[-.01em] text-ink">新文档</h4>
          </div>
          <span className="font-mono text-[11px] tabular-nums text-ink-faint">
            {(elapsed / 1000).toFixed(1)}s
          </span>
        </div>
        <div className="mt-3 h-1 overflow-hidden rounded-full bg-line/70">
          <div
            className="h-full rounded-full bg-brand transition-[width] duration-500 ease-out"
            style={{ width: `${Math.min(92, 12 + elapsed / 180)}%` }}
          />
        </div>
      </header>

      <div className="grid gap-0 md:grid-cols-[8.25rem_1fr] lg:grid-cols-1 xl:grid-cols-[8.25rem_1fr]">
        <ol className="border-b border-line bg-[#fafbf9] px-3 py-4 md:border-b-0 md:border-r lg:border-b lg:border-r-0 xl:border-b-0 xl:border-r">
          {GENERATION_STAGES.map((stage, index) => {
            const complete = index < activeStage;
            const active = index === activeStage;
            return (
              <li key={stage.label} className={`relative flex gap-2 pb-4 last:pb-0 ${index > activeStage ? 'opacity-45' : ''}`}>
                {index < GENERATION_STAGES.length - 1 && (
                  <span className={`absolute left-[7px] top-4 h-[calc(100%-8px)] w-px ${complete ? 'bg-brand/45' : 'bg-line'}`} />
                )}
                <span className={`relative z-10 mt-0.5 flex h-[15px] w-[15px] shrink-0 items-center justify-center rounded-full text-[9px] ${
                  complete ? 'bg-brand text-white' : active ? 'border-2 border-brand bg-white' : 'border border-line bg-white'
                }`}>
                  {complete ? '✓' : ''}
                </span>
                <div>
                  <p className={`text-[11px] font-medium ${active ? 'text-ink' : 'text-ink-soft'}`}>{stage.label}</p>
                  {active && <p className="mt-0.5 text-[10px] leading-4 text-ink-faint">{stage.detail}</p>}
                </div>
              </li>
            );
          })}
        </ol>

        <div className="min-h-[25rem] bg-white px-5 py-6">
          <div className="mb-6 h-5 w-3/5 animate-pulse rounded bg-ink/12" />
          <div className="space-y-3" aria-label="文档内容正在生成">
            {Array.from({ length: lineCount }).map((_, index) => (
              <div
                key={index}
                className="origin-left animate-[fade-in_.35s_ease-out_both] rounded bg-ink/[.075]"
                style={{
                  height: index % 5 === 0 ? '0.7rem' : '0.45rem',
                  width: `${[92, 84, 96, 68, 78, 88][index % 6]}%`,
                }}
              />
            ))}
            <span className="inline-block h-4 w-[2px] animate-pulse bg-brand align-middle" />
          </div>
        </div>
      </div>
      <footer className="border-t border-line px-4 py-2.5 text-[11px] text-ink-faint">
        文件完成后将在这里显示全文，并启用下载。
      </footer>
    </section>
  );
}

function FileBoard({ files, selected, onSelect }: {
  files: Artifact[];
  selected: Artifact;
  onSelect: (artifact: Artifact) => void;
}) {
  const [preview, setPreview] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError('');
    fetchArtifactPreview(selected.id)
      .then((data) => { if (alive) setPreview(normalizeDocumentPreview(data.content)); })
      .catch(() => { if (alive) setError('暂时无法提取文件内容，但仍可下载原文件。'); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [selected.id]);

  return (
    <div className="flex min-h-full flex-col gap-3">
      {files.length > 1 && (
        <div className="flex gap-2 overflow-x-auto pb-1">
          {files.map((file) => (
            <button
              type="button"
              key={file.id}
              onClick={() => onSelect(file)}
              className={`max-w-48 shrink-0 truncate rounded-lg border px-3 py-2 text-xs ${
                file.id === selected.id ? 'border-brand bg-brand-dim text-brand-deep' : 'border-line bg-white text-ink-soft'
              }`}
            >
              {file.fileName}
            </button>
          ))}
        </div>
      )}
      <section className="overflow-hidden rounded-xl border border-line bg-white">
        <header className="flex items-center justify-between gap-3 border-b border-line px-4 py-3">
          <div className="min-w-0">
            <h4 className="truncate text-sm font-semibold text-ink">{selected.fileName}</h4>
            <p className="text-xs text-ink-faint">{formatSize(selected.size)}</p>
          </div>
          <a
            href={`${selected.url}?download=true`}
            download={selected.fileName}
            className="shrink-0 rounded-lg bg-brand px-3 py-2 text-xs font-semibold text-white hover:opacity-90"
          >
            下载文件
          </a>
        </header>
        <div className="max-h-[calc(100vh-210px)] overflow-auto p-4 text-sm text-ink">
          {loading ? (
            <p className="py-10 text-center text-ink-faint">正在加载文件内容…</p>
          ) : error ? (
            <p className="py-10 text-center text-ink-faint">{error}</p>
          ) : selected.mimeType.includes('markdown') || selected.fileName.toLowerCase().endsWith('.md') ? (
            <Markdown content={preview} />
          ) : preview ? (
            <article className="whitespace-pre-wrap break-words font-sans leading-7" aria-label={`${selected.fileName} 内容`}>
              {preview}
            </article>
          ) : (
            <p className="py-10 text-center text-ink-faint">文件中没有可预览的文字内容。</p>
          )}
        </div>
      </section>
    </div>
  );
}

function formatSize(size: number) {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}
