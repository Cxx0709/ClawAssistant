import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import BrandMark from '../components/BrandMark';
import Markdown from '../components/Markdown';
import RightRail from '../components/RightRail';
import ToolTrace from '../components/ToolTrace';
import AgentRadarPage from './AgentRadarPage';
import { executionLabel, executionState } from '../lib/execution';
import PendingConfirmation from '../components/PendingConfirmation';
import ConversationSidebar from '../components/ConversationSidebar';
import Composer from '../components/Composer';
import MemoryNotice from '../components/MemoryNotice';
import ArtifactCard from '../components/ArtifactCard';
import WorkspaceCanvas from '../components/WorkspaceCanvas';
import { getMemories } from '../lib/memories';
import { useAttachments } from '../lib/useAttachments';
import {
  createConversation,
  deleteConversation,
  exportConversation,
  fetchConversationPage,
  fetchConversationMessages,
  fetchConversations,
  fetchRun,
  importConversationFile,
  fetchStatus,
  purgeConversation,
  updateConversation,
  confirmPendingTool,
  cancelPendingTool,
  fetchPendingTool,
} from '../lib/api';
import { consumeStream } from '../lib/sse';
import type { AppUser, Artifact, ChatMsg, Conversation, PendingToolInfo, StreamEvent, SystemStatus, ToolItem } from '../lib/types';

const SUGGESTIONS = [
  '帮我规划一趟周末杭州两日游',
  '我今天心情有点烦，能聊聊吗',
  '帮我创建一个「每周跑步 3 次」的目标',
  '记住：咖啡只喝中杯',
];

export default function ChatPage({ onHome, user, onLogout }: {
  onHome: () => void;
  user: AppUser;
  onLogout: () => void;
}) {
  const [messages, setMessages] = useState<ChatMsg[]>([]);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [radarOpen, setRadarOpen] = useState(() => new URLSearchParams(window.location.search).has('radar'));
  const [historyRefresh, setHistoryRefresh] = useState(0);
  const chatRootRef = useRef<HTMLDivElement>(null);
  const radarHistoryRef = useRef(false);
  const radarScrollRef = useRef<number | null>(null);
  const historyConversationRef = useRef<string | null>(null);
  const [railOpen, setRailOpen] = useState(false);
  const [railToken, setRailToken] = useState(0);
  const [workspaceOpen, setWorkspaceOpen] = useState(false);
  const [status, setStatus] = useState<SystemStatus | null>(null);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [archivedConversations, setArchivedConversations] = useState<Conversation[]>([]);
  const [deletedConversations, setDeletedConversations] = useState<Conversation[]>([]);
  const [conversationId, setConversationId] = useState<string | null>(null);
  const { queue, drafts, attachments, uploading, hasFailed, uploadError } = useAttachments(conversationId);
  const [conversationsLoading, setConversationsLoading] = useState(true);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState('');
  const [historyCursor, setHistoryCursor] = useState<string | null>(null);
  const [olderLoading, setOlderLoading] = useState(false);
  const [undoDelete, setUndoDelete] = useState<Conversation | null>(null);
  const [historyOpen, setHistoryOpen] = useState(() => window.innerWidth >= 768);
  // 待确认的高风险工具（Phase 5 前端确认按钮）
  const [pending, setPending] = useState<PendingToolInfo | null>(null);
  const [pendingBusy, setPendingBusy] = useState(false);
  const [pendingNotice, setPendingNotice] = useState<string | null>(null);

  const abortRef = useRef<AbortController | null>(null);
  const streamIdRef = useRef<string | null>(null);
  const seqRef = useRef(0);
  const toolKeyRef = useRef(0);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const stickRef = useRef(true);
  const scrollFrameRef = useRef<number | null>(null);
  const textBufferRef = useRef('');
  const textFlushTimerRef = useRef<number | null>(null);

  const openRadar = useCallback(() => {
    radarScrollRef.current = scrollRef.current?.scrollTop ?? null;
    const url = new URL(window.location.href);
    url.searchParams.set('radar', '');
    window.history.pushState(null, '', url);
    radarHistoryRef.current = true;
    setRadarOpen(true);
  }, []);

  const closeRadar = useCallback(() => {
    chatRootRef.current?.removeAttribute('inert');
    setRadarOpen(false);
    if (radarHistoryRef.current) {
      radarHistoryRef.current = false;
      window.history.back();
    } else {
      const url = new URL(window.location.href);
      url.searchParams.delete('radar');
      window.history.replaceState(null, '', url);
    }
  }, []);

  useEffect(() => {
    const onPopState = () => {
      radarHistoryRef.current = false;
      const open = new URLSearchParams(window.location.search).has('radar');
      const requested = new URLSearchParams(window.location.search).get('conversation');
      if (requested) setConversationId(requested);
      if (open) radarScrollRef.current = scrollRef.current?.scrollTop ?? null;
      setRadarOpen(open);
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);

  useLayoutEffect(() => {
    if (!radarOpen && radarScrollRef.current != null && scrollRef.current) {
      scrollRef.current.scrollTop = radarScrollRef.current;
      radarScrollRef.current = null;
    }
  }, [radarOpen]);

  useEffect(() => {
    const root = chatRootRef.current;
    if (radarOpen) root?.setAttribute('inert', '');
    else root?.removeAttribute('inert');
    return () => root?.removeAttribute('inert');
  }, [radarOpen]);

  useEffect(() => {
    const memoryId = new URLSearchParams(window.location.search).get('memory');
    if (!memoryId) return;
    let alive = true;
    getMemories().then(data => {
      if (!alive) return;
      const memory = data.items.find(item => item.id === memoryId && !item.disabled);
      if (memory) setText(`结合这条关于我的信息：“${memory.content}”，请帮我`);
      else setHistoryError('这条记忆已删除或停用，请从“我的记忆”重新选择');
    }).catch((reason: Error) => { if (alive) setHistoryError(reason.message); });
    return () => { alive = false; };
  }, []);

  const scrollToBottom = useCallback((behavior: ScrollBehavior = 'auto') => {
    const el = scrollRef.current;
    if (el) el.scrollTo({ top: el.scrollHeight, behavior });
  }, []);

  // 顶栏状态点
  useEffect(() => {
    let alive = true;
    fetchStatus().then((s) => alive && setStatus(s));
    return () => {
      alive = false;
    };
  }, []);

  // 待确认高风险工具轮询：发现 SafetyPolicy 拦截的操作后弹出确认卡片
  useEffect(() => {
    let alive = true;
    const tick = async () => {
      try {
        const data = await fetchPendingTool();
        if (alive) setPending(data.pending);
      } catch {
        // 网络抖动时保留当前状态，下一轮再试
      }
    };
    void tick();
    const timer = window.setInterval(() => void tick(), 3000);
    return () => { alive = false; window.clearInterval(timer); };
  }, []);

  const reloadConversations = useCallback(async () => {
    const [items, archived, deleted] = await Promise.all([
      fetchConversations(),
      fetchConversations(true),
      fetchConversationPage({ deleted: true, limit: 100 }).then((page) => page.items),
    ]);
    setConversations(items);
    setArchivedConversations(archived);
    setDeletedConversations(deleted);
    return items;
  }, []);

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const [activeItems, archivedItems, deletedItems] = await Promise.all([
          fetchConversations(),
          fetchConversations(true),
          fetchConversationPage({ deleted: true, limit: 100 }).then((page) => page.items),
        ]);
        let items = activeItems;
        if (items.length === 0) items = [await createConversation()];
        if (!alive) return;
        setConversations(items);
        setArchivedConversations(archivedItems);
        setDeletedConversations(deletedItems);
        const requested = new URLSearchParams(window.location.search).get('conversation');
        if (requested && ![...items, ...archivedItems].some(item => item.id === requested)) {
          setHistoryError('来源对话不存在或已删除，请从左侧选择其他对话');
        } else {
          setConversationId(requested || items[0].id);
        }
      } catch (reason) {
        if (alive) setHistoryError((reason as Error)?.message || '无法读取历史对话');
      } finally {
        if (alive) setConversationsLoading(false);
      }
    })();
    return () => { alive = false; };
  }, []);

  useEffect(() => {
    if (!conversationId) return;
    let alive = true;
    setHistoryLoading(true);
    setHistoryError('');
    const changedConversation = historyConversationRef.current !== conversationId;
    historyConversationRef.current = conversationId;
    if (changedConversation) {
      setMessages([]);
      setHistoryCursor(null);
    }
    const params = new URLSearchParams(window.location.search);
    params.set('conversation', conversationId);
    window.history.replaceState(null, '', `?${params}`);
    fetchConversationMessages(conversationId)
      .then((page) => {
        if (!alive) return;
        const history = page.items;
        seqRef.current = history.length;
        setMessages(history.map((message, index) => ({
          id: message.id || `h${index}`,
          createdAt: message.createdAt,
          role: message.role,
          content: message.content,
          tools: message.tools,
          skills: message.skills,
          streaming: message.status === 'STREAMING',
          status: message.status,
          runId: message.runId,
          errorText: message.errorText,
          totalMs: message.totalMs,
          artifacts: message.role === 'user' ? message.attachments : message.artifacts,
        })));
        setHistoryCursor(page.nextCursor ?? null);
        if (changedConversation) {
          stickRef.current = true;
          window.setTimeout(() => scrollToBottom(), 0);
        }
      })
      .catch((reason) => alive && setHistoryError((reason as Error)?.message || '对话加载失败'))
      .finally(() => alive && setHistoryLoading(false));
    return () => { alive = false; };
  }, [conversationId, scrollToBottom, historyRefresh]);

  useEffect(() => {
    if (busy) return; // SSE owns the active response; polling only resumes detached runs.
    const pending = messages.filter((message) => message.streaming && message.runId);
    if (pending.length === 0) return;
    let alive = true;
    const timer = window.setInterval(() => {
      pending.forEach((message) => {
        void fetchRun(message.runId!).then((fresh) => {
          if (!alive) return;
          setMessages((items) => items.map((item) => item.id === message.id ? {
            ...item,
            content: fresh.content,
            tools: fresh.tools,
            skills: fresh.skills,
            artifacts: fresh.artifacts,
            errorText: fresh.errorText,
            totalMs: fresh.totalMs,
            status: fresh.status,
            streaming: fresh.status === 'STREAMING',
          } : item));
        }).catch(() => undefined);
      });
    }, 1200);
    return () => { alive = false; window.clearInterval(timer); };
  }, [messages, busy]);

  const loadOlderMessages = useCallback(async () => {
    if (!conversationId || !historyCursor || olderLoading) return;
    setOlderLoading(true);
    try {
      const page = await fetchConversationMessages(conversationId, historyCursor);
      setMessages((current) => [
        ...page.items.map((message) => ({
          id: message.id,
          createdAt: message.createdAt,
          role: message.role,
          content: message.content,
          tools: message.tools,
          skills: message.skills,
          streaming: message.status === 'STREAMING',
          status: message.status,
          runId: message.runId,
          errorText: message.errorText,
          totalMs: message.totalMs,
          artifacts: message.role === 'user' ? message.attachments : message.artifacts,
        } as ChatMsg)),
        ...current,
      ]);
      setHistoryCursor(page.nextCursor ?? null);
    } catch (reason) {
      setHistoryError((reason as Error)?.message || '更早消息加载失败');
    } finally {
      setOlderLoading(false);
    }
  }, [conversationId, historyCursor, olderLoading]);

  const refreshRail = useCallback(() => setRailToken((t) => t + 1), []);

  // 用户是否停在底部；决定后续更新是否自动跟随滚动
  const onScroll = () => {
    const el = scrollRef.current;
    if (!el) return;
    stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 90;
  };

  // 消息/流式增量更新 → 自动跟随到底
  const last = messages[messages.length - 1];
  const watchKey = last
    ? `${messages.length}-${last.role}-${last.content.length}-${last.tools?.length ?? 0}-${!!last.streaming}-${!!last.errorText}`
    : 'idle';
  useEffect(() => {
    if (!stickRef.current) return;
    if (scrollFrameRef.current != null) {
      window.cancelAnimationFrame(scrollFrameRef.current);
    }
    scrollFrameRef.current = window.requestAnimationFrame(() => {
      scrollFrameRef.current = null;
      // Repeated smooth-scroll animations fight each other while tokens arrive.
      scrollToBottom(last?.streaming ? 'auto' : 'smooth');
    });
    return () => {
      if (scrollFrameRef.current != null) {
        window.cancelAnimationFrame(scrollFrameRef.current);
        scrollFrameRef.current = null;
      }
    };
  }, [watchKey, scrollToBottom]);

  const patchStream = useCallback((patch: (m: ChatMsg) => ChatMsg) => {
    const id = streamIdRef.current;
    if (!id) return;
    setMessages((prev) => prev.map((m) => (m.id === id ? patch(m) : m)));
  }, []);

  const flushTextBuffer = useCallback(() => {
    textFlushTimerRef.current = null;
    const buffered = textBufferRef.current;
    textBufferRef.current = '';
    if (buffered) {
      patchStream((m) => ({ ...m, content: m.content + buffered }));
    }
  }, [patchStream]);

  const queueText = useCallback((content: string) => {
    textBufferRef.current += content;
    if (textFlushTimerRef.current == null) {
      // 25 UI updates/sec is visually smooth and avoids reparsing all Markdown per token.
      textFlushTimerRef.current = window.setTimeout(flushTextBuffer, 40);
    }
  }, [flushTextBuffer]);

  const takeBufferedText = useCallback(() => {
    if (textFlushTimerRef.current != null) {
      window.clearTimeout(textFlushTimerRef.current);
      textFlushTimerRef.current = null;
    }
    const buffered = textBufferRef.current;
    textBufferRef.current = '';
    return buffered;
  }, []);

  useEffect(() => () => {
    if (textFlushTimerRef.current != null) {
      window.clearTimeout(textFlushTimerRef.current);
    }
  }, []);

  const toggleTrace = useCallback((id: string) => {
    setMessages((prev) =>
      prev.map((m) => (m.id === id ? { ...m, traceOpen: !m.traceOpen } : m)),
    );
  }, []);

  const newConversation = useCallback(async () => {
    if (busy) return;
    try {
      const created = await createConversation();
      setConversations((items) => [created, ...items]);
      setConversationId(created.id);
      setMessages([]);
    } catch (reason) {
      setHistoryError((reason as Error)?.message || '新建对话失败');
    }
  }, [busy]);

  const renameConversation = useCallback(async (id: string, title: string) => {
    try {
      const updated = await updateConversation(id, { title });
      setConversations((items) => items.map((item) => item.id === id ? updated : item));
    } catch (reason) {
      setHistoryError((reason as Error)?.message || '重命名失败');
    }
  }, []);

  const pinConversation = useCallback(async (id: string, pinned: boolean) => {
    try {
      await updateConversation(id, { pinned });
      await reloadConversations();
    } catch (reason) {
      setHistoryError((reason as Error)?.message || '置顶失败');
    }
  }, [reloadConversations]);

  const archiveConversation = useCallback(async (id: string, archived: boolean) => {
    if (busy) return;
    try {
      await updateConversation(id, { archived });
      let items = await reloadConversations();
      if (archived && id === conversationId) {
        if (items.length === 0) items = [await createConversation()];
        setConversations(items);
        setConversationId(items[0].id);
      }
    } catch (reason) {
      setHistoryError((reason as Error)?.message || '归档失败');
    }
  }, [busy, conversationId, reloadConversations]);

  const removeConversation = useCallback(async (id: string) => {
    if (busy) return;
    try {
      const removed = [...conversations, ...archivedConversations].find((item) => item.id === id) ?? null;
      await deleteConversation(id);
      setUndoDelete(removed);
      let items = await reloadConversations();
      if (id === conversationId) {
        if (items.length === 0) items = [await createConversation()];
        setConversations(items);
        setConversationId(items[0].id);
      }
    } catch (reason) {
      setHistoryError((reason as Error)?.message || '删除对话失败');
    }
  }, [archivedConversations, busy, conversationId, conversations, reloadConversations]);

  const restoreConversation = useCallback(async (id: string) => {
    try {
      await updateConversation(id, { deleted: false });
      await reloadConversations();
      setUndoDelete(null);
    } catch (reason) {
      setHistoryError((reason as Error)?.message || '恢复对话失败');
    }
  }, [reloadConversations]);

  const permanentlyDeleteConversation = useCallback(async (id: string) => {
    if (!window.confirm('这会永久删除消息和运行记录，且无法撤销。确定继续吗？')) return;
    try {
      await purgeConversation(id);
      await reloadConversations();
    } catch (reason) {
      setHistoryError((reason as Error)?.message || '永久删除失败');
    }
  }, [reloadConversations]);

  const downloadConversation = useCallback(async (conversation: Conversation) => {
    try {
      await exportConversation(conversation.id, conversation.title);
    } catch (reason) {
      setHistoryError((reason as Error)?.message || '导出失败');
    }
  }, []);

  const importConversation = useCallback(async (file: File) => {
    if (busy) return;
    try {
      const created = await importConversationFile(file);
      await reloadConversations();
      setConversationId(created.id);
    } catch (reason) {
      setHistoryError((reason as Error)?.message || '导入失败');
    }
  }, [busy, reloadConversations]);

  const handleEvent = useCallback(
    (startedAt: number) => (evt: StreamEvent) => {
      switch (evt.type) {
        case 'run':
          setMessages((items) => {
            let lastUserIndex = -1;
            for (let index = items.length - 1; index >= 0; index--) {
              if (items[index].role === 'user') { lastUserIndex = index; break; }
            }
            const localAssistantId = streamIdRef.current;
            streamIdRef.current = evt.assistantMessageId;
            return items.map((item, index) => index === lastUserIndex
              ? { ...item, id: evt.userMessageId }
              : item.id === localAssistantId
                ? { ...item, id: evt.assistantMessageId, runId: evt.runId, status: 'STREAMING' }
                : item);
          });
          break;
        case 'skill':
          patchStream((m) => ({
            ...m,
            skills: m.skills && m.skills.includes(evt.name) ? m.skills : [...(m.skills ?? []), evt.name],
          }));
          break;
        case 'tool_start': {
          toolKeyRef.current += 1;
          const key = `t${toolKeyRef.current}`;
          patchStream((m) => ({
            ...m,
            tools: [
              ...(m.tools ?? []),
              { id: key, name: evt.name, skill: evt.skill ?? '', state: 'running' as const },
            ],
          }));
          break;
        }
        case 'tool_end':
          patchStream((m) => {
            const tools = m.tools ?? [];
            // 顺序执行：匹配同名里最后一个仍在运行的实例
            let idx = -1;
            for (let i = tools.length - 1; i >= 0; i--) {
              if (tools[i].state === 'running' && tools[i].name === evt.name) {
                idx = i;
                break;
              }
            }
            if (idx === -1) return m;
            const next = tools.slice();
            next[idx] = {
              ...next[idx],
              state: evt.ok ? 'ok' : 'err',
              durationMs: evt.durationMs,
              detail: evt.detail,
            };
            return { ...m, tools: next };
          });
          break;
        case 'tool_trace': {
          const item = evt.item;
          patchStream((m) => {
            const tools = m.tools ?? [];
            if (item.eventType === 'UPDATE') {
              const idx = tools.findIndex((t) => t.traceId != null && t.traceId === item.traceId);
              if (idx === -1) return m;
              const next = tools.slice();
              next[idx] = {
                ...next[idx],
                state: item.state as ToolItem['state'],
                durationMs: item.durationMs,
                detail: item.detail,
              };
              return { ...m, tools: next };
            }
            return {
              ...m,
              tools: [...tools, item],
            };
          });
          break;
        }
        case 'text':
          queueText(evt.content);
          break;
        case 'done': {
          const buffered = takeBufferedText();
          // done 携带全文，以它为最终兜底（流式丢字也不漏）
          patchStream((m) => ({
            ...m,
            content: evt.reply || m.content + buffered,
            totalMs: Date.now() - startedAt,
            streaming: false,
            status: 'COMPLETED',
            artifacts: evt.artifacts ?? [],
          }));
          refreshRail();
          void reloadConversations();
          break;
        }
        case 'error': {
          const buffered = takeBufferedText();
          patchStream((m) => ({
            ...m,
            content: m.content + buffered,
            errorText: evt.message || '处理失败，请稍后再试',
            streaming: false,
            status: 'FAILED',
          }));
          refreshRail();
          break;
        }
      }
    },
    [patchStream, queueText, refreshRail, reloadConversations, takeBufferedText],
  );

  const send = useCallback(
    (raw: string) => {
      const content = raw.trim();
      if ((!content && attachments.length === 0) || busy || uploading || !conversationId || historyLoading) return;
      const selectedAttachments = queue.ready(conversationId);
      if (!selectedAttachments) return;
      setText('');
      queue.clear(conversationId);

      seqRef.current += 1;
      const uid = `u${seqRef.current}`;
      const aid = `a${seqRef.current}`;
      const startedAt = Date.now();

      takeBufferedText();

      setMessages((prev) => [
        ...prev,
        { id: uid, createdAt: startedAt, role: 'user', content: content || '请处理这些附件', artifacts: selectedAttachments },
        { id: aid, createdAt: startedAt, role: 'assistant', content: '', tools: [], skills: [], streaming: true },
      ]);
      streamIdRef.current = aid;
      setBusy(true);

      const ctrl = new AbortController();
      abortRef.current = ctrl;

      consumeStream('/api/webchat/stream', {
        message: content,
        attachmentIds: selectedAttachments.map((item) => item.id),
        conversationId,
      }, ctrl.signal, handleEvent(startedAt))
        .catch((err: unknown) => {
          const name = (err as Error)?.name;
          if (name === 'AbortError') {
            patchStream((m) => ({ ...m, streaming: true, errorText: undefined }));
            return; // 后端继续运行，轮询已保存的 run
          }
          patchStream((m) => ({
            ...m,
            errorText: (err as Error)?.message || '网络异常，请稍后再试',
            streaming: false,
          }));
          refreshRail();
        })
        .finally(() => {
          setBusy(false);
          abortRef.current = null;
          streamIdRef.current = null;
        });
    },
    [attachments, busy, conversationId, handleEvent, historyLoading, patchStream, queue, refreshRail, takeBufferedText, uploading],
  );

  const uploadFiles = useCallback((files: File[]) => {
    if (!files.length || busy || !conversationId || historyLoading) return;
    queue.add(conversationId, files);
  }, [busy, conversationId, historyLoading, queue]);

  const stop = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  const confirmPending = useCallback(async () => {
    if (pendingBusy || !pending) return;
    setPendingBusy(true);
    try {
      const res = await confirmPendingTool();
      setPending(null);
      const replyText = res.reply || '操作已确认执行。';
      setPendingNotice(replyText);
      // 确认结果作为新的助手消息写入聊天记录，避免只停留在"此操作需要确认"
      if (res.reply) {
        setMessages((prev) => [...prev, {
          id: `confirm-${Date.now()}`,
          role: 'assistant' as const,
          content: res.reply,
        }]);
      }
      // in-place trace update: WAIT_CONFIRM -> res.traceState
      if (res.traceId) {
        setMessages((prev) => prev.map((m) => {
          const tools = m.tools;
          if (!tools) return m;
          const idx = tools.findIndex((t) => t.traceId != null && t.traceId === res.traceId);
          if (idx === -1) return m;
          const next = tools.slice();
          const st = (res.traceState ?? 'ok') as ToolItem['state'];
          next[idx] = { ...next[idx], state: st, durationMs: 0, detail: st === 'ok' ? 'confirmed' : 'cancelled' };
          return { ...m, tools: next };
        }));
      }
    } catch (reason) {
      setPendingNotice((reason as Error)?.message || '确认失败，请稍后再试');
    } finally {
      setPendingBusy(false);
    }
  }, [pending, pendingBusy]);

  const cancelPending = useCallback(async () => {
    if (pendingBusy || !pending) return;
    setPendingBusy(true);
    try {
      const res = await cancelPendingTool();
      setPending(null);
      setPendingNotice(res.reply || '操作已取消。');
      // in-place trace update: WAIT_CONFIRM -> res.traceState
      if (res.traceId) {
        setMessages((prev) => prev.map((m) => {
          const tools = m.tools;
          if (!tools) return m;
          const idx = tools.findIndex((t) => t.traceId != null && t.traceId === res.traceId);
          if (idx === -1) return m;
          const next = tools.slice();
          const st = (res.traceState ?? 'ok') as ToolItem['state'];
          next[idx] = { ...next[idx], state: st, durationMs: 0, detail: st === 'ok' ? 'confirmed' : 'cancelled' };
          return { ...m, tools: next };
        }));
      }
    } catch (reason) {
      setPendingNotice((reason as Error)?.message || '取消失败，请稍后再试');
    } finally {
      setPendingBusy(false);
    }
  }, [pending, pendingBusy]);

  // 确认/取消结果提示自动消失
  useEffect(() => {
    if (!pendingNotice) return;
    const timer = window.setTimeout(() => setPendingNotice(null), 8000);
    return () => window.clearTimeout(timer);
  }, [pendingNotice]);

  const connected = status?.appReady;

  return (
    <>
    <div ref={chatRootRef} aria-hidden={radarOpen || undefined} className="flex h-dvh flex-col bg-canvas text-ink">
      {/* ===== 顶栏 ===== */}
      <header className="flex h-[58px] shrink-0 items-center gap-1 border-b border-line px-3 sm:gap-3 sm:px-4">
        <button
          type="button"
          onClick={() => setHistoryOpen((value) => !value)}
          title="历史对话"
          aria-pressed={historyOpen}
          className={`flex h-9 w-9 items-center justify-center rounded-lg transition-colors ${historyOpen ? 'bg-canvas-sub text-ink' : 'text-ink-soft hover:bg-canvas-sub'}`}
        >
          <svg viewBox="0 0 24 24" className="h-[17px] w-[17px]" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><path d="M4 5.5h16v13H4zM9 5.5v13" /></svg>
        </button>
        <button
          type="button"
          onClick={onHome}
          title="返回首页"
          className="flex h-9 w-9 items-center justify-center rounded-lg text-ink-soft transition-colors hover:bg-canvas-sub hover:text-ink"
        >
          <svg viewBox="0 0 24 24" className="h-[18px] w-[18px]" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M15 5l-7 7 7 7" />
          </svg>
        </button>

        <div className="flex items-center gap-2.5">
          <BrandMark size={28} />
          <div className="hidden leading-tight sm:block">
            <p className="text-[13.5px] font-semibold">Claw Assistant</p>
            <p className="flex items-center gap-1 text-[11px] text-ink-faint">
              <span className={`inline-block h-1.5 w-1.5 rounded-full ${connected ? 'bg-[#34c759]' : 'bg-ink-faint'}`} />
              {connected ? 'Web 助手在线' : '初始化中'}
            </p>
          </div>
        </div>

        <p className="absolute left-1/2 hidden max-w-[24vw] -translate-x-1/2 truncate text-[13px] font-medium text-ink-soft xl:block">
          {conversations.find((item) => item.id === conversationId)?.title || '新对话'}
        </p>

        <div className="ml-auto flex shrink-0 items-center gap-1 sm:gap-3">
          <span className="hidden max-w-24 truncate text-xs text-ink-faint lg:inline">{user.displayName}</span>
          <a
            href="?memories"
            target="_blank"
            rel="noopener noreferrer"
            title="我的记忆（在新标签页打开）"
            aria-label="我的记忆（在新标签页打开）"
            className="flex h-9 items-center gap-1.5 rounded-lg border border-line px-2 text-[12.5px] font-medium text-ink-soft transition-colors hover:bg-canvas-sub hover:text-ink focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand sm:px-3"
          >
            <svg aria-hidden="true" viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
              <ellipse cx="12" cy="5" rx="8" ry="3" />
              <path d="M4 5v7c0 1.7 3.6 3 8 3s8-1.3 8-3V5M4 12v7c0 1.7 3.6 3 8 3s8-1.3 8-3v-7" />
            </svg>
            <span className="hidden sm:inline">我的记忆</span>
          </a>
          <button
            type="button"
            onClick={() => { setWorkspaceOpen(false); setRailOpen((v) => !v); }}
            aria-label="会话信息"
            aria-pressed={railOpen}
            className={`flex h-9 items-center gap-1.5 rounded-lg border px-3 text-[12.5px] font-medium transition-colors ${
              railOpen
                ? 'border-brand/30 bg-brand-dim text-brand-deep'
                : 'border-line text-ink-soft hover:bg-canvas-sub hover:text-ink'
            }`}
          >
            <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
              <rect x="3" y="4" width="18" height="16" rx="2.5" />
              <path d="M9 4v16M3 9h6M3 14h6" />
            </svg>
            <span className="hidden sm:inline">信息</span>
          </button>
          <button
            type="button"
            onClick={() => { setRailOpen(false); setWorkspaceOpen((v) => !v); }}
            aria-label="工作台"
            aria-pressed={workspaceOpen}
            className={`flex h-9 items-center gap-1.5 rounded-lg border px-3 text-[12.5px] font-medium transition-colors ${
              workspaceOpen
                ? 'border-brand/30 bg-brand-dim text-brand-deep'
                : 'border-line text-ink-soft hover:bg-canvas-sub hover:text-ink'
            }`}
          >
            <span>🎨</span>
            <span className="hidden sm:inline">工作台</span>
          </button>
          <button
            type="button"
            onClick={openRadar}
            aria-label="查看当前会话执行记录"
            className="flex h-9 items-center gap-1.5 rounded-lg border border-line px-3 text-[12.5px] font-medium text-ink-soft transition-colors hover:bg-canvas-sub hover:text-ink"
          >
            <span>📡</span>
            <span className="hidden sm:inline">雷达</span>
          </button>
          <button type="button" onClick={onLogout} className="h-9 px-2 text-xs text-ink-faint hover:text-ink">退出</button>
        </div>
      </header>

      {/* ===== 主体 ===== */}
      <div className="relative flex min-h-0 flex-1">
        {historyOpen && (
          <>
            <div className="hidden h-full shrink-0 md:block">
              <ConversationSidebar
                conversations={conversations}
                archivedConversations={archivedConversations}
                deletedConversations={deletedConversations}
                activeId={conversationId}
                loading={conversationsLoading}
                disabled={busy}
                onSelect={setConversationId}
                onNew={newConversation}
                onRename={renameConversation}
                onPin={pinConversation}
                onArchive={archiveConversation}
                onDelete={removeConversation}
                onRestore={restoreConversation}
                onPurge={permanentlyDeleteConversation}
                onExport={downloadConversation}
                onImport={importConversation}
              />
            </div>
            <div className="fixed inset-0 z-40 bg-ink/20 backdrop-blur-[1px] md:hidden" onClick={() => setHistoryOpen(false)} />
            <div className="fixed inset-y-0 left-0 z-50 shadow-[8px_0_30px_-18px_rgba(20,21,23,.3)] md:hidden">
              <ConversationSidebar
                conversations={conversations}
                archivedConversations={archivedConversations}
                deletedConversations={deletedConversations}
                activeId={conversationId}
                loading={conversationsLoading}
                disabled={busy}
                onSelect={(id) => { setConversationId(id); setHistoryOpen(false); }}
                onNew={() => { void newConversation(); setHistoryOpen(false); }}
                onRename={renameConversation}
                onPin={pinConversation}
                onArchive={archiveConversation}
                onDelete={removeConversation}
                onRestore={restoreConversation}
                onPurge={permanentlyDeleteConversation}
                onExport={downloadConversation}
                onImport={importConversation}
                onClose={() => setHistoryOpen(false)}
              />
            </div>
          </>
        )}
        <main className="flex min-w-0 flex-1 flex-col">
          {/* 线程滚动区 */}
          <div ref={scrollRef} onScroll={onScroll} className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
            <div className="mx-auto w-full max-w-[760px] px-4 pb-10 pt-6 sm:px-6">
              {!historyLoading && historyCursor && (
                <div className="mb-5 text-center">
                  <button type="button" onClick={() => void loadOlderMessages()} disabled={olderLoading}
                    className="rounded-full border border-line bg-white px-3 py-1.5 text-xs text-ink-soft hover:border-brand/30 hover:text-ink disabled:opacity-50">
                    {olderLoading ? '正在加载…' : '加载更早消息'}
                  </button>
                </div>
              )}
              {historyError && (
                <div className="mb-5 rounded-xl border border-[#f3c8c8] bg-[#fdf3f3] px-3.5 py-3 text-[13px] text-[#a63a32]">{historyError}</div>
              )}
              {historyLoading && <HistorySkeleton />}
              {!historyLoading && messages.length === 0 ? <EmptyState onPick={(s) => send(s)} /> : null}
              <MessageList
                messages={messages}
                onToggleTrace={toggleTrace}
              />
            </div>
          </div>

          {/* 输入区 */}
          <div className="shrink-0 border-t border-line/70 bg-gradient-to-t from-canvas via-canvas to-canvas/90 pb-3 pt-3">
            <div className="mx-auto w-full max-w-[760px] px-4 sm:px-6">
              {pendingNotice && (
                <div className="mb-2 flex items-center gap-2 rounded-xl border border-[#d7e8dc] bg-[#f0f8f2] px-3.5 py-2.5 text-[13px] text-[#1f7a44]">
                  <span className="shrink-0 font-semibold">✓</span>
                  <span className="min-w-0 flex-1">{pendingNotice}</span>
                  <button type="button" onClick={() => setPendingNotice(null)} className="shrink-0 text-ink-faint hover:text-ink" aria-label="关闭">×</button>
                </div>
              )}
              {pending && (
                <PendingConfirmation
                  pending={pending}
                  busy={pendingBusy}
                  onConfirm={() => void confirmPending()}
                  onCancel={() => void cancelPending()}
                />
              )}
              <MemoryNotice conversationId={conversationId} refreshToken={railToken} />
              <Composer
                key={conversationId}
                text={text}
                busy={busy}
                disabled={!conversationId || historyLoading}
                onChange={setText}
                onSend={() => send(text)}
                onStop={stop}
                attachments={drafts}
                uploading={uploading}
                hasFailed={hasFailed}
                uploadError={uploadError}
                onFiles={uploadFiles}
                onRemove={(id) => queue.remove(id)}
                onRetry={(id) => queue.retry(id)}
              />
              <p className="mt-2 text-center text-[11px] text-ink-faint">
                Claw 也会犯错，重要信息请以官方渠道为准
              </p>
            </div>
          </div>
        </main>

        {/* 右侧信息抽屉（宽屏占位 / 窄屏悬浮） */}
        {railOpen && (
          <>
            <div
              className="fixed inset-0 z-30 bg-ink/20 backdrop-blur-[1px] lg:hidden"
              onClick={() => setRailOpen(false)}
            />
            <div className="fixed inset-y-0 right-0 z-40 w-[85vw] max-w-[340px] shadow-[-8px_0_30px_-18px_rgba(20,21,23,.25)] lg:static lg:z-auto lg:w-auto lg:max-w-none lg:shrink-0 lg:shadow-none">
              <RightRail refreshToken={railToken} />
            </div>
          </>
        )}

        {/* 右侧工作台（宽屏占位 / 窄屏悬浮） */}
        {workspaceOpen && (
          <>
            <div
              className="fixed inset-0 z-30 bg-ink/20 backdrop-blur-[1px] lg:hidden"
              onClick={() => setWorkspaceOpen(false)}
            />
            <div className="fixed inset-y-0 right-0 z-40 w-[85vw] max-w-[400px] shadow-[-8px_0_30px_-18px_rgba(20,21,23,.25)] lg:static lg:z-auto lg:w-[400px] lg:max-w-none lg:shrink-0 lg:shadow-none">
              <WorkspaceCanvas />
            </div>
          </>
        )}
      </div>
      {undoDelete && (
        <div className="fixed bottom-24 left-1/2 z-[70] flex -translate-x-1/2 items-center gap-4 rounded-xl border border-line bg-ink px-4 py-3 text-xs text-white shadow-pop">
          <span>“{undoDelete.title}”已移到回收站</span>
          <button type="button" onClick={() => void restoreConversation(undoDelete.id)} className="font-semibold text-[#91dfca] hover:text-white">撤销</button>
          <button type="button" onClick={() => setUndoDelete(null)} className="text-white/60 hover:text-white" aria-label="关闭">×</button>
        </div>
      )}
    </div>
    {radarOpen && <AgentRadarPage
      key={conversationId}
      messages={historyLoading ? [] : messages}
      conversationTitle={[...conversations, ...archivedConversations].find(item => item.id === conversationId)?.title || '当前会话'}
      loading={historyLoading || conversationsLoading}
      error={historyError}
      canRefresh={!!conversationId && !busy && !messages.some(message => message.streaming)}
      hasOlder={!!historyCursor}
      loadingOlder={olderLoading}
      onRefresh={() => setHistoryRefresh(value => value + 1)}
      onLoadOlder={() => void loadOlderMessages()}
      onBack={closeRadar}
    />}
    </>
  );
}

/* ================= 子组件 ================= */

function HistorySkeleton() {
  return (
    <div className="space-y-7 py-6" aria-label="正在加载对话">
      <div className="ml-auto h-10 w-2/5 animate-pulse rounded-2xl bg-bubble" />
      <div className="space-y-2">
        <div className="h-3 w-4/5 animate-pulse rounded bg-canvas-sub" />
        <div className="h-3 w-3/5 animate-pulse rounded bg-canvas-sub" />
      </div>
    </div>
  );
}

function EmptyState({ onPick }: { onPick: (s: string) => void }) {
  return (
    <div className="flex flex-col items-center pt-[14vh] text-center">
      <BrandMark size={56} />
      <h1 className="mt-5 text-xl font-semibold tracking-tight">今天想让我帮你做点什么？</h1>
      <p className="mt-1.5 text-sm text-ink-soft">
        会记忆、会规划、会主动跟进目标，也能读写图片、语音和文件
      </p>
      <div className="mt-8 flex w-full max-w-[520px] flex-col items-stretch gap-2 sm:flex-row sm:flex-wrap sm:justify-center">
        {SUGGESTIONS.map((s) => (
          <button
            key={s}
            type="button"
            onClick={() => onPick(s)}
            className="rounded-xl border border-line bg-white px-3.5 py-2.5 text-left text-[13px] text-ink-soft transition-all hover:border-brand/40 hover:text-ink hover:shadow-pop"
          >
            {s}
          </button>
        ))}
      </div>
    </div>
  );
}

function MessageList({
  messages,
  onToggleTrace,
}: {
  messages: ChatMsg[];
  onToggleTrace: (id: string) => void;
}) {
  return (
    <div className="space-y-6">
      {messages.map((m) =>
        m.role === 'user' ? (
          <div key={m.id} className="flex flex-col items-end gap-2">
            <p className="max-w-[82%] whitespace-pre-wrap rounded-2xl bg-bubble px-4 py-2.5 text-[14.5px] leading-relaxed text-ink sm:max-w-[68%]">{m.content}</p>
            <ArtifactList artifacts={m.artifacts ?? []} compact />
          </div>
        ) : (
          <div key={m.id} className="min-w-0">
            {m.errorText ? (
              <div className="flex items-start gap-2.5 rounded-xl border border-[#f3c8c8] bg-[#fdf3f3] px-3.5 py-3 text-[13.5px] text-[#c0392b]">
                <svg viewBox="0 0 24 24" className="mt-0.5 h-4 w-4 shrink-0" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                  <circle cx="12" cy="12" r="9" />
                  <path d="M12 8v5M12 16.5v.01" />
                </svg>
                <span>{m.errorText}</span>
              </div>
            ) : null}

            <ToolTrace
              tools={m.tools ?? []}
              skills={m.skills}
              running={executionState(m) === 'running'}
              open={!!m.traceOpen}
              totalMs={m.totalMs}
              onToggle={() => onToggleTrace(m.id)}
            />

            {m.streaming && !m.content && !m.errorText ? (
              <TypingDots action={executionLabel(m)} />
            ) : (
              m.content && <Markdown content={m.content} />
            )}
            {!!(m.content || !m.streaming) && !!(m.runId || m.tools?.length || m.errorText) && (
              <p role="status" className={`mt-2 text-xs ${executionState(m) === 'failed' ? 'text-red-700' : 'text-ink-soft'}`}>
                {executionLabel(m)}
              </p>
            )}
            <ArtifactList artifacts={m.artifacts ?? []} />
          </div>
        ),
      )}
    </div>
  );
}

function ArtifactList({ artifacts, compact = false }: { artifacts: Artifact[]; compact?: boolean }) {
  if (artifacts.length === 0) return null;
  return (
    <div className={`mt-3 grid gap-2 ${compact ? 'max-w-[82%]' : 'sm:grid-cols-2'}`}>
      {artifacts.map((artifact) => (
        <div key={artifact.id} className="flex flex-col gap-1">
          <ArtifactCard
            artifact={artifact}
            compact={compact}
          />
        </div>
      ))}
    </div>
  );
}

function TypingDots({ action }: { action?: string }) {
  return (
    <div className="mt-2 flex items-center gap-1.5 pl-1">
      {[0, 1, 2].map((i) => (
        <span
          key={i}
          className="h-1.5 w-1.5 rounded-full bg-brand animate-blink"
          style={{ animationDelay: `${i * 180}ms` }}
        />
      ))}
      <span className="ml-1 text-xs text-ink-soft font-medium">
        {action || '思考中…'}
      </span>
    </div>
  );
}
