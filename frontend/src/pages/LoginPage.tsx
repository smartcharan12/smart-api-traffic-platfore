import { useState, type FormEvent } from 'react';
import { setSession } from '../api/auth';
import { api, ApiError } from '../api/client';
import { FieldError } from '../components/RegisterServiceForm';

export function LoginPage() {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<ApiError | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      if (mode === 'register') {
        await api.register(username.trim(), email.trim(), password);
        setNotice('Account created. You can sign in now (role: USER, read-only).');
        setMode('login');
      } else {
        setSession(await api.login(username.trim(), password));
      }
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError(0, String(e)));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login">
      <h1>Smart API Traffic Platform</h1>
      <form onSubmit={submit} className="form">
        <h3>{mode === 'login' ? 'Sign in' : 'Create an account'}</h3>
        <label>
          Username
          <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required />
          <FieldError error={error} field="username" />
        </label>
        {mode === 'register' && (
          <label>
            Email
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
            <FieldError error={error} field="email" />
          </label>
        )}
        <label>
          Password {mode === 'register' && <span className="muted">(12–72 characters)</span>}
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            required
          />
          <FieldError error={error} field="password" />
        </label>
        {error && Object.keys(error.fieldErrors).length === 0 && <p className="error">{error.message}</p>}
        {notice && <p className="muted">{notice}</p>}
        <button type="submit" disabled={busy}>
          {busy ? '…' : mode === 'login' ? 'Sign in' : 'Register'}
        </button>
        <button type="button" className="link" onClick={() => setMode(mode === 'login' ? 'register' : 'login')}>
          {mode === 'login' ? 'No account? Register (read-only USER)' : 'Back to sign in'}
        </button>
      </form>
    </div>
  );
}
