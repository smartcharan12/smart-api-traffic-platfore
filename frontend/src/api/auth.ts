/**
 * Holds the access token for this browser tab. sessionStorage survives page reloads but not closing
 * the tab. Trade-off: any script running on the page (an XSS bug) could read it. The alternative, an
 * HttpOnly cookie, hides it from scripts but brings back CSRF, which this Bearer-token API avoids.
 * The token also expires after 1 hour.
 */
export interface Session {
  token: string;
  username: string;
  role: 'ADMIN' | 'USER';
  expiresAt: string;
}

const KEY = 'smart-api-session';
const listeners = new Set<() => void>();

export function getSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    if (!raw) return null;
    const session = JSON.parse(raw) as Session;
    return new Date(session.expiresAt).getTime() > Date.now() ? session : null;
  } catch {
    return null;
  }
}

export function setSession(session: Session | null) {
  try {
    if (session) sessionStorage.setItem(KEY, JSON.stringify(session));
    else sessionStorage.removeItem(KEY);
  } catch {
    // storage unavailable (private mode): the session just won't survive a reload
  }
  listeners.forEach((l) => l());
}

export function onSessionChange(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export const isAdmin = () => getSession()?.role === 'ADMIN';
