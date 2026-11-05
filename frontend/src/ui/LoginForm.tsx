import { useState, type FormEvent } from 'react';
import type { AuthSession } from '../api/auth';

const MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: 'Wrong username or password.',
  REGISTRATION_CLOSED: 'Registration needs a valid invite code.',
  USERNAME_TAKEN: 'That username is taken.',
  EMAIL_TAKEN: 'That email is already registered.',
  RATE_LIMITED: 'Too many attempts. Wait a minute and try again.',
  VALIDATION_ERROR: 'Check the fields: username 3–50 characters, password at least 8.',
};

export function LoginForm({ auth, onAuthenticated }: { auth: AuthSession; onAuthenticated: () => void }) {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [email, setEmail] = useState('');
  const [inviteCode, setInviteCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const r = mode === 'login'
      ? await auth.login(username, password)
      : await auth.register({ username, email, password, displayName: username, inviteCode });
    setBusy(false);
    if (r.ok) {
      onAuthenticated();
    } else {
      setError(MESSAGES[r.code ?? ''] ?? `Request failed (${r.status}).`);
    }
  }

  return (
    <main className="center">
      <form className="card" onSubmit={submit} aria-label={mode === 'login' ? 'Log in' : 'Register'}>
        <h1>{mode === 'login' ? 'Log in' : 'Register'}</h1>
        <p className="note">This is a demo. Everything you write here is visible to other demo users: don't post personal data.</p>
        <label>Username <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required /></label>
        {mode === 'register' && (
          <label>Email <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="email" required /></label>
        )}
        <label>Password <input type="password" value={password} onChange={(e) => setPassword(e.target.value)}
          autoComplete={mode === 'login' ? 'current-password' : 'new-password'} required /></label>
        {mode === 'register' && (
          <label>Invite code <input value={inviteCode} onChange={(e) => setInviteCode(e.target.value)} required /></label>
        )}
        {error && <p role="alert" className="error">{error}</p>}
        <button type="submit" disabled={busy}>{mode === 'login' ? 'Log in' : 'Create account'}</button>
        <button type="button" className="link" onClick={() => setMode(mode === 'login' ? 'register' : 'login')}>
          {mode === 'login' ? 'Have an invite code? Register' : 'Already registered? Log in'}
        </button>
      </form>
    </main>
  );
}
