/**
 * Minimal SVG line chart: a few series over a shared x axis (time). No chart library needed for
 * what the dashboard shows; about 80 lines are easier to explain than a dependency.
 */
export interface Series {
  name: string;
  color: string;
  values: (number | null)[];
}

interface Props {
  labels: string[];
  series: Series[];
  unit?: string;
  height?: number;
}

const W = 640;
const PAD = { top: 10, right: 12, bottom: 24, left: 48 };

export function LineChart({ labels, series, unit = '', height = 200 }: Props) {
  if (labels.length === 0) return <p className="empty">No data in this time window yet.</p>;
  const all = series.flatMap((s) => s.values.filter((v): v is number => v !== null));
  const max = Math.max(1, ...all);
  const x = (i: number) => PAD.left + (labels.length === 1 ? 0 : (i * (W - PAD.left - PAD.right)) / (labels.length - 1));
  const y = (v: number) => height - PAD.bottom - (v / max) * (height - PAD.top - PAD.bottom);
  const ticks = [0, max / 2, max];
  const labelEvery = Math.max(1, Math.ceil(labels.length / 6));

  return (
    <figure className="chart">
      <svg viewBox={`0 0 ${W} ${height}`} role="img" aria-label={series.map((s) => s.name).join(', ')}>
        {ticks.map((t) => (
          <g key={t}>
            <line x1={PAD.left} x2={W - PAD.right} y1={y(t)} y2={y(t)} className="chart-grid" />
            <text x={PAD.left - 6} y={y(t) + 4} textAnchor="end" className="chart-axis">
              {formatTick(t)}
              {unit}
            </text>
          </g>
        ))}
        {labels.map((l, i) =>
          i % labelEvery === 0 ? (
            <text key={l + i} x={x(i)} y={height - 6} textAnchor="middle" className="chart-axis">
              {l}
            </text>
          ) : null,
        )}
        {series.map((s) =>
          segments(s.values).map((segment, k) => (
            <polyline
              key={`${s.name}-${k}`}
              fill="none"
              stroke={s.color}
              strokeWidth={2}
              points={segment.map(([i, v]) => `${x(i)},${y(v)}`).join(' ')}
            />
          )),
        )}
      </svg>
      <figcaption className="chart-legend">
        {series.map((s) => (
          <span key={s.name}>
            <i style={{ background: s.color }} /> {s.name}
          </span>
        ))}
      </figcaption>
    </figure>
  );
}

/** Splits a series at nulls (minutes without data) so the line is not drawn across the gap. */
function segments(values: (number | null)[]): [number, number][][] {
  const result: [number, number][][] = [];
  let current: [number, number][] = [];
  values.forEach((v, i) => {
    if (v === null) {
      if (current.length) result.push(current);
      current = [];
    } else {
      current.push([i, v]);
    }
  });
  if (current.length) result.push(current);
  return result;
}

function formatTick(v: number) {
  return v >= 100 ? Math.round(v).toString() : v >= 10 ? v.toFixed(0) : v.toFixed(1);
}
