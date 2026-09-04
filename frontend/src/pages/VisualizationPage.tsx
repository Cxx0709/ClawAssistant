import { useEffect, useRef, useState } from 'react';
import { apiFetch } from '../lib/api';

interface SkillPoint {
  skillName: string;
  description: string | null;
  priority: number;
  tags: string[];
  exampleCount: number;
  embeddingReady: boolean;
}
interface SkillData {
  points: SkillPoint[];
  totalSkills: number;
  matchThreshold: number;
  timestamp: number;
}
interface SimilarityData {
  message: string;
  similarities: { skillName: string; confidence: number | null; matched: boolean; reason: string }[];
}

async function request<T>(path: string, init: RequestInit): Promise<T> {
  const response = await apiFetch(`/api/visualization/${path}`, init);
  if (!response.ok) {
    if (response.status === 401) throw new Error('登录已过期，请返回首页重新登录');
    if (response.status === 404) throw new Error('未找到向量诊断接口，请重启更新后的后端服务');
    if (response.status === 503) throw new Error('技能向量或嵌入服务尚未就绪，请检查后端初始化日志和模型连接');
    throw new Error(`向量诊断请求失败（HTTP ${response.status}），请稍后重试`);
  }
  if (!response.headers.get('content-type')?.includes('application/json')) {
    throw new Error('向量接口返回了网页，请检查后端服务和开发代理是否启动');
  }
  return response.json() as Promise<T>;
}

function StatusBadge({ ready, skillName }: { ready: boolean; skillName: string }) {
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium ${
      ready
        ? 'bg-emerald-50 text-emerald-700 ring-1 ring-emerald-200'
        : skillName === 'common'
          ? 'bg-amber-50 text-amber-700 ring-1 ring-amber-200'
          : 'bg-gray-50 text-gray-500 ring-1 ring-gray-200'
    }`}>
      <span className={`h-1.5 w-1.5 rounded-full ${ready ? 'bg-emerald-500' : skillName === 'common' ? 'bg-amber-500' : 'bg-gray-400'}`} />
      {ready ? '已就绪' : skillName === 'common' ? '通用兜底' : '未就绪'}
    </span>
  );
}

function StatCard({ label, value, icon, trend }: { label: string; value: string | number; icon: React.ReactNode; trend?: 'up' | 'down' | 'neutral' }) {
  return (
    <div className="group relative overflow-hidden rounded-2xl border border-gray-100 bg-white p-6 shadow-sm transition-all hover:shadow-md hover:border-gray-200">
      <div className="flex items-start justify-between">
        <div>
          <p className="text-sm font-medium text-gray-500">{label}</p>
          <p className="mt-2 text-3xl font-bold tracking-tight text-gray-900">{value}</p>
        </div>
        <div className="rounded-xl bg-gray-50 p-3 text-gray-400 transition-colors group-hover:bg-brand/5 group-hover:text-brand">
          {icon}
        </div>
      </div>
      {trend && (
        <div className={`mt-4 inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-xs font-medium ${
          trend === 'up' ? 'bg-emerald-50 text-emerald-700' : trend === 'down' ? 'bg-red-50 text-red-700' : 'bg-gray-50 text-gray-600'
        }`}>
          {trend === 'up' ? '↑' : trend === 'down' ? '↓' : '→'} 实时数据
        </div>
      )}
    </div>
  );
}

function SimilarityBar({ item, threshold }: { item: SimilarityData['similarities'][0]; threshold: number }) {
  const matched = item.confidence !== null && item.confidence >= threshold;
  const percentage = Math.max(0, Math.min(1, item.confidence ?? 0)) * 100;

  return (
    <div className="group rounded-xl border border-gray-100 bg-white p-4 transition-all hover:border-gray-200 hover:shadow-sm">
      <div className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className={`h-2 w-2 rounded-full ${matched ? 'bg-emerald-500' : 'bg-gray-300'}`} />
          <span className="font-medium text-gray-900">{item.skillName}</span>
        </div>
        <span className={`text-sm font-semibold ${matched ? 'text-emerald-600' : 'text-gray-500'}`}>
          {item.confidence === null ? 'N/A' : `${item.confidence.toFixed(3)}`}
        </span>
      </div>
      <div className="relative h-2 overflow-hidden rounded-full bg-gray-100">
        <div
          className={`absolute inset-y-0 left-0 rounded-full transition-all duration-500 ${
            matched ? 'bg-gradient-to-r from-brand to-brand-deep' : 'bg-gray-300'
          }`}
          style={{ width: `${percentage}%` }}
        />
        {/* 阈值标记 */}
        <div
          className="absolute inset-y-0 w-0.5 bg-gray-400"
          style={{ left: `${threshold * 100}%` }}
        />
      </div>
      <div className="mt-2 flex items-center justify-between text-xs text-gray-500">
        <span>{matched ? '✓ 达到阈值' : '未达到阈值'}</span>
        <span>阈值: {threshold.toFixed(2)}</span>
      </div>
    </div>
  );
}

export default function VisualizationPage({ onBack }: { onBack: () => void }) {
  const [data, setData] = useState<SkillData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  const [message, setMessage] = useState('');
  const [testing, setTesting] = useState(false);
  const [testError, setTestError] = useState('');
  const [result, setResult] = useState<SimilarityData | null>(null);
  const [threshold, setThreshold] = useState(0.65);
  const testController = useRef<AbortController | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 15000);
    let active = true;
    setLoading(true);
    setError('');
    setData(null);
    request<SkillData>('embeddings', { signal: controller.signal })
      .then((fresh) => {
        if (!active) return;
        if (!Array.isArray(fresh.points) || typeof fresh.matchThreshold !== 'number'
          || fresh.points.some((point) => typeof point.embeddingReady !== 'boolean')) {
          throw new Error('后端诊断接口版本尚未更新，请重启后端服务后重试');
        }
        setData(fresh);
        setThreshold(fresh.matchThreshold);
      })
      .catch((reason: Error) => {
        if (active) setError(reason.name === 'AbortError' ? '向量接口响应超时，请检查后端服务后重试' : reason.message);
      })
      .finally(() => { window.clearTimeout(timeout); if (active) setLoading(false); });
    return () => { active = false; controller.abort(); window.clearTimeout(timeout); };
  }, [revision]);

  useEffect(() => () => { testController.current?.abort(); }, []);

  const testSimilarity = async () => {
    if (!message.trim() || testing) return;
    const controller = new AbortController();
    testController.current = controller;
    const timeout = window.setTimeout(() => controller.abort(), 45000);
    setTesting(true);
    setTestError('');
    setResult(null);
    try {
      const fresh = await request<SimilarityData>('similarity', {
        method: 'POST', headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
        body: message.trim(), signal: controller.signal,
      });
      if (!controller.signal.aborted) setResult(fresh);
    } catch (reason) {
      setTestError((reason as Error).name === 'AbortError' ? '相似度测试超时，请稍后重试' : (reason as Error).message);
    } finally {
      window.clearTimeout(timeout);
      setTesting(false);
      if (testController.current === controller) testController.current = null;
    }
  };

  const readyCount = data?.points.filter((point) => point.embeddingReady).length ?? 0;
  const totalExamples = data?.points.reduce((sum, point) => sum + point.exampleCount, 0) ?? 0;

  return (
    <div className="min-h-dvh bg-gradient-to-b from-gray-50 to-white">
      {/* 头部导航 */}
      <header className="sticky top-0 z-50 border-b border-gray-200/80 bg-white/80 backdrop-blur-xl">
        <div className="mx-auto flex max-w-7xl items-center justify-between px-6 py-4">
          <div className="flex items-center gap-4">
            <button
              type="button"
              onClick={onBack}
              className="flex items-center gap-2 rounded-xl border border-gray-200 bg-white px-4 py-2.5 text-sm font-medium text-gray-700 shadow-sm transition-all hover:bg-gray-50 hover:border-gray-300 hover:shadow-md focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand"
            >
              <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M10 19l-7-7m0 0l7-7m-7 7h18" />
              </svg>
              返回
            </button>
            <div>
              <h1 className="text-xl font-bold text-gray-900">技能向量诊断</h1>
              <p className="text-sm text-gray-500">实时监控技能路由和向量缓存状态</p>
            </div>
          </div>
          <button
            type="button"
            disabled={loading || testing}
            onClick={() => { setResult(null); setTestError(''); setRevision((value) => value + 1); }}
            className="flex items-center gap-2 rounded-xl bg-brand px-4 py-2.5 text-sm font-medium text-white shadow-sm transition-all hover:bg-brand-deep hover:shadow-md focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:brand disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <svg className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
            {loading ? '刷新中...' : '刷新状态'}
          </button>
        </div>
      </header>

      <main className="mx-auto max-w-7xl px-6 py-8">
        {/* 加载状态 */}
        {loading && (
          <div className="flex items-center justify-center py-20">
            <div className="text-center">
              <div className="mx-auto h-12 w-12 animate-spin rounded-full border-4 border-brand/20 border-t-brand" />
              <p className="mt-4 text-sm font-medium text-gray-600">正在读取技能向量状态...</p>
              <p className="mt-1 text-xs text-gray-400">首次加载可能需要几秒钟</p>
            </div>
          </div>
        )}

        {/* 错误状态 */}
        {error && (
          <div className="rounded-2xl border border-red-200 bg-red-50 p-6">
            <div className="flex items-start gap-4">
              <div className="rounded-full bg-red-100 p-2">
                <svg className="h-5 w-5 text-red-600" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L3.34 16.5c-.77.833.192 2.5 1.732 2.5z" />
                </svg>
              </div>
              <div>
                <h3 className="text-lg font-semibold text-red-800">加载失败</h3>
                <p className="mt-2 text-sm text-red-700">{error}</p>
                <button
                  type="button"
                  onClick={() => setRevision((value) => value + 1)}
                  className="mt-4 rounded-xl bg-red-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-red-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-red-600"
                >
                  重新加载
                </button>
              </div>
            </div>
          </div>
        )}

        {/* 主要内容 */}
        {data && (
          <div className="space-y-8">
            {/* 统计卡片 */}
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
              <StatCard
                label="已注册技能"
                value={data.totalSkills}
                icon={
                  <svg className="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M9.75 3.104v5.714a2.25 2.25 0 01-.659 1.591L5 14.5M9.75 3.104c-.251.023-.501.05-.75.082m.75-.082a24.301 24.301 0 014.5 0m0 0v5.714c0 .597.237 1.17.659 1.591L19.8 15.3M14.25 3.104c.251.023.501.05.75.082M19.8 15.3l-1.57.393A9.065 9.065 0 0112 15a9.065 9.065 0 00-6.23.693L5 14.5m14.8.8l1.402 1.402c1.232 1.232.65 3.318-1.067 3.611A48.309 48.309 0 0112 21c-2.773 0-5.491-.235-8.135-.687-1.718-.293-2.3-2.379-1.067-3.61L5 14.5" />
                  </svg>
                }
                trend="neutral"
              />
              <StatCard
                label="向量已就绪"
                value={readyCount}
                icon={
                  <svg className="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M9 12.75L11.25 15 15 9.75m-3-7.036A11.959 11.959 0 013.598 6 11.99 11.99 0 003 9.749c0 5.592 3.824 10.29 9 11.623 5.176-1.332 9-6.03 9-11.622 0-1.31-.21-2.571-.598-3.751h-.152c-3.196 0-6.1-1.248-8.25-3.285z" />
                  </svg>
                }
                trend={readyCount > 0 ? 'up' : 'down'}
              />
              <StatCard
                label="代表性示例"
                value={totalExamples}
                icon={
                  <svg className="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 1.125 0 0113.5 7.125v-1.5a3.375 3.375 0 00-3.375-3.375H8.25m0 12.75h7.5m-7.5 3H12M10.5 2.25H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z" />
                  </svg>
                }
                trend="neutral"
              />
              <StatCard
                label="路由匹配阈值"
                value={data.matchThreshold.toFixed(2)}
                icon={
                  <svg className="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M10.5 6h9.75M10.5 6a1.5 1.5 0 11-3 0m3 0a1.5 1.5 0 10-3 0M3.75 6H7.5m3 12h9.75m-9.75 0a1.5 1.5 0 01-3 0m3 0a1.5 1.5 0 00-3 0m-3.75 0H7.5m9-6h3.75m-3.75 0a1.5 1.5 0 01-3 0m3 0a1.5 1.5 0 00-3 0m-9.75 0h9.75" />
                  </svg>
                }
                trend="neutral"
              />
            </div>

            {/* 空状态提示 */}
            {data.totalSkills === 0 ? (
              <div className="rounded-2xl border-2 border-dashed border-gray-200 bg-gray-50/50 p-8 text-center">
                <div className="mx-auto h-12 w-12 rounded-full bg-gray-100 p-3">
                  <svg className="h-6 w-6 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M20.25 7.5l-.625 10.632a2.25 2.25 0 01-2.247 2.118H6.622a2.25 2.25 0 01-2.247-2.118L3.75 7.5m6 4.125l2.25 2.25m0 0l2.25 2.25M12 13.875l2.25-2.25M12 13.875l-2.25 2.25M3.375 7.5h17.25c.621 0 1.125-.504 1.125-1.125v-1.5c0-.621-.504-1.125-1.125-1.125H3.375c-.621 0-1.125.504-1.125 1.125v1.5c0 .621.504 1.125 1.125 1.125z" />
                  </svg>
                </div>
                <h3 className="mt-4 text-lg font-semibold text-gray-900">尚未注册技能</h3>
                <p className="mt-2 text-sm text-gray-500">请等待应用初始化完成后刷新页面</p>
              </div>
            ) : readyCount === 0 ? (
              <div className="rounded-2xl border border-amber-200 bg-amber-50 p-6">
                <div className="flex items-start gap-4">
                  <div className="rounded-full bg-amber-100 p-2">
                    <svg className="h-5 w-5 text-amber-600" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                      <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m9-.75a9 9 0 11-18 0 9 9 0 0118 0zm-9 3.75h.008v.008H12v-.008z" />
                    </svg>
                  </div>
                  <div>
                    <h3 className="text-lg font-semibold text-amber-800">向量缓存未就绪</h3>
                    <p className="mt-2 text-sm text-amber-700">
                      技能已注册，但向量缓存尚未就绪。请检查后端初始化日志、嵌入模型配置和连接状态。
                    </p>
                  </div>
                </div>
              </div>
            ) : null}

            {/* 主要内容区域 */}
            <div className="grid gap-8 lg:grid-cols-[1.2fr_1fr]">
              {/* 技能缓存表格 */}
              <section>
                <div className="mb-4 flex items-center justify-between">
                  <div>
                    <h2 className="text-lg font-bold text-gray-900">技能缓存</h2>
                    <p className="text-sm text-gray-500">实时监控所有技能的向量状态和示例数量</p>
                  </div>
                  <span className="rounded-full bg-gray-100 px-3 py-1 text-xs font-medium text-gray-600">
                    更新于 {new Date(data.timestamp).toLocaleTimeString()}
                  </span>
                </div>
                <div className="overflow-hidden rounded-2xl border border-gray-200 bg-white shadow-sm">
                  <div className="overflow-x-auto">
                    <table className="w-full">
                      <thead>
                        <tr className="border-b border-gray-100 bg-gray-50/50">
                          <th className="px-6 py-4 text-left text-xs font-semibold uppercase tracking-wider text-gray-500">技能名称</th>
                          <th className="px-4 py-4 text-center text-xs font-semibold uppercase tracking-wider text-gray-500">优先级</th>
                          <th className="px-4 py-4 text-center text-xs font-semibold uppercase tracking-wider text-gray-500">示例</th>
                          <th className="px-6 py-4 text-center text-xs font-semibold uppercase tracking-wider text-gray-500">向量状态</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-gray-100">
                        {data.points.map((point) => (
                          <tr key={point.skillName} className="transition-colors hover:bg-gray-50/50">
                            <td className="px-6 py-4">
                              <div>
                                <p className="font-semibold text-gray-900">{point.skillName}</p>
                                {point.description && (
                                  <p className="mt-1 line-clamp-2 text-xs leading-relaxed text-gray-500" title={point.description}>
                                    {point.description}
                                  </p>
                                )}
                                {point.tags.length > 0 && (
                                  <div className="mt-2 flex flex-wrap gap-1">
                                    {point.tags.slice(0, 3).map((tag) => (
                                      <span key={tag} className="rounded-full bg-gray-100 px-2 py-0.5 text-[10px] font-medium text-gray-600">
                                        {tag}
                                      </span>
                                    ))}
                                  </div>
                                )}
                              </div>
                            </td>
                            <td className="px-4 py-4 text-center">
                              <span className="inline-flex h-8 w-8 items-center justify-center rounded-lg bg-gray-100 text-sm font-bold text-gray-700">
                                {point.priority}
                              </span>
                            </td>
                            <td className="px-4 py-4 text-center">
                              <span className="text-sm font-semibold text-gray-900">{point.exampleCount}</span>
                            </td>
                            <td className="px-6 py-4 text-center">
                              <StatusBadge ready={point.embeddingReady} skillName={point.skillName} />
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              </section>

              {/* 消息相似度测试 */}
              <section>
                <div className="mb-4">
                  <h2 className="text-lg font-bold text-gray-900">消息相似度测试</h2>
                  <p className="text-sm text-gray-500">输入消息测试与各技能的匹配程度</p>
                </div>
                <div className="rounded-2xl border border-gray-200 bg-white p-6 shadow-sm">
                  <form onSubmit={(event) => { event.preventDefault(); void testSimilarity(); }} className="space-y-4">
                    <div>
                      <label className="mb-2 block text-sm font-medium text-gray-700" htmlFor="similarity-message">
                        测试消息
                      </label>
                      <textarea
                        id="similarity-message"
                        value={message}
                        maxLength={4000}
                        rows={3}
                        disabled={testing}
                        onChange={(event) => setMessage(event.target.value)}
                        placeholder="例如：杭州明天会下雨吗？"
                        className="w-full resize-none rounded-xl border border-gray-200 bg-gray-50 px-4 py-3 text-sm outline-none transition-all focus:border-brand focus:bg-white focus:ring-2 focus:ring-brand/20 disabled:opacity-50"
                      />
                    </div>

                    <div className="flex flex-wrap gap-2">
                      {['杭州明天会下雨吗？', '帮我规划周末旅行', '搜索附近的咖啡店'].map((example) => (
                        <button
                          key={example}
                          type="button"
                          disabled={testing}
                          onClick={() => setMessage(example)}
                          className="rounded-full border border-gray-200 bg-white px-3 py-1.5 text-xs font-medium text-gray-600 transition-all hover:border-brand hover:text-brand focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand disabled:opacity-40"
                        >
                          {example}
                        </button>
                      ))}
                    </div>

                    <button
                      type="submit"
                      disabled={testing || !message.trim() || readyCount === 0}
                      className="w-full rounded-xl bg-brand px-4 py-3 text-sm font-semibold text-white shadow-sm transition-all hover:bg-brand-deep hover:shadow-md focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      {testing ? (
                        <span className="flex items-center justify-center gap-2">
                          <svg className="h-4 w-4 animate-spin" fill="none" viewBox="0 0 24 24">
                            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                            <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                          </svg>
                          正在计算...
                        </span>
                      ) : '测试匹配'}
                    </button>
                  </form>

                  {testing && (
                    <div className="mt-4 flex items-center gap-3 rounded-xl bg-blue-50 p-4">
                      <div className="h-2 w-2 animate-pulse rounded-full bg-blue-500" />
                      <p className="text-sm text-blue-700">正在生成消息向量并逐个比较技能...</p>
                    </div>
                  )}

                  {testError && (
                    <div className="mt-4 rounded-xl bg-red-50 p-4">
                      <p className="text-sm text-red-700">{testError}</p>
                    </div>
                  )}

                  {result && (
                    <div className="mt-6 space-y-4">
                      <div className="rounded-xl bg-gray-50 p-4">
                        <p className="text-xs font-medium text-gray-500">测试消息</p>
                        <p className="mt-1 break-words text-sm text-gray-900">{result.message}</p>
                      </div>

                      <div>
                        <div className="mb-3 flex items-center justify-between">
                          <label htmlFor="threshold" className="text-sm font-medium text-gray-700">
                            匹配阈值预览
                          </label>
                          <span className="rounded-full bg-brand/10 px-2.5 py-1 text-xs font-semibold text-brand">
                            {threshold.toFixed(2)}
                          </span>
                        </div>
                        <input
                          id="threshold"
                          type="range"
                          min="0"
                          max="1"
                          step="0.01"
                          value={threshold}
                          onChange={(event) => setThreshold(Number(event.target.value))}
                          className="w-full accent-brand"
                        />
                        <p className="mt-1 text-xs text-gray-400">滑块仅用于预览，不修改后端路由阈值</p>
                      </div>

                      <div className="space-y-3">
                        {result.similarities.map((item) => (
                          <SimilarityBar key={item.skillName} item={item} threshold={threshold} />
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              </section>
            </div>
          </div>
        )}
      </main>
    </div>
  );
}
