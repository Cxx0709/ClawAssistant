import { useState } from 'react';
import BrandMark from '../components/BrandMark';
import { login, registerAccount, setupAccount } from '../lib/api';
import type { AppUser } from '../lib/types';

interface AuthPageProps {
  setupRequired: boolean;
  onAuthenticated: (user: AppUser) => void;
}

export default function AuthPage({ setupRequired, onAuthenticated }: AuthPageProps) {
  const [registering, setRegistering] = useState(setupRequired);
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      if (registering) {
        if (setupRequired) await setupAccount(username, password, displayName);
        else await registerAccount(username, password, displayName);
      }
      onAuthenticated(await login(username, password));
    } catch (reason) {
      setError((reason as Error)?.message || '操作失败，请重试');
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="flex min-h-dvh items-center justify-center bg-canvas px-5 py-10 text-ink">
      <div className="w-full max-w-[390px] rounded-3xl border border-line bg-white p-7 shadow-pop sm:p-9">
        <div className="flex items-center gap-3">
          <BrandMark size={42} />
          <div>
            <h1 className="text-lg font-semibold tracking-tight">知行</h1>
            <p className="text-xs text-ink-faint">ZHIXING · AGENT · 你的私人 Web 智能助理</p>
          </div>
        </div>

        <h2 className="mt-8 text-2xl font-semibold tracking-tight">
          {setupRequired ? '创建首个账户' : registering ? '创建账户' : '欢迎回来'}
        </h2>
        <p className="mt-1.5 text-sm leading-relaxed text-ink-soft">
          {setupRequired
            ? '此账户会接管原有助手数据；历史记录无需迁移。'
            : registering ? '每个账户的对话、文件和通知相互隔离。' : '登录后继续你的对话和目标。'}
        </p>

        <form onSubmit={submit} className="mt-6 space-y-4">
          {registering && (
            <label className="block text-sm text-ink-soft">
              显示名称
              <input
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                autoComplete="name"
                required
                className="mt-1.5 w-full rounded-xl border border-line bg-canvas px-3.5 py-2.5 text-ink outline-none transition focus:border-brand/60"
              />
            </label>
          )}
          <label className="block text-sm text-ink-soft">
            用户名
            <input
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              minLength={3}
              required
              className="mt-1.5 w-full rounded-xl border border-line bg-canvas px-3.5 py-2.5 text-ink outline-none transition focus:border-brand/60"
            />
          </label>
          <label className="block text-sm text-ink-soft">
            密码
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete={registering ? 'new-password' : 'current-password'}
              minLength={8}
              required
              className="mt-1.5 w-full rounded-xl border border-line bg-canvas px-3.5 py-2.5 text-ink outline-none transition focus:border-brand/60"
            />
          </label>

          {error && <p className="rounded-xl bg-[#fdf3f3] px-3 py-2.5 text-sm text-[#c0392b]">{error}</p>}

          <button
            type="submit"
            disabled={busy}
            className="w-full rounded-xl bg-brand px-4 py-2.5 font-medium text-white transition hover:bg-brand-deep disabled:opacity-60"
          >
            {busy ? '请稍候…' : registering ? '创建并登录' : '登录'}
          </button>
        </form>

        {!setupRequired && (
          <button
            type="button"
            onClick={() => { setRegistering((value) => !value); setError(''); }}
            className="mt-5 w-full text-sm text-brand-deep hover:underline"
          >
            {registering ? '已有账户？返回登录' : '没有账户？立即注册'}
          </button>
        )}
      </div>
    </main>
  );
}
