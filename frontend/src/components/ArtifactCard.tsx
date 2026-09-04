import { useState, useEffect } from 'react';
import type { Artifact } from '../lib/types';

interface ArtifactCardProps {
  artifact: Artifact;
  compact?: boolean;
  highlight?: boolean;
  onBoardClick?: () => void;
}

export default function ArtifactCard({ artifact, compact = false, highlight = false, onBoardClick }: ArtifactCardProps) {
  const [isHighlighted, setIsHighlighted] = useState(false);

  useEffect(() => {
    if (highlight) {
      setIsHighlighted(true);
      const timer = setTimeout(() => setIsHighlighted(false), 2000);
      return () => clearTimeout(timer);
    }
  }, [highlight]);

  const baseClass = `overflow-hidden rounded-xl border transition-all duration-300 ${
    isHighlighted ? 'border-brand shadow-lg shadow-brand/20' : 'border-line bg-white'
  }`;

  switch (artifact.kind) {
    case 'IMAGE':
      return (
        <a
          href={artifact.url}
          target="_blank"
          rel="noreferrer"
          className={`${baseClass} ${compact ? 'max-w-[82%]' : ''}`}
        >
          <img
            src={artifact.url}
            alt={artifact.description || artifact.fileName}
            className="max-h-72 w-full object-contain"
          />
          {artifact.description && (
            <p className="px-3 py-2 text-xs text-ink-soft">{artifact.description}</p>
          )}
        </a>
      );

    case 'AUDIO':
      return (
        <div className={`${baseClass} p-3 ${compact ? 'max-w-[82%]' : ''}`}>
          <p className="mb-2 truncate text-xs text-ink-soft">{artifact.fileName}</p>
          <audio controls preload="metadata" src={artifact.url} className="h-9 w-full" />
        </div>
      );

    case 'BOARD':
      return <BoardCard artifact={artifact} isHighlighted={isHighlighted} onClick={onBoardClick} />;

    case 'FILE':
    default:
      return (
        <a
          href={artifact.url}
          download={artifact.fileName}
          className={`${baseClass} flex items-center gap-2 px-3 py-2.5 text-sm text-brand-deep hover:border-brand/50 ${
            compact ? 'max-w-[82%]' : ''
          }`}
        >
          <span aria-hidden="true">↧</span>
          <span className="truncate">{artifact.fileName}</span>
        </a>
      );
  }
}

function BoardCard({ artifact, isHighlighted, onClick }: { artifact: Artifact; isHighlighted: boolean; onClick?: () => void }) {
  const boardData = artifact.boardData;

  if (!boardData) {
    return (
      <div
        className={`rounded-xl border p-4 cursor-pointer transition-all duration-300 ${
          isHighlighted ? 'border-brand shadow-lg shadow-brand/20 bg-brand-dim' : 'border-line bg-white'
        }`}
        onClick={onClick}
      >
        <div className="flex items-center gap-2 mb-2">
          <span className="text-lg">📋</span>
          <h4 className="font-medium text-ink">{artifact.description || '行程看板'}</h4>
        </div>
        <p className="text-sm text-ink-soft">点击查看详情</p>
      </div>
    );
  }

  return (
    <div
      className={`rounded-xl border overflow-hidden cursor-pointer transition-all duration-300 ${
        isHighlighted ? 'border-brand shadow-lg shadow-brand/20' : 'border-line bg-white'
      }`}
      onClick={onClick}
    >
      {/* 头部 */}
      <div className={`px-4 py-3 ${isHighlighted ? 'bg-brand-dim' : 'bg-canvas-sub'}`}>
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="text-lg">📋</span>
            <h4 className="font-medium text-ink">{boardData.title}</h4>
          </div>
          <span className="text-xs text-ink-faint font-mono">{artifact.fileName}</span>
        </div>
        {boardData.stats && (
          <p className="mt-1 text-xs text-ink-soft">{boardData.stats}</p>
        )}
      </div>

      {/* 行程列表 */}
      <div className="px-4 py-3 space-y-3">
        {boardData.days.map((day, dayIndex) => (
          <div key={dayIndex}>
            <h5 className="text-xs font-medium text-ink-soft mb-2">{day.label}</h5>
            <div className="space-y-1.5">
              {day.items.map((item, itemIndex) => (
                <div
                  key={itemIndex}
                  className={`flex items-start gap-2 p-2 rounded-lg ${
                    item.status === 'adjusted' ? 'bg-warn-dim' :
                    item.status === 'added' ? 'bg-ok-dim' :
                    'bg-canvas-sub'
                  }`}
                >
                  {/* 状态图标 */}
                  <div className="mt-0.5 shrink-0">
                    {item.status === 'done' && (
                      <span className="text-brand">✓</span>
                    )}
                    {item.status === 'adjusted' && (
                      <span className="text-warn">⇄</span>
                    )}
                    {item.status === 'added' && (
                      <span className="text-ok">+</span>
                    )}
                  </div>

                  {/* 内容 */}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-medium text-ink">{item.title}</span>
                      {item.time && (
                        <span className="text-xs text-ink-faint font-mono">{item.time}</span>
                      )}
                    </div>
                    {item.note && (
                      <p className="text-xs text-ink-soft mt-0.5">{item.note}</p>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>

      {/* 底部操作提示 */}
      <div className="px-4 py-2 border-t border-line bg-canvas-sub">
        <p className="text-xs text-ink-faint text-center">点击查看详情</p>
      </div>
    </div>
  );
}
