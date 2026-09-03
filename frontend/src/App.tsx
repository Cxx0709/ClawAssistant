import { useCallback, useEffect, useState } from 'react';
import { fetchCurrentUser, fetchSetupStatus, logout } from './lib/api';
import type { AppUser } from './lib/types';
import AuthPage from './pages/AuthPage';
import ChatPage from './pages/ChatPage';
import Landing from './pages/Landing';

/**
 * 单页双视图（home / chat），状态切换代替路由 —— 避免后端 fallback 问题。
 * 构建产物直接由 Spring Boot 托管，任何路径都可回退到本入口。
 */
export default function App() {
  const [view, setView] = useState<'home' | 'chat'>(() =>
    new URLSearchParams(window.location.search).has('conversation') ? 'chat' : 'home');
  const [user, setUser] = useState<AppUser | null>(null);
  const [setupRequired, setSetupRequired] = useState(false);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let alive = true;
    fetchSetupStatus()
      .then(async (required) => {
        if (!alive) return;
        setSetupRequired(required);
        if (!required) {
          try {
            const current = await fetchCurrentUser();
            if (alive) setUser(current);
          } catch {
            // 未登录是正常入口。
          }
        }
      })
      .finally(() => alive && setLoading(false));
    return () => { alive = false; };
  }, []);

  const goHome = useCallback(() => {
    window.history.replaceState(null, '', window.location.pathname);
    setView('home');
  }, []);
  const goChat = useCallback(() => setView('chat'), []);
  const signOut = useCallback(async () => {
    await logout();
    setUser(null);
    setSetupRequired(false);
    setView('home');
  }, []);

  if (loading) {
    return <div className="flex min-h-dvh items-center justify-center bg-canvas text-sm text-ink-faint">正在加载…</div>;
  }

  if (!user) {
    return <AuthPage setupRequired={setupRequired} onAuthenticated={(next) => {
      setUser(next);
      setSetupRequired(false);
    }} />;
  }

  return view === 'chat'
    ? <ChatPage onHome={goHome} user={user} onLogout={signOut} />
    : <Landing onStart={goChat} user={user} onLogout={signOut} />;
}
