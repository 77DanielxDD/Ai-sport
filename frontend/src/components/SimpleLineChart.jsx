export default function SimpleLineChart({
  points = [],
  width = 640,
  height = 220,
  yMin = 0,
  yMax = 180,
  xLabel = "序列",
  yLabel = "值",
}) {
  if (!points.length) {
    return <p>暂无可绘制数据</p>;
  }

  const pad = { l: 40, r: 16, t: 12, b: 30 };
  const w = width - pad.l - pad.r;
  const h = height - pad.t - pad.b;

  const xs = points.map((p) => p.x);
  const xMin = Math.min(...xs);
  const xMax = Math.max(...xs);
  const safeXMax = xMax === xMin ? xMin + 1 : xMax;
  const safeYMax = yMax === yMin ? yMin + 1 : yMax;

  const toSvgX = (x) => pad.l + ((x - xMin) / (safeXMax - xMin)) * w;
  const toSvgY = (y) => pad.t + (1 - (y - yMin) / (safeYMax - yMin)) * h;

  const polyline = points.map((p) => `${toSvgX(p.x)},${toSvgY(p.y)}`).join(" ");

  return (
    <svg viewBox={`0 0 ${width} ${height}`} width="100%" height={height} role="img" aria-label="line chart">
      <line x1={pad.l} y1={pad.t} x2={pad.l} y2={height - pad.b} stroke="#64748b" strokeWidth="1" />
      <line x1={pad.l} y1={height - pad.b} x2={width - pad.r} y2={height - pad.b} stroke="#64748b" strokeWidth="1" />

      <polyline fill="none" stroke="#1d4ed8" strokeWidth="2.5" points={polyline} />
      {points.map((p, idx) => (
        <g key={`${p.x}-${idx}`}>
          <circle cx={toSvgX(p.x)} cy={toSvgY(p.y)} r="3.5" fill="#0ea5e9" />
          <text x={toSvgX(p.x)} y={height - 10} textAnchor="middle" fontSize="11" fill="#334155">
            {p.x}
          </text>
        </g>
      ))}

      <text x={width / 2} y={height - 2} textAnchor="middle" fontSize="12" fill="#475569">
        {xLabel}
      </text>
      <text x={14} y={height / 2} textAnchor="middle" fontSize="12" fill="#475569" transform={`rotate(-90 14 ${height / 2})`}>
        {yLabel}
      </text>
    </svg>
  );
}
