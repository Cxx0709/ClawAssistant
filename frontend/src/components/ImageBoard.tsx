import { useCallback, useEffect, useMemo, useState } from 'react';
import { fetchArtifacts, generateStyledImage, IMAGE_STYLE_OPTIONS } from '../lib/api';
import type { ImageStyleKey } from '../lib/api';
import type { Artifact } from '../lib/types';
import EmptyState from './WorkspaceEmptyState';

interface ImageBoardProps {
  /** 当前会话内实时推送的产物（对话刚生成的图优先展示） */
  artifacts: Artifact[];
  refreshToken?: number;
  focusedArtifactId?: string | null;
}

/** 剥离后端 description 里的风格标记（"{原prompt} ·[{风格}]重绘"），只展示原 prompt */
function stripStyleMarker(description?: string): string {
  if (!description) return '';
  const idx = description.indexOf(' ·[');
  return idx >= 0 ? description.slice(0, idx).trim() : description.trim();
}

/** 会话内实时图 + 历史图按 id 去重合并（实时在前） */
function mergeImages(sessionArtifacts: Artifact[], history: Artifact[]): Artifact[] {
  const seen = new Set<string>();
  const merged: Artifact[] = [];
  for (const artifact of [...sessionArtifacts, ...history]) {
    if (artifact.kind !== 'IMAGE' || seen.has(artifact.id)) continue;
    seen.add(artifact.id);
    merged.push(artifact);
  }
  return merged;
}

export default function ImageBoard({ artifacts, refreshToken = 0, focusedArtifactId = null }: ImageBoardProps) {
  const [history, setHistory] = useState<Artifact[] | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [retryToken, setRetryToken] = useState(0);
  const [generated, setGenerated] = useState<Artifact[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [generating, setGenerating] = useState(false);
  const [generateError, setGenerateError] = useState('');
  const [highlighted, setHighlighted] = useState(false);

  useEffect(() => {
    let alive = true;
    setLoadError(false);
    fetchArtifacts()
      .then((items) => { if (alive) setHistory(items); })
      .catch(() => { if (alive) { setHistory(null); setLoadError(true); } });
    return () => { alive = false; };
  }, [refreshToken, retryToken]);

  const images = useMemo(
    () => mergeImages([...generated, ...artifacts], history ?? []),
    [generated, artifacts, history],
  );

  const hero = images.find((image) => image.id === selectedId) ?? images[0];

  // 对话流「→ 工作台」聚焦：以目标图为主图并高亮 2s
  useEffect(() => {
    if (!focusedArtifactId) return;
    setSelectedId(focusedArtifactId);
    setHighlighted(true);
    const timer = setTimeout(() => setHighlighted(false), 2000);
    return () => clearTimeout(timer);
  }, [focusedArtifactId]);

  const restyle = useCallback(async (style?: ImageStyleKey) => {
    if (!hero || generating) return;
    if (!stripStyleMarker(hero.description).trim()) {
      setGenerateError('这张图没有生成描述，在对话里描述想要的画面即可');
      return;
    }
    setGenerating(true);
    setGenerateError('');
    try {
      const artifact = await generateStyledImage({ sourceArtifactId: hero.id, style });
      setGenerated((prev) => [artifact, ...prev]);
      setSelectedId(artifact.id);
    } catch (error) {
      setGenerateError(error instanceof Error ? error.message : '生成失败，请稍后重试');
    } finally {
      setGenerating(false);
    }
  }, [hero, generating]);

  if (loadError) {
    return (
      <div className="flex flex-col items-center justify-center gap-3 py-12 text-center">
        <p className="text-sm text-ink-soft">图片列表加载失败</p>
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

  if (history === null) return <ImageBoardSkeleton />;
  if (images.length === 0) {
    return <EmptyState icon="🎨" text="生成的图片会显示在这里，也可以在对话里让我画一张" />;
  }

  return (
    <div className="space-y-4">
      {/* Hero 主图卡 */}
      {generating ? (
        <div
          className="flex h-64 flex-col items-center justify-center gap-3 rounded-[20px] border border-line bg-white"
          role="status"
          aria-label="正在生成图片"
        >
          <span className="h-8 w-8 animate-spin rounded-full border-2 border-brand/20 border-t-brand" />
          <p className="animate-pulse text-xs text-ink-soft">正在画，大约需要半分钟…</p>
        </div>
      ) : (
        <figure
          className={`overflow-hidden rounded-[20px] border bg-white transition-all duration-300 ${
            highlighted ? 'border-brand shadow-lg shadow-brand/20' : 'border-line'
          }`}
        >
          <img src={hero.url} alt={stripStyleMarker(hero.description) || '生成的图片'} className="max-h-96 w-full object-contain" />
          {stripStyleMarker(hero.description) && (
            <figcaption className="border-t border-line px-4 py-2.5 text-xs leading-relaxed text-ink-faint">
              {stripStyleMarker(hero.description)}
            </figcaption>
          )}
        </figure>
      )}

      {/* 风格切换胶囊 */}
      <div className="flex flex-wrap items-center gap-2">
        {IMAGE_STYLE_OPTIONS.map((option) => (
          <button
            key={option.key}
            type="button"
            disabled={generating}
            onClick={() => restyle(option.key)}
            className="rounded-full border border-line bg-white px-3.5 py-1.5 text-xs font-medium text-ink-soft transition-all hover:border-brand/40 hover:text-brand-deep focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/30 active:translate-y-px disabled:cursor-not-allowed disabled:opacity-50"
          >
            {option.label}
          </button>
        ))}
        <button
          type="button"
          disabled={generating}
          onClick={() => restyle()}
          className="rounded-full bg-brand px-3.5 py-1.5 text-xs font-semibold text-white transition-all hover:opacity-90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/30 active:translate-y-px disabled:cursor-not-allowed disabled:opacity-50"
        >
          ↻ 重新生成
        </button>
      </div>

      {generateError && (
        <p className="rounded-xl border border-line bg-white px-3 py-2 text-xs text-ink-soft" role="alert">
          {generateError}
        </p>
      )}

      {/* 历史缩略图条 */}
      {images.length > 1 && (
        <section aria-label="历史图片">
          <div className="mb-2 flex items-center justify-between">
            <p className="text-[10px] font-semibold tracking-[.14em] text-ink-faint">HISTORY</p>
            <span className="text-[11px] tabular-nums text-ink-faint">共 {images.length} 张</span>
          </div>
          <div className="flex gap-2 overflow-x-auto pb-1">
            {images.map((image) => (
              <button
                key={image.id}
                type="button"
                onClick={() => { setSelectedId(image.id); setHighlighted(false); }}
                aria-label={`查看图片：${stripStyleMarker(image.description) || image.fileName}`}
                className={`h-20 w-20 shrink-0 overflow-hidden rounded-xl border transition-all ${
                  image.id === hero.id
                    ? 'border-brand ring-2 ring-brand/25'
                    : 'border-line opacity-80 hover:opacity-100'
                }`}
              >
                <img src={image.url} alt="" className="h-full w-full object-cover" loading="lazy" />
              </button>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}

function ImageBoardSkeleton() {
  return (
    <div className="animate-pulse space-y-4" aria-label="正在加载图片">
      <div className="h-64 rounded-[20px] bg-canvas-sub" />
      <div className="flex gap-2">
        <div className="h-7 w-20 rounded-full bg-canvas-sub" />
        <div className="h-7 w-24 rounded-full bg-canvas-sub" />
        <div className="h-7 w-16 rounded-full bg-canvas-sub" />
      </div>
      <div className="flex gap-2">
        <div className="h-20 w-20 rounded-xl bg-canvas-sub" />
        <div className="h-20 w-20 rounded-xl bg-canvas-sub" />
      </div>
    </div>
  );
}
