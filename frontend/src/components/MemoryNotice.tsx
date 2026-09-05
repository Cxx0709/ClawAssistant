import { useEffect, useState } from 'react';
import { getMemoryChanges, undoMemoryChange } from '../lib/memories';
import type { MemoryChange } from '../lib/memories';

/** Extraction may finish after the chat stream, so receipts are refreshed independently. */
export default function MemoryNotice({ conversationId, refreshToken }: { conversationId: string | null; refreshToken: number }) {
  const [changes, setChanges] = useState<MemoryChange[]>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);
  const [dismissed, setDismissed] = useState(false);
  useEffect(() => {
    setChanges([]); setError(''); setDismissed(false);
  }, [conversationId]);
  useEffect(() => {
    if (!conversationId) return;
    let alive = true;
    let loading = false;
    const controller = new AbortController();
    const load = async () => {
      if (loading || document.hidden || busy) return;
      loading = true;
      try {
        const items = await getMemoryChanges(conversationId, controller.signal);
        if (alive) {
          setChanges(prev => {
            // 有新的记忆变更时，重置 dismissed 状态，让提示重新显示
            if (items.length > 0 && prev.length > 0 && items[0].id !== prev[0].id) {
              setDismissed(false);
            } else if (items.length > 0 && prev.length === 0) {
              setDismissed(false);
            }
            return items;
          });
        }
      } catch { /* A receipt outage must not interrupt chat; management reports errors separately. */ }
      finally { loading = false; }
    };
    void load();
    const timer = window.setInterval(() => void load(), 10000);
    return () => { alive = false; controller.abort(); window.clearInterval(timer); };
  }, [conversationId, refreshToken, revision, busy]);

  const latest = changes[0];
  if (dismissed || (!latest && !error)) return null;
  return <div className="mb-3 border-l-2 border-brand/40 pl-3 text-xs leading-6 text-ink-soft" aria-live="polite">
    {latest && <div className="flex flex-wrap items-center gap-x-3">
      <span className="min-w-0 flex-1 break-words">{latest.action === 'ADDED' ? '已记住' : '已更新记忆'}：{latest.memory.content}</span>
      <a className="text-brand-deep hover:underline" href={`?memories&memoryId=${encodeURIComponent(latest.memory.id)}`} target="_blank" rel="noopener noreferrer">查看</a>
      <button disabled={busy} className="text-brand-deep hover:underline disabled:opacity-40" onClick={async () => {
        setBusy(true); setError('');
        try {
          await undoMemoryChange(latest.id);
          setChanges(current => current.filter(change => change.memory.id !== latest.memory.id));
          setRevision(value => value + 1);
        } catch (reason) { setError((reason as Error).message); }
        finally { setBusy(false); }
      }}>{busy ? '撤销中…' : '撤销'}</button>
      <button className="text-ink-faint hover:text-ink" onClick={() => setDismissed(true)} aria-label="关闭提示">×</button>
    </div>}
    {changes.length > 1 && <details className="mt-1"><summary className="cursor-pointer text-ink-faint">本对话的其他 {changes.length - 1} 条近期记忆变更</summary>{changes.slice(1).map(change => <p key={change.id} className="mt-1"><a href={`?memories&memoryId=${encodeURIComponent(change.memory.id)}`} target="_blank" rel="noopener noreferrer" className="hover:underline">{change.memory.content} ↗</a></p>)}</details>}
    {error && <p role="alert" className="text-red-700">{error}</p>}
  </div>;
}
