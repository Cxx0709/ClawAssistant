import { useEffect, useState, useSyncExternalStore } from 'react';
import { uploadArtifact } from './api';
import { AttachmentQueue } from './attachmentQueue';

export function useAttachments(conversationId: string | null) {
  const [queue] = useState(() => new AttachmentQueue(uploadArtifact));
  const state = useSyncExternalStore(queue.subscribe, queue.snapshot);
  useEffect(() => () => queue.dispose(), [queue]);
  const drafts = state.items.filter((item) => item.conversationId === conversationId);
  return {
    queue,
    drafts,
    attachments: drafts.flatMap((item) => item.artifact ? [item.artifact] : []),
    uploading: drafts.some((item) => item.status === 'uploading'),
    hasFailed: drafts.some((item) => item.status === 'error'),
    uploadError: conversationId ? state.errors[conversationId] || '' : '',
  };
}
