import { useState } from 'react';
import { api, setToken } from '../api.js';

/**
 * Holding the invite link is not enough - the candidate must sign in, and the
 * backend checks the signed-in user really is the one this interview was
 * scheduled for.
 */
export default function LoginScreen({ onSignedIn }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const result = await api.login(email, password);
      setToken(result.token);
      onSignedIn(result.user);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="centre-wrap">
      <form className="card narrow" onSubmit={submit}>
        <h1>Sign in to your interview</h1>
        <p className="muted">Use the account your recruiter created for you.</p>

        {error && <div className="alert error">{error}</div>}

        <label htmlFor="email">Email</label>
        <input
          id="email"
          type="email"
          value={email}
          autoComplete="username"
          onChange={(e) => setEmail(e.target.value)}
          required
        />

        <label htmlFor="password">Password</label>
        <input
          id="password"
          type="password"
          value={password}
          autoComplete="current-password"
          onChange={(e) => setPassword(e.target.value)}
          required
        />

        <button type="submit" disabled={busy}>
          {busy ? 'Signing in...' : 'Sign in'}
        </button>
      </form>
    </div>
  );
}
