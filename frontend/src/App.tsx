import { useEffect, useMemo, useState } from 'react';
import { AuthSession } from './api/auth';
import { createApi } from './api/http';
import { LoginForm } from './ui/LoginForm';
import { ChatView } from './ui/ChatView';
import { BuildInfo } from './ui/BuildInfo';

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

  let view;
  if (phase === 'checking') {
    view = <main className="center">Loading…</main>;
  } else if (phase === 'anonymous') {
    view = <LoginForm auth={auth} onAuthenticated={() => setPhase('authenticated')} />;
  } else {
    view = <ChatView auth={auth} api={api} onLoggedOut={() => setPhase('anonymous')} />;
  }
  return (
    <>
      {view}
      <BuildInfo />
    </>
  );
}
