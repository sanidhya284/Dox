import { useState } from 'react';
import type { AuthSession } from '../api/auth';

/** Close code 4008 (ADR-0012): the guest account reached 24 hours and was removed. */
export function GuestExpired({ auth, onAuthenticated, onSignUp }: {
  auth: AuthSession;
  onAuthenticated: () => void;
  onSignUp: () => void;
}) {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function again() {
    setBusy(true);
    const r = await auth.guest();
    setBusy(false);
    if (r.ok) {
      onAuthenticated();
    } else {
      setError(r.code === 'DEMO_BUSY'
        ? 'the demo is full right now. sign up instead: it\'s free.'
        : 'a new demo couldn\'t start. try again in a moment.');
    }
  }

  return (
    <main className="splash">
      <section className="window" aria-labelledby="expired-title">
        <div className="window-bar"><span>dox</span><span>session closed (4008)</span></div>
        <div className="window-body">
          <h1 id="expired-title" className="headline">your demo has ended.</h1>
          <p className="dim">guest accounts last 24 hours. this one and its conversations have been deleted.</p>
          {error && <p role="alert" className="error">error: {error}</p>}
          <div className="auth-actions">
            <button type="button" className="cmd cmd-primary" onClick={onSignUp}>[ sign up ]</button>
            <button type="button" className="cmd" onClick={() => void again()} disabled={busy}>[ start a new demo ]</button>
          </div>
        </div>
      </section>
    </main>
  );
}
