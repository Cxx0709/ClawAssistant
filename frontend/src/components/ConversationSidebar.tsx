import { useEffect, useState } from 'react';
import { fetchConversationPage } from '../lib/api';
import type { Conversation } from '../lib/types';

type View = 'history' | 'archive' | 'trash';

type Props = {
  conversations: Conversation[];
  archivedConversations: Conversation[];
  deletedConversations: Conversation[];
  activeId: string | null;
  loading: boolean;
  disabled: boolean;
  onSelect: (id: string) => void;
  onNew: () => void;
  onRename: (id: string, title: string) => void;
  onPin: (id: string, pinned: boolean) => void;
  onArchive: (id: string, archived: boolean) => void;
  onDelete: (id: string) => void;
  onRestore: (id: string) => void;
  onPurge: (id: string) => void;
  onExport: (conversation: Conversation) => void;
  onImport: (file: File) => void;
  onClose?: () => void;
};

export default function ConversationSidebar(props: Props) {
  const [editingId, setEditingId] = useState<string | null>(null);
  const [menuId, setMenuId] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [view, setView] = useState<View>('history');
  const [query, setQuery] = useState('');
  const [searchItems, setSearchItems] = useState<Conversation[] | null>(null);
  const [searching, setSearching] = useState(false);

  useEffect(() => {
    const value = query.trim();
    if (!value) { setSearchItems(null); return; }
    let alive = true;
    const timer = window.setTimeout(() => {
      setSearching(true);
      void fetchConversationPage({ q: value, archived: view === 'archive', deleted: view === 'trash', limit: 50 })
        .then((page) => alive && setSearchItems(page.items))
        .catch(() => alive && setSearchItems([]))
        .finally(() => alive && setSearching(false));
    }, 220);
    return () => { alive = false; window.clearTimeout(timer); };
  }, [query, view]);

  const baseItems = view === 'history' ? props.conversations
    : view === 'archive' ? props.archivedConversations : props.deletedConversations;
  const visible = searchItems ?? baseItems;
  const groups = view === 'history' ? groupConversations(visible)
    : [{ label: view === 'archive' ? '已归档' : '最近删除', items: visible }];
  const beginRename = (conversation: Conversation) => {
    setMenuId(null); setEditingId(conversation.id); setDraft(conversation.title);
  };
  const commitRename = () => {
    if (editingId && draft.trim()) props.onRename(editingId, draft.trim());
    setEditingId(null);
  };

  return (
    <aside className="flex h-full w-[280px] flex-col border-r border-line bg-[#f7f8f7]">
      <div className="border-b border-line p-3">
        <div className="mb-2.5 flex items-baseline justify-between px-0.5">
          <div>
            <h2 className="text-[14px] font-semibold tracking-[-.01em] text-ink">对话记录</h2>
            <p className="mt-0.5 text-[10.5px] text-ink-faint">查找和管理与 Claw 的对话</p>
          </div>
          <span className="tabular-nums text-[10.5px] text-ink-faint">{props.conversations.length} 条</span>
        </div>
        <div className="flex gap-2">
          <button type="button" onClick={props.onNew} disabled={props.disabled}
            className="flex h-9 flex-1 items-center gap-2 rounded-lg border border-line bg-white px-3 text-[13px] font-medium text-ink transition hover:border-brand/35 hover:bg-brand-dim disabled:opacity-50">
            <span className="text-lg font-light leading-none text-brand">＋</span>新建对话
          </button>
          <label title="导入 JSON 对话" className="flex h-9 w-9 cursor-pointer items-center justify-center rounded-lg border border-line bg-white text-ink-soft hover:border-brand/35 hover:text-ink">
            <span aria-hidden="true">↥</span><span className="sr-only">导入对话</span>
            <input type="file" accept="application/json,.json" className="hidden" onChange={(event) => {
              const file = event.target.files?.[0]; if (file) props.onImport(file); event.target.value = '';
            }} />
          </label>
          {props.onClose && <button type="button" onClick={props.onClose} className="h-9 w-9 rounded-lg text-ink-soft hover:bg-black/5" aria-label="关闭历史对话">×</button>}
        </div>
        <label className="mt-2 flex h-8 items-center gap-2 rounded-lg border border-transparent bg-black/[.035] px-2.5 focus-within:border-brand/25 focus-within:bg-white">
          <svg viewBox="0 0 24 24" className="h-3.5 w-3.5 text-ink-faint" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="11" cy="11" r="7"/><path d="m16 16 4 4"/></svg>
          <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索标题和消息" className="min-w-0 flex-1 bg-transparent text-xs outline-none placeholder:text-ink-faint" />
          {query && <button type="button" onClick={() => setQuery('')} className="text-ink-faint hover:text-ink">×</button>}
        </label>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-2 py-3" onClick={() => menuId && setMenuId(null)}>
        {(props.loading || searching) && <p className="px-2 py-3 text-xs text-ink-faint">{searching ? '正在搜索…' : '正在读取历史对话…'}</p>}
        {!props.loading && !searching && visible.length === 0 && (
          <div className="px-3 py-10 text-center text-xs leading-5 text-ink-faint">
            {query ? '没有找到相关对话' : view === 'archive' ? '还没有归档对话' : view === 'trash' ? '回收站为空' : <>还没有历史对话<br />开始一次新对话吧</>}
          </div>
        )}
        {!searching && groups.map((group) => (
          <section key={group.label} className="mb-4">
            <h2 className="mb-1 px-2 text-[10.5px] font-semibold tracking-[.08em] text-ink-faint">{group.label}</h2>
            <div className="space-y-0.5">
              {group.items.map((conversation) => {
                const active = conversation.id === props.activeId;
                return (
                  <div key={conversation.id} className={`group relative rounded-lg ${active ? 'bg-white shadow-[0_1px_2px_rgba(20,21,23,.06)]' : 'hover:bg-black/[.035]'}`}>
                    {editingId === conversation.id ? (
                      <input autoFocus value={draft} onChange={(event) => setDraft(event.target.value)} onBlur={commitRename}
                        onKeyDown={(event) => { if (event.key === 'Enter') commitRename(); if (event.key === 'Escape') setEditingId(null); }}
                        className="m-1 h-8 w-[calc(100%-8px)] rounded-md border border-brand/40 bg-white px-2 text-[13px] outline-none" />
                    ) : (
                      <button type="button" disabled={props.disabled || view !== 'history'} onClick={() => view === 'history' && props.onSelect(conversation.id)}
                        onDoubleClick={() => view === 'history' && beginRename(conversation)} className="w-full px-2.5 py-2 pr-10 text-left disabled:opacity-70">
                        <span className="block truncate text-[13px] font-medium text-ink">{conversation.title}</span>
                        <span className="mt-0.5 block truncate text-[10.5px] text-ink-faint">{conversation.lastMessagePreview || formatTime(conversation.updatedAt)}</span>
                      </button>
                    )}
                    {editingId !== conversation.id && <ConversationMenu conversation={conversation} view={view} active={active} open={menuId === conversation.id}
                      onToggle={() => setMenuId(menuId === conversation.id ? null : conversation.id)} onClose={() => setMenuId(null)}
                      onRename={() => beginRename(conversation)} onPin={props.onPin} onArchive={props.onArchive}
                      onDelete={props.onDelete} onRestore={props.onRestore} onPurge={props.onPurge} onExport={props.onExport} />}
                  </div>
                );
              })}
            </div>
          </section>
        ))}
      </div>

      <div className="border-t border-line p-2">
        <div className="grid grid-cols-3 rounded-lg bg-black/[.035] p-0.5 text-[11px] text-ink-faint">
          {([['history', '历史'], ['archive', `归档 ${props.archivedConversations.length || ''}`], ['trash', `回收站 ${props.deletedConversations.length || ''}`]] as const).map(([key, label]) => (
            <button key={key} type="button" onClick={() => { setView(key); setQuery(''); setMenuId(null); }}
              className={`rounded-md px-1 py-1.5 transition ${view === key ? 'bg-white text-ink shadow-sm' : 'hover:text-ink-soft'}`}>{label}</button>
          ))}
        </div>
        <p className="mt-2 px-1 text-[10px] text-ink-faint">保存在本机 · 回收站保留 30 天</p>
      </div>
    </aside>
  );
}

function ConversationMenu({ conversation, view, active, open, onToggle, onClose, onRename, onPin, onArchive, onDelete, onRestore, onPurge, onExport }: {
  conversation: Conversation; view: View; active: boolean; open: boolean; onToggle: () => void; onClose: () => void; onRename: () => void;
  onPin: Props['onPin']; onArchive: Props['onArchive']; onDelete: Props['onDelete']; onRestore: Props['onRestore']; onPurge: Props['onPurge']; onExport: Props['onExport'];
}) {
  return <div className="absolute right-1.5 top-1.5">
    <button type="button" aria-label="更多操作" aria-expanded={open} onClick={(event) => { event.stopPropagation(); onToggle(); }}
      className={`flex h-7 w-7 items-center justify-center rounded-md text-sm tracking-widest text-ink-faint hover:bg-black/[.06] hover:text-ink ${active || open ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'}`}>•••</button>
    {open && <div onClick={(event) => event.stopPropagation()} className="absolute right-0 top-8 z-20 w-36 rounded-xl border border-line bg-white p-1.5 text-xs shadow-pop">
      {view === 'history' && <>
        <MenuItem onClick={onRename}>重命名</MenuItem>
        <MenuItem onClick={() => { onClose(); onExport(conversation); }}>导出 JSON</MenuItem>
        <MenuItem onClick={() => { onClose(); onPin(conversation.id, !conversation.pinned); }}>{conversation.pinned ? '取消置顶' : '置顶'}</MenuItem>
        <MenuItem onClick={() => { onClose(); onArchive(conversation.id, true); }}>归档</MenuItem>
        <MenuItem danger onClick={() => { onClose(); onDelete(conversation.id); }}>移到回收站</MenuItem>
      </>}
      {view === 'archive' && <>
        <MenuItem onClick={() => { onClose(); onExport(conversation); }}>导出 JSON</MenuItem>
        <MenuItem onClick={() => { onClose(); onArchive(conversation.id, false); }}>恢复到历史</MenuItem>
        <MenuItem danger onClick={() => { onClose(); onDelete(conversation.id); }}>移到回收站</MenuItem>
      </>}
      {view === 'trash' && <>
        <MenuItem onClick={() => { onClose(); onRestore(conversation.id); }}>恢复对话</MenuItem>
        <MenuItem danger onClick={() => { onClose(); onPurge(conversation.id); }}>永久删除</MenuItem>
      </>}
    </div>}
  </div>;
}

function MenuItem({ children, danger, onClick }: { children: React.ReactNode; danger?: boolean; onClick: () => void }) {
  return <button type="button" onClick={onClick} className={`block w-full rounded-lg px-2.5 py-2 text-left hover:bg-canvas-sub ${danger ? 'text-[#b3342b]' : 'text-ink-soft hover:text-ink'}`}>{children}</button>;
}

function groupConversations(conversations: Conversation[]) {
  const now = new Date();
  const startToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime() / 1000;
  const weekAgo = startToday - 7 * 86400;
  const definitions = [
    { label: '已置顶', test: (item: Conversation) => item.pinned },
    { label: '今天', test: (item: Conversation) => !item.pinned && item.updatedAt >= startToday },
    { label: '近 7 天', test: (item: Conversation) => !item.pinned && item.updatedAt < startToday && item.updatedAt >= weekAgo },
    { label: '更早', test: (item: Conversation) => !item.pinned && item.updatedAt < weekAgo },
  ];
  return definitions.map((definition) => ({ label: definition.label, items: conversations.filter(definition.test) })).filter((group) => group.items.length > 0);
}

function formatTime(epochSeconds: number) {
  return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric' }).format(new Date(epochSeconds * 1000));
}
