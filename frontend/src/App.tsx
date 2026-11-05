import { useEffect, useMemo, useState } from 'react';
import { AuthSession } from './api/auth';
import { createApi } from './api/http';
import { LoginForm } from './ui/LoginForm';
import { ChatView } from './ui/ChatView';

type Phase = 'checking' | 'anonymous' | 'authenticated';

export function App() {
  const auth = useMemo(() => new AuthSession(), []);
  const api = useMemo(() => createApi(auth), [auth]);
  const [phase, setPhase] = useState<Phase>('checking');

  // After a reload the access token is gone (memory only); the HttpOnly cookie gets a new one.
  useEffect(() => {
    let cancelled = false;
    void auth.refresh().then((ok) => {
      if (!cancelled) {
        setPhase(ok ? 'authenticated' : 'anonymous');
      }
    });
    return () => {
      cancelled = true;
    };
  }, [auth]);

  if (phase === 'checking') {
    return <main className="center">Loading…</main>;
  }
  if (phase === 'anonymous') {
    return <LoginForm auth={auth} onAuthenticated={() => setPhase('authenticated')} />;
  }
  return (
    <ChatView
      auth={auth}
      api={api}
      onLoggedOut={() => setPhase('anonymous')}
    />
  );
}
