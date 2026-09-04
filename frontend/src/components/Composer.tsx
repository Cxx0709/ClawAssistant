import { useEffect, useRef, useState } from 'react';
import { formatFileSize, MAX_ATTACHMENTS, type AttachmentDraft } from '../lib/attachmentQueue';

interface ComposerProps {
  text: string;
  busy: boolean;
  disabled: boolean;
  onChange: (value: string) => void;
  onSend: () => void;
  onStop: () => void;
  attachments: AttachmentDraft[];
  uploading: boolean;
  hasFailed: boolean;
  uploadError: string;
  onFiles: (files: File[]) => void;
  onRemove: (id: string) => void;
  onRetry: (id: string) => void;
}

export default function Composer({ text, busy, disabled, onChange, onSend, onStop,
  attachments, uploading, hasFailed, uploadError, onFiles, onRemove, onRetry }: ComposerProps) {
  const ref = useRef<HTMLTextAreaElement | null>(null);
  const fileRef = useRef<HTMLInputElement | null>(null);
  const [dragging, setDragging] = useState(false);
  const blocked = busy || disabled;
  const canSend = (text.trim().length > 0 || attachments.length > 0)
    && !uploading && !hasFailed && !blocked;

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
  }, [text]);

  const onKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing && event.keyCode !== 229) {
      event.preventDefault();
      if (canSend) onSend();
    }
  };

  const onPaste = (event: React.ClipboardEvent<HTMLTextAreaElement>) => {
    const files = Array.from(event.clipboardData.files);
    if (files.length === 0) {
      for (const item of Array.from(event.clipboardData.items)) {
        if (item.kind !== 'file') continue;
        const file = item.getAsFile();
        if (file) files.push(file);
      }
    }
    if (files.length === 0) return;
    // Let the browser insert any accompanying text at the current selection.
    if (!event.clipboardData.getData('text/plain')) event.preventDefault();
    if (!blocked) onFiles(files);
  };

  return (
    <div
      onDragOver={(event) => {
        if (!Array.from(event.dataTransfer.types).includes('Files')) return;
        event.preventDefault();
        event.dataTransfer.dropEffect = blocked ? 'none' : 'copy';
        setDragging(!blocked);
      }}
      onDragLeave={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setDragging(false);
      }}
      onDrop={(event) => {
        setDragging(false);
        const files = Array.from(event.dataTransfer.files);
        if (files.length === 0) return;
        event.preventDefault();
        if (!blocked) onFiles(files);
      }}
      className={`relative rounded-[24px] border bg-white p-1.5 shadow-composer transition-shadow focus-within:border-brand/50 focus-within:shadow-pop ${dragging ? 'border-brand ring-2 ring-brand/20' : 'border-line'}`}
    >
      {dragging && <div className="pointer-events-none absolute inset-0 z-10 flex items-center justify-center rounded-[24px] bg-brand-dim/95 text-sm font-medium text-brand-deep">松开即可添加图片或文件</div>}
      {attachments.length > 0 && (
        <div className="flex max-h-56 flex-wrap gap-2 overflow-y-auto px-2 pb-2 pt-1">
          {attachments.map((item) => (
            <div key={item.id} className={`flex w-full min-w-0 items-center gap-2 rounded-xl px-2.5 py-2 text-xs sm:w-[220px] ${item.status === 'error' ? 'bg-[#fdf3f3]' : 'bg-canvas-sub'}`}>
              {item.preview ? (
                <a href={item.preview} target="_blank" rel="noopener noreferrer" aria-label={`预览 ${item.file.name}`} className="shrink-0 rounded-lg focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand">
                  <img src={item.preview} alt="" className="h-11 w-11 rounded-lg object-cover" />
                </a>
              ) : (
                <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg bg-white text-ink-faint" aria-hidden="true">
                  <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M14 3H5v18h14V8l-5-5ZM14 3v5h5M8 13h8M8 17h5" /></svg>
                </span>
              )}
              <div className="min-w-0 flex-1">
                <p className="truncate font-medium text-ink" title={item.file.name}>{item.file.name}</p>
                <p className="mt-0.5 text-[11px] text-ink-faint">{formatFileSize(item.file.size)} · {item.status === 'ready' ? '已上传' : item.status === 'uploading' ? '上传中…' : '上传失败'}</p>
                {item.status === 'error' && <>
                  <p className="mt-1 break-words text-[11px] text-[#a63a32]" role="alert">{item.error}</p>
                  <button type="button" disabled={blocked} onClick={() => onRetry(item.id)} className="mt-1 font-medium text-brand-deep underline underline-offset-2 focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand disabled:opacity-40">重试</button>
                </>}
              </div>
              <button type="button" disabled={blocked} onClick={() => onRemove(item.id)} className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-base text-ink-faint transition-colors hover:bg-white hover:text-ink focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand disabled:opacity-40" aria-label={`${item.status === 'uploading' ? '取消上传' : '移除'} ${item.file.name}`}>×</button>
            </div>
          ))}
        </div>
      )}
      {uploadError && <p role="alert" className="px-3 pb-1 text-xs text-[#a63a32]">{uploadError}</p>}
      {uploading && <p role="status" className="px-3 pb-1 text-xs text-ink-faint">附件上传完成后即可发送，可以继续添加</p>}
      {hasFailed && <p className="px-3 pb-1 text-xs text-[#a63a32]">请重试或移除失败附件后再发送</p>}
      <div className="flex items-end gap-1.5">
        <input ref={fileRef} type="file" multiple className="hidden" disabled={blocked}
          onChange={(event) => { onFiles(Array.from(event.target.files ?? [])); event.target.value = ''; }} />
        <button type="button" onClick={() => fileRef.current?.click()} disabled={blocked || attachments.length >= MAX_ATTACHMENTS}
          title="添加图片、语音或文件" aria-label="添加图片、语音或文件"
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-ink-soft transition-colors hover:bg-canvas-sub focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand disabled:opacity-40">
          <svg viewBox="0 0 24 24" className="h-[18px] w-[18px]" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m8 13 6-6a3 3 0 0 1 4 4l-8 8a5 5 0 0 1-7-7l9-9M6 15l8-8" /></svg>
        </button>
        <textarea ref={ref} rows={1} value={text} aria-label="消息输入框"
          placeholder={busy ? 'Agent 正在处理…' : disabled ? '正在加载对话…' : '发消息、粘贴图片，或拖入文件…'}
          onChange={(event) => onChange(event.target.value)} onKeyDown={onKeyDown} onPaste={onPaste} disabled={blocked}
          className="max-h-[160px] min-w-0 flex-1 resize-none bg-transparent py-[7px] text-[14.5px] leading-relaxed text-ink outline-none placeholder:text-ink-faint disabled:opacity-60" />
        {busy ? (
          <button type="button" onClick={onStop} title="停止生成" aria-label="停止生成"
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full border border-line bg-white text-ink-soft transition-colors hover:border-[#e5484d]/40 hover:text-[#e5484d] focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand">
            <svg viewBox="0 0 24 24" className="h-4 w-4" fill="currentColor"><rect x="7" y="7" width="10" height="10" rx="1.6" /></svg>
          </button>
        ) : (
          <button type="button" onClick={onSend} disabled={!canSend} title="发送" aria-label="发送"
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-brand text-white transition-colors enabled:hover:bg-brand-deep focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand disabled:opacity-35">
            <svg viewBox="0 0 24 24" className="h-[18px] w-[18px]" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 19V5M6 11l6-6 6 6" /></svg>
          </button>
        )}
      </div>
      <div className="flex items-center justify-between gap-2 px-3 pb-1 pt-1.5 text-[10px] text-ink-faint">
        <span>每条最多 {MAX_ATTACHMENTS} 个附件 · 单个 25 MB{attachments.length > 0 ? ` · ${attachments.length}/${MAX_ATTACHMENTS}` : ''}</span>
        <span className="hidden sm:inline">Enter 发送 · Shift + Enter 换行</span>
      </div>
    </div>
  );
}
