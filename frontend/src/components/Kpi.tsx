export function Kpi({ label, value, tone }: { label: string; value: string | number; tone?: 'good' | 'warn' | 'bad' }) {
  return (
    <div className={`card ${tone ? `card-${tone}` : ''}`}>
      <div className="card-value">{value}</div>
      <div className="card-label">{label}</div>
    </div>
  );
}

export const fmtMs = (v: number | null | undefined) => (v === null || v === undefined ? '—' : `${Math.round(v)} ms`);
export const fmtTime = (iso: string | null | undefined) => (iso ? new Date(iso).toLocaleTimeString() : '—');
export const fmtDuration = (seconds: number | null | undefined) => {
  if (seconds === null || seconds === undefined) return '—';
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
  return `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m`;
};
export const minuteLabel = (iso: string) =>
  new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
