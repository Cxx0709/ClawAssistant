import { useState } from 'react';

type TabKey = 'images' | 'files';

const TABS: { key: TabKey; label: string; icon: string }[] = [
  { key: 'images', label: '生成图片', icon: '🎨' },
  { key: 'files', label: '文件', icon: '📁' },
];

export default function WorkspaceCanvas() {
  const [activeTab, setActiveTab] = useState<TabKey>('images');

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
        {activeTab === 'images' ? (
          <EmptyState icon="🎨" text="生成的图片会显示在这里" />
        ) : (
          <EmptyState icon="📁" text="相关的文件会显示在这里" />
        )}
      </div>
    </div>
  );
}

function EmptyState({ icon, text }: { icon: string; text: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 py-12 text-[var(--color-text-muted)]">
      <span className="text-4xl">{icon}</span>
      <p className="text-sm">{text}</p>
    </div>
  );
}
