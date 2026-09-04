import type { Artifact } from './types';

export const MAX_ATTACHMENTS = 8;
// Matches spring.servlet.multipart.max-file-size in application.properties.
export const MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024;

export interface AttachmentDraft {
  id: string;
  conversationId: string;
  file: File;
  preview?: string;
  status: 'uploading' | 'ready' | 'error';
  artifact?: Artifact;
  error?: string;
}

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** Owns uploads independently of whichever conversation is currently visible. */
export class AttachmentQueue {
  private state: { items: AttachmentDraft[]; errors: Record<string, string> } = { items: [], errors: {} };
  private listeners = new Set<() => void>();
  private controllers = new Map<string, AbortController>();
  private sequence = 0;
  private upload: (file: File, signal: AbortSignal) => Promise<Artifact>;

  constructor(upload: (file: File, signal: AbortSignal) => Promise<Artifact>) {
    this.upload = upload;
  }

  snapshot = () => this.state;
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  };

  private publish(items = this.state.items, errors = this.state.errors) {
    this.state = { items, errors };
    this.listeners.forEach((listener) => listener());
  }

  add(conversationId: string, files: File[]) {
    const errors: string[] = [];
    for (const file of files) {
      const existing = this.state.items.filter((item) => item.conversationId === conversationId);
      if (file.size === 0) {
        errors.push(`${file.name}：文件为空`);
        continue;
      }
      if (file.size > MAX_ATTACHMENT_BYTES) {
        errors.push(`${file.name}：单个附件不能超过 25 MB`);
        continue;
      }
      if (existing.some((item) => item.file.name === file.name && item.file.size === file.size
        && item.file.lastModified === file.lastModified && item.file.type === file.type)) {
        errors.push(`${file.name}：已经添加，请勿重复上传`);
        continue;
      }
      if (existing.length >= MAX_ATTACHMENTS) {
        errors.push(`${file.name}：每条消息最多添加 ${MAX_ATTACHMENTS} 个附件`);
        continue;
      }
      const item: AttachmentDraft = {
        id: `upload-${++this.sequence}`, conversationId, file, status: 'uploading',
        preview: file.type.startsWith('image/') ? URL.createObjectURL(file) : undefined,
      };
      this.publish([...this.state.items, item]);
      void this.start(item);
    }
    this.publish(this.state.items, { ...this.state.errors, [conversationId]: errors.join('；') });
  }

  private async start(item: AttachmentDraft) {
    const controller = new AbortController();
    this.controllers.set(item.id, controller);
    this.publish(this.state.items.map((current) => current.id === item.id
      ? { ...current, status: 'uploading', error: undefined } : current));
    try {
      const artifact = await this.upload(item.file, controller.signal);
      if (controller.signal.aborted) return;
      this.publish(this.state.items.map((current) => current.id === item.id
        ? { ...current, status: 'ready', artifact } : current));
    } catch (reason) {
      if (controller.signal.aborted) return;
      this.publish(this.state.items.map((current) => current.id === item.id
        ? { ...current, status: 'error', error: (reason as Error)?.message || '上传失败，请重试' } : current));
    } finally {
      if (this.controllers.get(item.id) === controller) this.controllers.delete(item.id);
    }
  }

  retry(id: string) {
    const item = this.state.items.find((current) => current.id === id);
    if (item?.status === 'error') void this.start(item);
  }

  remove(id: string) {
    this.controllers.get(id)?.abort();
    this.controllers.delete(id);
    const item = this.state.items.find((current) => current.id === id);
    if (item?.preview) URL.revokeObjectURL(item.preview);
    this.publish(this.state.items.filter((current) => current.id !== id));
  }

  ready(conversationId: string): Artifact[] | null {
    const items = this.state.items.filter((item) => item.conversationId === conversationId);
    if (items.some((item) => item.status !== 'ready')) return null;
    return items.map((item) => item.artifact!);
  }

  clear(conversationId: string) {
    this.state.items.filter((item) => item.conversationId === conversationId)
      .forEach((item) => this.remove(item.id));
    const errors = { ...this.state.errors };
    delete errors[conversationId];
    this.publish(this.state.items, errors);
  }

  dispose() {
    this.state.items.forEach((item) => this.remove(item.id));
    this.publish([], {});
  }
}
