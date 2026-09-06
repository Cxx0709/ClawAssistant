import { useCallback, useEffect, useState } from 'react';
import { fetchCurrentUser, fetchSetupStatus, logout } from './lib/api';
import type { AppUser } from './lib/types';
import AuthPage from './pages/AuthPage';
import ChatPage from './pages/ChatPage';
import Landing from './pages/Landing';
import VisualizationPage from './pages/VisualizationPage';
import MemoryPage from './pages/MemoryPage';
import RolesPage from './pages/RolesPage';

/**
 * 单页双视图（home / chat），状态切换代替路由 —— 避免后端 fallback 问题。
 * 构建产物直接由 Spring Boot 托管，任何路径都可回退到本入口。
 */
export default function App() {
  const [view, setView] = useState<'home' | 'chat' | 'visualization' | 'memories' | 'roles'>(() => {
    const params = new URLSearchParams(window.location.search);
    if (params.has('conversation') || params.has('radar')) return 'chat';
    if (params.has('diagnostics')) return 'visualization';
    if (params.has('memories') || params.has('visualization')) return 'memories';
    return 'home';
  });
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
  const goVisualization = useCallback(() => {
    window.history.replaceState(null, '', '?memories');
    setView('memories');
  }, []);
  const goRoles = useCallback(() => {
    window.history.replaceState(null, '', '?roles');
    setView('roles');
  }, []);
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

  if (view === 'visualization') {
    return <VisualizationPage onBack={goHome} />;
  }

  if (view === 'memories') return <MemoryPage onBack={goHome} />;

  if (view === 'roles') return <RolesPage onBack={goHome} />;

  return view === 'chat'
    ? <ChatPage onHome={goHome} user={user} onLogout={signOut} onGoRoles={goRoles} />
    : <Landing onStart={goChat} onVisualization={goVisualization} user={user} onLogout={signOut} />;
}
