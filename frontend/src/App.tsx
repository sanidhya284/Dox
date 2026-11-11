import { useCallback, useEffect, useMemo, useState } from 'react';
import { AuthSession } from './api/auth';
import { createApi } from './api/http';
import { AuthScreen } from './ui/AuthScreen';
import { ChatApp } from './ui/ChatApp';
import { BuildInfo } from './ui/BuildInfo';
import { GuestExpired } from './ui/GuestExpired';

type Phase = 'checking' | 'anonymous' | 'authenticated' | 'guest_expired';

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

  const loggedOut = useCallback(() => setPhase('anonymous'), []);
  const guestExpired = useCallback(() => setPhase('guest_expired'), []);

  let view;
  if (phase === 'checking') {
    view = <main className="splash" aria-busy="true"><p className="dim">dox ▸ connecting<span className="caret">█</span></p></main>;
  } else if (phase === 'anonymous') {
    view = <AuthScreen auth={auth} onAuthenticated={() => setPhase('authenticated')} />;
  } else if (phase === 'guest_expired') {
    view = <GuestExpired auth={auth} onAuthenticated={() => setPhase('authenticated')} onSignUp={loggedOut} />;
  } else {
    view = <ChatApp auth={auth} api={api} onLoggedOut={loggedOut} onGuestExpired={guestExpired} />;
  }
  return (
    <>
      {view}
      <BuildInfo />
    </>
  );
}
