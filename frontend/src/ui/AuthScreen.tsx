import { useState, type FormEvent } from 'react';
import type { AuthSession } from '../api/auth';

const MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: 'wrong username or password.',
  REGISTRATION_CLOSED: 'sign-up is closed on this server.',
  USERNAME_TAKEN: 'that username is taken.',
  EMAIL_TAKEN: 'that email is already registered.',
  RATE_LIMITED: 'too many attempts. wait a little and try again.',
  VALIDATION_ERROR: 'check the fields: username 3–50 characters (not starting with guest- or deleted-), '
    + 'a valid email, password at least 8 characters.',
  DEMO_BUSY: 'the demo is full right now. sign up instead: it takes ten seconds and it\'s free.',
};

type Mode = 'signup' | 'login';

/** Sign up, log in, or try the demo as a 24-hour guest (ADR-0012), in one terminal window. */
export function AuthScreen({ auth, onAuthenticated }: { auth: AuthSession; onAuthenticated: () => void }) {
  const [mode, setMode] = useState<Mode>('signup');
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const r = mode === 'login'
      ? await auth.login(username, password)
      : await auth.register({ username, email, password, displayName: displayName.trim() || username });
    setBusy(false);
    if (r.ok) {
      onAuthenticated();
    } else {
      setError(MESSAGES[r.code ?? ''] ?? `request failed (${r.status}). try again.`);
    }
  }

  async function tryDemo() {
    setBusy(true);
    setError(null);
    const r = await auth.guest();
    setBusy(false);
    if (r.ok) {
      onAuthenticated();
    } else {
      if (r.code === 'DEMO_BUSY') {
        setMode('signup'); // point to sign-up: it always works when the demo is full
      }
      setError(MESSAGES[r.code ?? ''] ?? `the demo couldn't start (${r.status}). try again.`);
    }
  }

  return (
    <main className="auth">
      <section className="window" aria-labelledby="auth-title">
        <div className="window-bar"><span>dox — realtime chat</span><span>tty1</span></div>
        <div className="window-body">
          <pre className="boot" aria-hidden="true">
            <span className="ok">[ ok ]</span> every message stored before it&apos;s acknowledged{'\n'}
            <span className="ok">[ ok ]</span> ordered by seq, no gaps{'\n'}
            <span className="ok">[ ok ]</span> recovered after any dropped connection
          </pre>
          <h1 id="auth-title" className="headline">messages that arrive.<span className="caret" aria-hidden="true">█</span></h1>
          <div className="auth-actions">
            <button type="button" className="cmd cmd-primary" onClick={() => void tryDemo()} disabled={busy}>
              [ try the demo ]
            </button>
            <span className="hint">no sign-up: a 24-hour guest account with a bot to talk to, then it&apos;s deleted.</span>
          </div>

          <div className="tabs" role="tablist" aria-label="Account">
            <button type="button" role="tab" aria-selected={mode === 'signup'} className="tab"
              onClick={() => { setMode('signup'); setError(null); }}>sign up</button>
            <button type="button" role="tab" aria-selected={mode === 'login'} className="tab"
              onClick={() => { setMode('login'); setError(null); }}>log in</button>
          </div>
          <form onSubmit={submit} className="stack" aria-label={mode === 'signup' ? 'Sign up' : 'Log in'}>
            <label className="field">
              <span>username:</span>
              <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username"
                minLength={3} maxLength={50} required />
            </label>
            {mode === 'signup' && (
              <>
                <label className="field">
                  <span>display name (optional):</span>
                  <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} autoComplete="name"
                    maxLength={100} />
                </label>
                <label className="field">
                  <span>email:</span>
                  <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="email"
                    required />
                </label>
              </>
            )}
            <label className="field">
              <span>password:</span>
              <input type="password" value={password} onChange={(e) => setPassword(e.target.value)}
                autoComplete={mode === 'login' ? 'current-password' : 'new-password'} minLength={8} required />
            </label>
            {error && <p role="alert" className="error">error: {error}</p>}
            <div className="auth-actions">
              <button type="submit" className="cmd cmd-primary" disabled={busy}>
                {mode === 'signup' ? '[ create account ]' : '[ log in ]'}
              </button>
              <span className="hint">public demo: don&apos;t post anything personal.</span>
            </div>
          </form>
        </div>
      </section>
    </main>
  );
}
