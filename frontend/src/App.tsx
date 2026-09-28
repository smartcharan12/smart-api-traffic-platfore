import { useEffect, useState } from 'react';
import { BrowserRouter, NavLink, Route, Routes } from 'react-router-dom';
import { getSession, onSessionChange, setSession } from './api/auth';
import { api } from './api/client';
import { HealthIndicator } from './components/HealthIndicator';
import { usePolling } from './hooks/usePolling';
import { CircuitBreakersPage } from './pages/CircuitBreakersPage';
import { FailuresPage } from './pages/FailuresPage';
import { HealthPage } from './pages/HealthPage';
import { LoginPage } from './pages/LoginPage';
import { OverviewPage } from './pages/OverviewPage';
import { RateLimitPage } from './pages/RateLimitPage';
import { RequestLogsPage } from './pages/RequestLogsPage';
import { ServicesPage } from './pages/ServicesPage';
import { TrafficPage } from './pages/TrafficPage';

const PAGES = [
  { path: '/', label: 'Overview', element: <OverviewPage /> },
  { path: '/services', label: 'Services', element: <ServicesPage /> },
  { path: '/health', label: 'Service health', element: <HealthPage /> },
  { path: '/traffic', label: 'Traffic', element: <TrafficPage /> },
  { path: '/rate-limiting', label: 'Rate limiting', element: <RateLimitPage /> },
  { path: '/failures', label: 'Failures', element: <FailuresPage /> },
  { path: '/circuit-breakers', label: 'Circuit breakers', element: <CircuitBreakersPage /> },
  { path: '/logs', label: 'Request logs', element: <RequestLogsPage /> },
];

export default function App() {
  const [session, setCurrent] = useState(getSession());
  useEffect(() => onSessionChange(() => setCurrent(getSession())), []);
  return session ? <Dashboard username={session.username} role={session.role} /> : <LoginPage />;
}

function Dashboard({ username, role }: { username: string; role: string }) {
  const health = usePolling(() => api.health(), 5000);
  return (
    <BrowserRouter>
      <div className="app">
        <header>
          <h1>Smart API Traffic Platform</h1>
          <div className="header-right">
            <HealthIndicator health={health.data} />
            <span className="muted">
              {username} ({role})
            </span>
            <button className="small" onClick={() => setSession(null)}>
              Sign out
            </button>
          </div>
        </header>
        <nav className="nav">
          {PAGES.map((p) => (
            <NavLink key={p.path} to={p.path} end={p.path === '/'}>
              {p.label}
            </NavLink>
          ))}
        </nav>
        <Routes>
          {PAGES.map((p) => (
            <Route key={p.path} path={p.path} element={p.element} />
          ))}
        </Routes>
      </div>
    </BrowserRouter>
  );
}
