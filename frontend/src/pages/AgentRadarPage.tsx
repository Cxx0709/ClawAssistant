import { useState, useEffect } from 'react';

interface AgentStatus {
  id: string;
  name: string;
  type: 'main' | 'skill' | 'tool';
  status: 'idle' | 'running' | 'completed' | 'error';
  startTime?: number;
  endTime?: number;
  durationMs?: number;
  parent?: string;
  children?: string[];
  error?: string;
}

interface ExecutionTrace {
  id: string;
  type: 'skill' | 'tool' | 'llm' | 'artifact';
  name: string;
  status: 'pending' | 'running' | 'completed' | 'failed';
  startTime: number;
  endTime?: number;
  durationMs?: number;
  parent?: string;
  depth: number;
  metadata?: Record<string, unknown>;
}

interface AgentRadarPageProps {
  onBack?: () => void;
}

export default function AgentRadarPage({ onBack }: AgentRadarPageProps) {
  const [agents, setAgents] = useState<AgentStatus[]>([]);
  const [traces, setTraces] = useState<ExecutionTrace[]>([]);
  const [selectedTrace, setSelectedTrace] = useState<string | null>(null);
  const [isLive, setIsLive] = useState(true);
  const [timeRange, setTimeRange] = useState<'1h' | '6h' | '24h' | '7d'>('1h');

  // 模拟数据加载
  useEffect(() => {
    const load = async () => {
      // TODO: 替换为真实API调用
      const mockAgents: AgentStatus[] = [
        { id: 'main', name: '主Agent', type: 'main', status: 'running', startTime: Date.now() - 5000 },
        { id: 'skill-1', name: 'travel-planner', type: 'skill', status: 'running', parent: 'main', startTime: Date.now() - 3000 },
        { id: 'tool-1', name: 'travel_collect', type: 'tool', status: 'completed', parent: 'skill-1', startTime: Date.now() - 2000, endTime: Date.now() - 1000, durationMs: 1000 },
      ];

      const mockTraces: ExecutionTrace[] = [
        { id: '1', type: 'skill', name: 'travel-planner', status: 'running', startTime: Date.now() - 3000, depth: 0 },
        { id: '2', type: 'tool', name: 'travel_collect', status: 'completed', startTime: Date.now() - 2000, endTime: Date.now() - 1000, durationMs: 1000, parent: '1', depth: 1 },
        { id: '3', type: 'tool', name: 'travel_save_options', status: 'pending', startTime: Date.now() - 500, parent: '1', depth: 1 },
      ];

      setAgents(mockAgents);
      setTraces(mockTraces);
    };

    load();
  }, [timeRange]);

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'running': return 'bg-brand text-white';
      case 'completed': return 'bg-ok text-white';
      case 'failed': case 'error': return 'bg-red-500 text-white';
      case 'idle': case 'pending': return 'bg-gray-200 text-gray-600';
      default: return 'bg-gray-200 text-gray-600';
    }
  };

  const getTypeIcon = (type: string) => {
    switch (type) {
      case 'main': return '🤖';
      case 'skill': return '⚡';
      case 'tool': return '🔧';
      case 'llm': return '🧠';
      case 'artifact': return '📎';
      default: return '❓';
    }
  };

  const formatDuration = (ms?: number) => {
    if (!ms) return '-';
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
  };

  const selectedTraceData = traces.find(t => t.id === selectedTrace);

  return (
    <div className="flex h-full flex-col bg-[var(--color-canvas)]">
      {/* Header */}
      <div className="flex items-center justify-between border-b border-[var(--color-border)] px-6 py-4">
        <div className="flex items-center gap-3">
          <button
            onClick={onBack}
            className="rounded-lg p-2 text-[var(--color-text-muted)] hover:bg-[var(--color-border)]"
          >
            ← 返回
          </button>
          <h1 className="text-xl font-bold text-[var(--color-text)]">Agent 雷达</h1>
        </div>

        <div className="flex items-center gap-3">
          {/* 时间范围选择 */}
          <select
            value={timeRange}
            onChange={(e) => setTimeRange(e.target.value as typeof timeRange)}
            className="rounded-lg border border-[var(--color-border)] bg-[var(--color-surface)] px-3 py-2 text-sm"
          >
            <option value="1h">最近1小时</option>
            <option value="6h">最近6小时</option>
            <option value="24h">最近24小时</option>
            <option value="7d">最近7天</option>
          </select>

          {/* 实时开关 */}
          <button
            onClick={() => setIsLive(!isLive)}
            className={`flex items-center gap-2 rounded-lg px-4 py-2 text-sm font-medium ${
              isLive ? 'bg-ok text-white' : 'bg-[var(--color-border)] text-[var(--color-text-muted)]'
            }`}
          >
            <span className={`h-2 w-2 rounded-full ${isLive ? 'animate-pulse bg-white' : 'bg-gray-400'}`} />
            {isLive ? '实时' : '暂停'}
          </button>
        </div>
      </div>

      {/* Content */}
      <div className="flex flex-1 overflow-hidden">
        {/* 左侧：Agent状态列表 */}
        <div className="w-80 border-r border-[var(--color-border)] overflow-y-auto p-4">
          <h3 className="mb-3 text-sm font-semibold text-[var(--color-text-muted)]">活跃 Agent</h3>
          <div className="space-y-2">
            {agents.map((agent) => (
              <div
                key={agent.id}
                className="rounded-lg border border-[var(--color-border)] p-3 transition-colors hover:border-brand/30"
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span>{getTypeIcon(agent.type)}</span>
                    <span className="font-medium text-[var(--color-text)]">{agent.name}</span>
                  </div>
                  <span className={`rounded-full px-2 py-0.5 text-xs ${getStatusColor(agent.status)}`}>
                    {agent.status}
                  </span>
                </div>
                {agent.durationMs && (
                  <p className="mt-1 text-xs text-[var(--color-text-muted)]">
                    耗时: {formatDuration(agent.durationMs)}
                  </p>
                )}
              </div>
            ))}
          </div>
        </div>

        {/* 右侧：执行轨迹时间线 */}
        <div className="flex-1 overflow-y-auto p-6">
          <h3 className="mb-4 text-sm font-semibold text-[var(--color-text-muted)]">执行轨迹</h3>

          {/* 时间线 */}
          <div className="relative">
            {traces.map((trace, index) => (
              <div
                key={trace.id}
                className={`relative flex items-start gap-4 pb-6 cursor-pointer transition-colors ${
                  selectedTrace === trace.id ? 'bg-brand/5 rounded-lg' : ''
                }`}
                style={{ paddingLeft: `${trace.depth * 24 + 16}px` }}
                onClick={() => setSelectedTrace(trace.id)}
              >
                {/* 连接线 */}
                {index > 0 && (
                  <div
                    className="absolute left-0 top-0 h-full w-px bg-[var(--color-border)]"
                    style={{ left: `${trace.depth * 24 + 24}px` }}
                  />
                )}

                {/* 节点 */}
                <div className={`relative z-10 flex h-8 w-8 items-center justify-center rounded-full ${getStatusColor(trace.status)}`}>
                  {getTypeIcon(trace.type)}
                </div>

                {/* 内容 */}
                <div className="flex-1">
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-[var(--color-text)]">{trace.name}</span>
                    <span className="text-xs text-[var(--color-text-muted)]">
                      {formatDuration(trace.durationMs)}
                    </span>
                  </div>
                  <p className="mt-1 text-xs text-[var(--color-text-muted)]">
                    {trace.type} · {trace.status}
                  </p>
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* 右侧详情面板 */}
        {selectedTraceData && (
          <div className="w-80 border-l border-[var(--color-border)] overflow-y-auto p-4">
            <h3 className="mb-3 text-sm font-semibold text-[var(--color-text-muted)]">详情</h3>
            <div className="space-y-3">
              <div>
                <p className="text-xs text-[var(--color-text-muted)]">名称</p>
                <p className="font-medium">{selectedTraceData.name}</p>
              </div>
              <div>
                <p className="text-xs text-[var(--color-text-muted)]">类型</p>
                <p className="font-medium">{selectedTraceData.type}</p>
              </div>
              <div>
                <p className="text-xs text-[var(--color-text-muted)]">状态</p>
                <span className={`inline-block rounded-full px-2 py-0.5 text-xs ${getStatusColor(selectedTraceData.status)}`}>
                  {selectedTraceData.status}
                </span>
              </div>
              <div>
                <p className="text-xs text-[var(--color-text-muted)]">耗时</p>
                <p className="font-medium">{formatDuration(selectedTraceData.durationMs)}</p>
              </div>
              {selectedTraceData.metadata && (
                <div>
                  <p className="text-xs text-[var(--color-text-muted)]">元数据</p>
                  <pre className="mt-1 rounded-lg bg-[var(--color-surface)] p-2 text-xs overflow-x-auto">
                    {JSON.stringify(selectedTraceData.metadata, null, 2)}
                  </pre>
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
