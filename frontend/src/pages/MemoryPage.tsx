import { useEffect, useMemo, useRef, useState } from 'react';
import BrandMark from '../components/BrandMark';
import { createConversation } from '../lib/api';
import { getMemories, memoryCategories, memoryDate, memoryRequest } from '../lib/memories';
import type { MemoryCategory, PersonalMemory } from '../lib/memories';

const button = 'rounded-lg border border-line px-3 py-2 text-sm transition-colors hover:bg-canvas-sub focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand disabled:cursor-wait disabled:opacity-40';
const primary = `${button} border-transparent bg-brand text-white hover:bg-brand-deep`;
const input = 'w-full rounded-lg border border-line bg-white px-3 py-2.5 text-sm outline-none focus:border-brand focus:ring-2 focus:ring-brand/15';
const useFor: Record<MemoryCategory, string> = {
  PREFERENCE: '在推荐和选择方案时，参考你的个人偏好。',
  RULE: '在沟通和解释问题时，参考你习惯的表达方式。',
  FACT: '在相关问题中，参考你的个人背景。',
  GOAL: '在制定计划和讨论进展时，参考你的目标。',
  EXPERIENCE: '在提供建议时，参考你已经分享的经历。',
};

export default function MemoryPage({ onBack }: { onBack: () => void }) {
  const [items, setItems] = useState<PersonalMemory[]>([]);
  const [loading, setLoading] = useState(true);
  const [enabled, setEnabled] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [revision, setRevision] = useState(0);
  const [query, setQuery] = useState('');
  const [category, setCategory] = useState<MemoryCategory | 'ALL'>('ALL');
  const [status, setStatus] = useState('ALL');
  const [selection, setSelection] = useState<string | null>(() => new URLSearchParams(location.search).get('memoryId'));
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState(false);
  const [content, setContent] = useState('');
  const [editCategory, setEditCategory] = useState<MemoryCategory>('PREFERENCE');
  const [busy, setBusy] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const editor = useRef<HTMLTextAreaElement>(null);
  const selected = items.find(item => item.id === selection);
  const detailOpen = creating || !!selected;

  useEffect(() => {
    const controller = new AbortController();
    let alive = true;
    setLoading(true);
    setError('');
    getMemories(controller.signal).then(data => {
      if (!alive) return;
      setItems(data.items);
      setEnabled(data.enabled);
      setSelection(id => data.items.some(item => item.id === id) ? id : null);
    }).catch((reason: Error) => {
      if (alive) setError(reason.message || '记忆读取失败');
    }).finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; controller.abort(); };
  }, [revision]);

  useEffect(() => { if (creating || editing) editor.current?.focus(); }, [creating, editing]);

  const filtered = useMemo(() => items.filter(item =>
    (category === 'ALL' || category === item.category) &&
    (status === 'ALL' || (status === 'PAUSED' ? item.disabled : !item.disabled)) &&
    item.content.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase())), [items, query, category, status]);
  const recent = items.filter(item => Date.now() - new Date(item.updatedAt).getTime() < 7 * 86400000).length;
  const paused = items.filter(item => item.disabled).length;

  function select(item: PersonalMemory) {
    setSelection(item.id); setCreating(false); setEditing(false); setConfirmDelete(false); setError('');
    setNotice('');
  }
  function startNew() {
    setSelection(null); setCreating(true); setEditing(false); setContent('');
    setEditCategory(category === 'ALL' ? 'PREFERENCE' : category); setError(''); setConfirmDelete(false);
    setNotice('');
  }
  function edit() {
    if (!selected) return;
    setContent(selected.content); setEditCategory(selected.category); setEditing(true); setConfirmDelete(false);
  }
  async function act(operation: () => Promise<void>) {
    if (busy) return;
    setBusy(true); setError(''); setNotice('');
    try { await operation(); }
    catch (reason) { setError((reason as Error).message || '操作未完成，请重试'); }
    finally { setBusy(false); }
  }
  function replace(item: PersonalMemory) {
    setItems(current => [item, ...current.filter(previous => previous.id !== item.id)]);
    setSelection(item.id); setEditing(false); setCreating(false); setConfirmDelete(false);
  }
  async function save() {
    if (!content.trim()) return;
    await act(async () => {
      const item = await memoryRequest<PersonalMemory>(creating ? '' : `/${selected!.id}`, {
        method: creating ? 'POST' : 'PUT',
        body: JSON.stringify({ content: content.trim(), category: editCategory,
          disabled: selected?.disabled ?? false, expectedUpdatedAt: selected?.updatedAt }),
      });
      replace(item); setNotice('记忆已保存');
    });
  }
  async function toggle() {
    if (!selected) return;
    await act(async () => {
      const item = await memoryRequest<PersonalMemory>(`/${selected.id}`, { method: 'PUT',
        body: JSON.stringify({ ...selected, disabled: !selected.disabled, expectedUpdatedAt: selected.updatedAt }) });
      replace(item); setNotice(item.disabled ? '已停用，之后的记忆检索将不再使用这一条' : '已恢复使用');
    });
  }
  async function remove() {
    if (!selected) return;
    await act(async () => {
      await memoryRequest(`/${selected.id}?expectedUpdatedAt=${encodeURIComponent(selected.updatedAt)}`, { method: 'DELETE' });
      setItems(current => current.filter(item => item.id !== selected.id));
      setSelection(null); setConfirmDelete(false); setNotice('记忆已删除');
    });
  }
  async function ask() {
    if (!selected) return;
    await act(async () => {
      const conversation = await createConversation();
      window.location.assign(`?conversation=${encodeURIComponent(conversation.id)}&memory=${encodeURIComponent(selected.id)}`);
    });
  }

  return <div className="min-h-dvh bg-canvas text-ink">
    <header className="border-b border-line">
      <div className="mx-auto flex max-w-6xl items-center justify-between gap-3 px-5 py-4 sm:px-8">
        <div className="flex items-center gap-3"><BrandMark size={30} /><span className="text-sm font-semibold">Claw Assistant <span className="ml-2 font-normal text-ink-faint">/ 我的记忆</span></span></div>
        <button onClick={onBack} disabled={busy} className={button}>← 返回</button>
      </div>
    </header>
    <main className="mx-auto max-w-6xl px-5 pb-16 pt-10 sm:px-8 sm:pt-14">
      <div className="flex flex-wrap items-end justify-between gap-6">
        <div><p className="mb-3 text-xs tracking-widest text-brand-deep">关于你，慢慢了解</p>
          <h1 className="text-3xl font-semibold tracking-tight sm:text-4xl">助手记住的我</h1>
          <p className="mt-4 max-w-xl text-sm leading-7 text-ink-soft">让每一次对话，都更懂你一点。你可以查看、纠正或删除这里的记忆。</p>
        </div>
        <button onClick={startNew} disabled={busy || loading || !enabled} className={primary}>＋ 添加记忆</button>
      </div>
      {!loading && <p className="mt-6 text-sm text-ink-soft">共 <strong className="font-semibold text-ink">{items.length}</strong> 条记忆<span className="mx-3 text-line">/</span>近 7 天更新 {recent} 条<span className="mx-3 text-line">/</span>{paused} 条已停用</p>}
      <div className="mt-7 min-h-6" aria-live="polite">
        {error && <div role="alert" className="flex flex-wrap items-center gap-3 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-800">{error}<button className="underline underline-offset-4" disabled={busy} onClick={() => { setRevision(v => v + 1); setEditing(false); }}>重新读取</button></div>}
        {notice && <p role="status" className="text-sm text-brand-deep">{notice}</p>}
        {!enabled && <p className="mt-2 text-sm text-ink-soft">长期记忆功能尚未启用。现有内容可查看或删除，启用后才能添加和修改。</p>}
      </div>

      <div className="mt-4 grid items-start gap-8 lg:grid-cols-[minmax(0,1fr)_minmax(18rem,0.8fr)] lg:gap-12">
        <section aria-label="记忆列表" className={detailOpen ? 'hidden lg:block' : ''}>
          <label className="sr-only" htmlFor="memory-search">搜索记忆</label>
          <input id="memory-search" type="search" className={input} placeholder="搜索记忆，例如：旅行、学习、咖啡" value={query} onChange={e => setQuery(e.target.value)} />
          <div className="mt-5 flex flex-wrap gap-2" aria-label="记忆分类">
            {(['ALL', ...Object.keys(memoryCategories)] as (MemoryCategory | 'ALL')[]).map(key => <button key={key} aria-pressed={category === key} onClick={() => setCategory(key)} className={`rounded-md px-3 py-2 text-xs transition-colors focus-visible:outline focus-visible:outline-brand ${category === key ? 'bg-brand-dim font-medium text-brand-deep' : 'text-ink-soft hover:bg-canvas-sub'}`}>{key === 'ALL' ? '全部' : memoryCategories[key]}</button>)}
          </div>
          <div className="mt-5 flex items-center justify-between border-b border-line pb-3">
            <p className="text-xs text-ink-faint">{filtered.length} 条 · 最近更新优先</p>
            <label className="text-xs text-ink-soft">状态 <select value={status} onChange={e => setStatus(e.target.value)} className="ml-2 rounded bg-transparent py-1 focus-visible:outline-brand"><option value="ALL">全部状态</option><option value="ACTIVE">使用中</option><option value="PAUSED">已停用</option></select></label>
          </div>
          {loading ? <div role="status" aria-label="正在读取记忆" className="space-y-5 py-7">{[1, 2, 3].map(n => <div key={n} className="h-20 animate-pulse rounded-lg bg-canvas-sub motion-reduce:animate-none" />)}</div>
            : filtered.length ? <ul className="divide-y divide-line">{filtered.map(item => <li key={item.id}>
              <button onClick={() => select(item)} disabled={busy} aria-pressed={selection === item.id} className={`w-full rounded-md px-3 py-5 text-left transition-colors hover:bg-canvas-sub focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand ${selection === item.id ? 'bg-brand-dim/50' : ''}`}>
                <div className="mb-2 flex items-center justify-between gap-3 text-xs"><span className="text-brand-deep">{memoryCategories[item.category]}</span>{item.disabled && <span className="text-ink-faint">已停用</span>}</div>
                <p className="line-clamp-3 break-words text-sm leading-7">{item.content}</p>
                <p className="mt-3 text-xs text-ink-faint">{memoryDate(item.updatedAt)} 更新 <span className="float-right" aria-hidden="true">↗</span></p>
              </button>
            </li>)}</ul>
              : !error && <div className="py-14 text-center"><h2 className="text-lg font-medium">{items.length ? '没有找到相关记忆' : '从一件小事，开始了解你'}</h2><p className="mx-auto mt-3 max-w-xs text-sm leading-7 text-ink-soft">{items.length ? '试试其他关键词，或切换分类和状态。' : '告诉助手你的偏好、目标或习惯。比如：“解释问题时，请多给我具体例子。”'}</p>{!items.length && <button className={`${button} mt-6`} disabled={!enabled} onClick={startNew}>添加第一条记忆</button>}</div>}
        </section>

        <aside aria-label="记忆详情" className={`rounded-2xl bg-canvas-sub/70 p-6 sm:p-8 ${detailOpen ? '' : 'hidden lg:block'}`}>
          {detailOpen ? <>
            <button onClick={() => { setSelection(null); setCreating(false); setEditing(false); }} disabled={busy} className="mb-6 text-sm text-ink-soft hover:text-ink lg:hidden">← 返回记忆列表</button>
            {creating || editing ? <form onSubmit={e => { e.preventDefault(); void save(); }}>
              <h2 className="text-xl font-semibold">{creating ? '想让我记住什么？' : '纠正这条记忆'}</h2>
              <p className="mb-6 mt-2 text-sm leading-6 text-ink-soft">写下你希望助手在以后对话中参考的信息。</p>
              <label className="mb-2 block text-sm" htmlFor="memory-category">分类</label>
              <select id="memory-category" className={input} value={editCategory} onChange={e => setEditCategory(e.target.value as MemoryCategory)} disabled={busy}>{Object.entries(memoryCategories).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select>
              <label className="mb-2 mt-5 block text-sm" htmlFor="memory-content">记忆内容</label>
              <textarea ref={editor} id="memory-content" required maxLength={500} rows={7} value={content} onChange={e => setContent(e.target.value)} disabled={busy} className={`${input} resize-y leading-7`} placeholder="例如：我在学习 Java，希望讲解时多举例。" />
              <p className="mt-2 text-right text-xs tabular-nums text-ink-faint">{content.length} / 500</p>
              <div className="mt-6 flex gap-3"><button type="submit" disabled={busy || !content.trim() || !enabled} className={primary}>{busy ? '正在保存…' : '保存记忆'}</button><button type="button" disabled={busy} onClick={() => { setCreating(false); setEditing(false); }} className={button}>取消</button></div>
            </form> : selected && <>
              <div className="flex items-center justify-between text-xs"><span className="text-brand-deep">{memoryCategories[selected.category]}</span><span className="text-ink-faint">{selected.disabled ? '已停用' : '使用中'}</span></div>
              <h2 className="mt-5 break-words text-xl font-medium leading-9">{selected.content}</h2>
              <p className="mt-5 text-sm leading-7 text-ink-soft">{selected.disabled ? '这条记忆已暂停使用。恢复后，助手会在相关问题中继续参考。' : useFor[selected.category]}</p>
              <dl className="mt-7 space-y-4 border-t border-line pt-6 text-sm">
                <div><dt className="mb-1 text-xs text-ink-faint">来源</dt><dd>{selected.source === 'MANUAL' ? '你主动添加或修改' : '从对话中整理'}{selected.sourceConversationId ? <a href={`?conversation=${encodeURIComponent(selected.sourceConversationId)}`} className="ml-3 text-brand-deep underline underline-offset-4">查看来源对话 ↗</a> : <p className="mt-1 text-xs leading-6 text-ink-faint">未记录来源对话位置</p>}</dd></div>
                <div><dt className="mb-1 text-xs text-ink-faint">记录依据</dt><dd className="whitespace-pre-wrap break-words leading-7">{selected.evidence || '未记录依据片段'}</dd></div>
                <div className="flex flex-wrap gap-6"><div><dt className="mb-1 text-xs text-ink-faint">首次记住</dt><dd>{memoryDate(selected.createdAt)}</dd></div><div><dt className="mb-1 text-xs text-ink-faint">最近更新</dt><dd>{memoryDate(selected.updatedAt)}</dd></div></div>
              </dl>
              <button onClick={() => void ask()} disabled={busy || selected.disabled || !enabled} className={`${primary} mt-8 w-full`}>用这条记忆聊一聊 ↗</button>
              <div className="mt-3 flex flex-wrap gap-2"><button onClick={edit} disabled={busy || !enabled} className={button}>修改</button><button onClick={() => void toggle()} disabled={busy || !enabled} className={button}>{selected.disabled ? '恢复使用' : '暂时停用'}</button><button onClick={() => setConfirmDelete(true)} disabled={busy} className={`${button} ml-auto text-red-700`}>删除</button></div>
              {confirmDelete && <div className="mt-5 border-t border-line pt-5"><p className="text-sm leading-6">删除这条记忆？删除后无法撤销，原始聊天记录会保留。</p><div className="mt-3 flex gap-3"><button onClick={() => void remove()} disabled={busy} className={`${button} text-red-700`}>确认删除</button><button onClick={() => setConfirmDelete(false)} disabled={busy} className={button}>保留</button></div></div>}
              {busy && <p role="status" className="mt-4 text-sm text-ink-faint">正在处理…</p>}
            </>}
          </> : <div className="py-10"><div className="mb-8 flex h-14 w-14 items-center justify-center rounded-full bg-brand-dim text-2xl text-brand-deep" aria-hidden="true">◎</div><h2 className="text-xl font-medium">你说了算</h2><p className="mt-4 text-sm leading-8 text-ink-soft">选择左侧的一条记忆，看看它从哪里来、如何帮助你。生活和想法变了，也可以随时更新。</p><p className="mt-8 border-t border-line pt-5 text-xs leading-6 text-ink-faint">停用或删除记忆不会移除原始聊天内容；原对话仍可能包含你之前分享的信息。</p></div>}
        </aside>
      </div>
    </main>
  </div>;
}
