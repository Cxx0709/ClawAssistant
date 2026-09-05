import { useState, useEffect } from 'react';
import type { Artifact } from '../lib/types';

interface ArtifactCardProps {
  artifact: Artifact;
  compact?: boolean;
  highlight?: boolean;
}

export default function ArtifactCard({ artifact, compact = false, highlight = false }: ArtifactCardProps) {
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
